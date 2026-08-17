package com.mindtable.bitbuckethelper

import com.mindtable.bitbuckethelper.adapter.outbound.persistence.JooqApplicationPersistence
import com.mindtable.bitbuckethelper.application.model.NotificationDeliveryKey
import com.mindtable.bitbuckethelper.application.model.NotificationIntentInsertResult
import com.mindtable.bitbuckethelper.application.model.NotificationIntentState
import com.mindtable.bitbuckethelper.application.model.NotificationRequest
import com.mindtable.bitbuckethelper.application.model.NotificationSound
import com.mindtable.bitbuckethelper.application.model.StoredConfiguredRepository
import com.mindtable.bitbuckethelper.application.model.StoredInstallationConfiguration
import com.mindtable.bitbuckethelper.application.model.StoredNotificationIntent
import com.mindtable.bitbuckethelper.bootstrap.BitbucketCredentials
import com.mindtable.bitbuckethelper.bootstrap.ServiceConfiguration
import com.mindtable.bitbuckethelper.bootstrap.ServiceRuntime
import com.mindtable.bitbuckethelper.bootstrap.ServiceRuntimeLifecycleProbe
import com.mindtable.bitbuckethelper.domain.shared.NotificationIntentId
import com.mindtable.bitbuckethelper.domain.shared.RepositoryId
import com.mindtable.bitbuckethelper.domain.shared.WorkspaceId
import com.mindtable.bitbuckethelper.support.FakeDesktopNotificationsExecutable
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.net.InetSocketAddress
import java.net.URI
import java.nio.charset.StandardCharsets.ISO_8859_1
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.sql.DriverManager
import java.sql.SQLException
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.TimeUnit
import kotlin.io.path.exists
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir

class NotificationIntegrationTest {
    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    fun `committed rejection survives restart lease expiry and retries unchanged through Quartz`(
        @TempDir directory: Path,
    ) {
        val existingSchedulerThreads = schedulerThreadIds()
        val captured = captureDiagnostics {
            runBlocking {
                Files.setPosixFilePermissions(directory, PosixFilePermissions.fromString("rwx------"))
                val databasePath = directory.resolve("notification.sqlite")
                val firstSocket = directory.resolve("first.sock")
                val secondSocket = directory.resolve("second.sock")
                val provider = ProviderFixture.create(directory)
                val bitbucket = FakeBitbucketServer.withRawActivity(RAW_CONTENT_MARKER)
                try {
                    val clock = MutableClock(Instant.parse("2026-08-15T12:34:00Z"))
                    seedConfiguration(databasePath, bitbucket.baseUrl)

                    val firstRuntime = runtime(
                        databasePath = databasePath,
                        socketPath = firstSocket,
                        executable = provider.executable,
                        serviceClock = clock,
                        schedulerClock = Clock.systemUTC(),
                    )
                    try {
                        firstRuntime.start()
                        eventuallyWithin(
                            stage = "first provider launch",
                            timeout = Duration.ofSeconds(10),
                            diagnostics = {
                                "requests=${bitbucket.requestPaths()} intents=${runCatching { loadIntentRows(databasePath) }.getOrNull()}"
                            },
                        ) { provider.firstRejectedAttemptStarted.takeIf(Path::exists) }

                        val committedBeforeLaunch = loadIntentRows(databasePath)
                        val retryable = committedBeforeLaunch.single { it.deliveryKey.startsWith("actionable:") }
                        val initialDigest = committedBeforeLaunch.single { it.deliveryKey.startsWith("initial-digest:") }
                        assertEquals(NotificationIntentState.PENDING.name, retryable.state)
                        assertEquals(0, retryable.attemptCount)
                        assertNotNull(retryable.leaseOwner, "the committed claim must be visible before process completion")
                        assertEquals(0, attemptCount(databasePath, retryable.id))

                        Files.createFile(provider.releaseFirstRejectedAttempt)
                        eventuallyWithin("rejection persisted", Duration.ofSeconds(10)) {
                            loadIntentRows(databasePath)
                                .singleOrNull { it.id == retryable.id }
                                ?.takeIf { it.state == NotificationIntentState.PENDING.name && it.attemptCount == 1 }
                        }
                        eventuallyWithin("initial digest accepted", Duration.ofSeconds(10)) {
                            loadIntentRows(databasePath)
                                .singleOrNull { it.id == initialDigest.id }
                                ?.takeIf { it.state == NotificationIntentState.ACCEPTED.name }
                        }
                    } finally {
                        if (provider.firstRejectedAttemptStarted.exists() && !provider.releaseFirstRejectedAttempt.exists()) {
                            Files.createFile(provider.releaseFirstRejectedAttempt)
                        }
                        firstRuntime.close()
                    }
                    assertFalse(firstSocket.exists())

                    val afterRejection = loadIntentRows(databasePath)
                    val retryable = afterRejection.single { it.deliveryKey.startsWith("actionable:") }
                    assertEquals(clock.instant().plus(Duration.ofMinutes(1)), retryable.nextAttemptAt)
                    assertEquals(null, retryable.leaseOwner)
                    val firstArguments = Files.readAllLines(provider.firstArguments, UTF_8)
                    assertEquals(retryable.deliveryKey, deliveryKeyFrom(firstArguments))

                    val leaseAcquiredAt = clock.instant().plus(Duration.ofMinutes(1))
                    val leaseExpiresAt = clock.instant().plus(Duration.ofMinutes(2))
                    val exhaustedId = NotificationIntentId("ni_restart_exhaustion")
                    val exhaustedAttemptTimes = listOf(
                        Instant.parse("2026-08-14T05:15:00Z"),
                        Instant.parse("2026-08-14T05:16:00Z"),
                        Instant.parse("2026-08-14T05:21:00Z"),
                        Instant.parse("2026-08-14T05:36:00Z"),
                        Instant.parse("2026-08-14T06:36:00Z"),
                        Instant.parse("2026-08-14T12:36:00Z"),
                    )
                    JooqApplicationPersistence.open(databasePath).use { persistence ->
                        persistence.inTransaction {
                            val claimed = notificationIntentStore.tryClaim(
                                id = NotificationIntentId(retryable.id),
                                owner = "crashed-runtime",
                                acquiredAt = leaseAcquiredAt,
                                expiresAt = leaseExpiresAt,
                            )
                            assertEquals("crashed-runtime", claimed?.lease?.owner)
                            val exhausted = StoredNotificationIntent(
                                id = exhaustedId,
                                request = NotificationRequest(
                                    deliveryKey = NotificationDeliveryKey("exhaustion:restart-matrix"),
                                    title = "Bitbucket Helper",
                                    body = "Delivery retry budget verification",
                                    openUrl = URI("https://bitbucket.org/acme-engineering/release-tools"),
                                    sound = NotificationSound.DEFAULT,
                                ),
                                createdAt = exhaustedAttemptTimes.first(),
                                state = NotificationIntentState.PENDING,
                                attemptCount = 6,
                                nextAttemptAt = leaseExpiresAt,
                                lease = null,
                            )
                            assertTrue(
                                notificationIntentStore.insertIfAbsent(exhausted) is NotificationIntentInsertResult.Inserted,
                            )
                        }
                    }
                    seedFailedAttemptHistory(databasePath, exhaustedId.value, exhaustedAttemptTimes)

                    clock.advance(Duration.ofMinutes(2))
                    val immediateRetryClock = Clock.fixed(Instant.now().minusSeconds(59), ZoneOffset.UTC)
                    val secondRuntime = runtime(
                        databasePath = databasePath,
                        socketPath = secondSocket,
                        executable = provider.executable,
                        serviceClock = clock,
                        schedulerClock = immediateRetryClock,
                    )
                    try {
                        secondRuntime.start()
                        eventuallyWithin("restart retry accepted", Duration.ofSeconds(10)) {
                            loadIntentRows(databasePath)
                                .singleOrNull { it.id == retryable.id }
                                ?.takeIf { it.state == NotificationIntentState.ACCEPTED.name && it.attemptCount == 2 }
                        }
                        eventuallyWithin("seventh attempt exhausted", Duration.ofSeconds(10)) {
                            loadIntentRows(databasePath)
                                .singleOrNull { it.id == exhaustedId.value }
                                ?.takeIf { it.state == NotificationIntentState.EXHAUSTED.name && it.attemptCount == 7 }
                        }
                    } finally {
                        secondRuntime.close()
                    }
                    assertFalse(secondSocket.exists())

                    val accepted = loadIntentRows(databasePath).single { it.id == retryable.id }
                    val exhausted = loadIntentRows(databasePath).single { it.id == exhaustedId.value }
                    assertEquals(NotificationIntentState.ACCEPTED.name, accepted.state)
                    assertEquals(2, attemptCount(databasePath, accepted.id))
                    assertEquals(NotificationIntentState.EXHAUSTED.name, exhausted.state)
                    assertEquals(7, attemptCount(databasePath, exhausted.id))

                    val secondArguments = Files.readAllLines(provider.secondArguments, UTF_8)
                    assertEquals(firstArguments, secondArguments, "retry must preserve the complete provider payload")
                    assertEquals(retryable.deliveryKey, deliveryKeyFrom(secondArguments))
                    assertEquals(
                        "exhaustion:restart-matrix",
                        deliveryKeyFrom(Files.readAllLines(provider.exhaustedArguments, UTF_8)),
                    )

                    val databaseBytes = Files.list(directory).use { paths ->
                        paths.filter { it.fileName.toString().startsWith(databasePath.fileName.toString()) }
                            .map { Files.readString(it, ISO_8859_1) }
                            .toList()
                            .joinToString("\n")
                    }
                    val nonLiveDiagnostics = listOf(
                        databaseBytes,
                        loadIntentRows(databasePath).toString(),
                        firstArguments.toString(),
                        secondArguments.toString(),
                        Files.readAllLines(provider.exhaustedArguments, UTF_8).toString(),
                    ).joinToString("\n")
                    assertFalse(nonLiveDiagnostics.contains(RAW_CONTENT_MARKER))

                    provider.recordedPids().forEach { pid ->
                        eventuallyWithin("provider process $pid terminated", Duration.ofSeconds(5)) {
                            Unit.takeUnless { ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false) }
                        }
                    }
                    eventuallyWithin("Quartz threads terminated", Duration.ofSeconds(5)) {
                        Unit.takeIf { schedulerThreadIds().subtract(existingSchedulerThreads).isEmpty() }
                    }
                } finally {
                    bitbucket.close()
                }
            }
        }

        val failure = captured.result.exceptionOrNull()
        val diagnostics = listOf(
            captured.standardOut,
            captured.standardErr,
            failure?.stackTraceToString().orEmpty(),
        ).joinToString("\n")
        assertFalse(diagnostics.contains(RAW_CONTENT_MARKER))
        captured.result.getOrThrow()
    }

    private fun runtime(
        databasePath: Path,
        socketPath: Path,
        executable: Path,
        serviceClock: Clock,
        schedulerClock: Clock,
    ): ServiceRuntime = ServiceRuntime.create(
        configuration = ServiceConfiguration(
            httpHost = "127.0.0.1",
            httpPort = 0,
            databasePath = databasePath,
            unixSocketPath = socketPath,
            notificationExecutablePath = executable,
            bitbucketRequestTimeout = Duration.ofSeconds(2),
            credentials = BitbucketCredentials("person@example.com", "test-token"),
        ),
        clock = serviceClock,
        lifecycleProbe = ServiceRuntimeLifecycleProbe.NONE,
        schedulerClock = schedulerClock,
    )

    private suspend fun seedConfiguration(databasePath: Path, apiBaseUrl: URI) {
        JooqApplicationPersistence.open(databasePath).use { persistence ->
            persistence.inTransaction {
                val workspaceId = WorkspaceId("ws_22222222-2222-2222-2222-222222222222")
                configurationStore.save(
                    StoredInstallationConfiguration(
                        workspaceId = workspaceId,
                        bitbucketApiBaseUrl = apiBaseUrl,
                        workspaceSlug = "acme-engineering",
                        workspaceDisplayName = "Acme Engineering",
                        workspaceWebUrl = URI("https://bitbucket.org/acme-engineering"),
                        currentUserStableId = "11111111-1111-1111-1111-111111111111",
                        currentUserDisplayName = "Ada Lovelace",
                        configuredAt = Instant.parse("2026-08-15T12:34:00Z"),
                        retentionDays = 30,
                        repositories = listOf(
                            StoredConfiguredRepository(
                                id = RepositoryId("repo_33333333-3333-3333-3333-333333333333"),
                                workspaceId = workspaceId,
                                slug = "release-tools",
                                displayName = "Release Tools",
                                webUrl = URI("https://bitbucket.org/acme-engineering/release-tools"),
                                removedAt = null,
                            ),
                        ),
                    ),
                )
            }
        }
    }

    private fun loadIntentRows(databasePath: Path): List<IntentRow> =
        DriverManager.getConnection("jdbc:sqlite:${databasePath.toAbsolutePath().normalize()}").use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery(
                    "SELECT id, delivery_key, state, attempt_count, next_attempt_at, lease_owner FROM notification_intent ORDER BY delivery_key",
                ).use { result ->
                    buildList {
                        while (result.next()) {
                            add(
                                IntentRow(
                                    id = result.getString("id"),
                                    deliveryKey = result.getString("delivery_key"),
                                    state = result.getString("state"),
                                    attemptCount = result.getInt("attempt_count"),
                                    nextAttemptAt = decodeInstant(result.getString("next_attempt_at")),
                                    leaseOwner = result.getString("lease_owner"),
                                ),
                            )
                        }
                    }
                }
            }
        }

    private fun attemptCount(databasePath: Path, intentId: String): Int =
        DriverManager.getConnection("jdbc:sqlite:${databasePath.toAbsolutePath().normalize()}").use { connection ->
            connection.prepareStatement("SELECT COUNT(*) FROM notification_attempt WHERE intent_id=?").use { statement ->
                statement.setString(1, intentId)
                statement.executeQuery().use { result ->
                    check(result.next())
                    result.getInt(1)
                }
            }
        }

    private fun seedFailedAttemptHistory(
        databasePath: Path,
        intentId: String,
        completedAt: List<Instant>,
    ) {
        DriverManager.getConnection("jdbc:sqlite:${databasePath.toAbsolutePath().normalize()}").use { connection ->
            connection.prepareStatement(
                """
                    INSERT INTO notification_attempt(
                        id, intent_id, attempt_number, completed_at, result_kind, failure_category, ambiguous
                    )
                    VALUES (?, ?, ?, ?, 'FAILED', 'DELIVERY_FAILED', 0)
                """.trimIndent(),
            ).use { statement ->
                completedAt.forEachIndexed { index, instant ->
                    val attempt = index + 1
                    statement.setString(1, "na_restart_seed_$attempt")
                    statement.setString(2, intentId)
                    statement.setInt(3, attempt)
                    statement.setString(4, encodeInstant(instant))
                    assertEquals(1, statement.executeUpdate())
                }
            }
        }
    }

    private fun encodeInstant(instant: Instant): String {
        val secondsOffset = instant.epochSecond - Instant.MIN.epochSecond
        return "@" + secondsOffset.toString().padStart(17, '0') + instant.nano.toString().padStart(9, '0')
    }

    private fun decodeInstant(value: String?): Instant? = value?.let { encoded ->
        if (!encoded.startsWith("@")) {
            Instant.parse(encoded)
        } else {
            val secondsOffset = encoded.substring(1, 18).toLong()
            val nano = encoded.substring(18).toLong()
            Instant.ofEpochSecond(Instant.MIN.epochSecond + secondsOffset, nano)
        }
    }

    private fun deliveryKeyFrom(arguments: List<String>): String {
        val keyIndex = arguments.indexOf("--delivery-key")
        assertTrue(keyIndex >= 0 && keyIndex + 1 < arguments.size)
        return arguments[keyIndex + 1]
    }

    private suspend fun <T : Any> eventuallyWithin(
        stage: String,
        timeout: Duration,
        diagnostics: () -> String = { "" },
        condition: () -> T?,
    ): T {
        val deadline = System.nanoTime() + timeout.toNanos()
        while (true) {
            try {
                condition()?.let { return it }
            } catch (_: SQLException) {
                // A concurrent SQLite writer may hold the database briefly; retry until the monotonic deadline.
            }
            if (System.nanoTime() >= deadline) {
                throw AssertionError(
                    "Condition was not satisfied before the monotonic deadline: $stage ${diagnostics()}",
                )
            }
            delay(10)
        }
    }

    private fun schedulerThreadIds(): Set<Long> = Thread.getAllStackTraces().keys
        .filter { it.isAlive && it.name.contains("bitbucket-helper-application-") }
        .mapTo(mutableSetOf(), Thread::threadId)

    private fun <T> captureDiagnostics(block: () -> T): CapturedExecution<T> {
        val standardOut = ByteArrayOutputStream()
        val standardErr = ByteArrayOutputStream()
        val originalOut = System.out
        val originalErr = System.err
        val replacementOut = PrintStream(standardOut, true, UTF_8)
        val replacementErr = PrintStream(standardErr, true, UTF_8)
        System.setOut(replacementOut)
        System.setErr(replacementErr)
        return try {
            val result = runCatching(block)
            replacementOut.flush()
            replacementErr.flush()
            CapturedExecution(result, standardOut.toString(UTF_8), standardErr.toString(UTF_8))
        } finally {
            System.setOut(originalOut)
            System.setErr(originalErr)
            replacementOut.close()
            replacementErr.close()
        }
    }

    private data class IntentRow(
        val id: String,
        val deliveryKey: String,
        val state: String,
        val attemptCount: Int,
        val nextAttemptAt: Instant?,
        val leaseOwner: String?,
    )

    private data class CapturedExecution<T>(
        val result: Result<T>,
        val standardOut: String,
        val standardErr: String,
    )

    private class MutableClock(private var current: Instant) : Clock() {
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId): Clock = checkNotNull(takeIf { zone == ZoneOffset.UTC })
        override fun instant(): Instant = current
        fun advance(duration: Duration) {
            current = current.plus(duration)
        }
    }

    private data class ProviderFixture(
        val executable: Path,
        val firstRejectedAttemptStarted: Path,
        val releaseFirstRejectedAttempt: Path,
        val firstArguments: Path,
        val secondArguments: Path,
        val exhaustedArguments: Path,
        val pids: Path,
    ) {
        fun recordedPids(): List<Long> = Files.readAllLines(pids, UTF_8).map(String::toLong)

        companion object {
            fun create(directory: Path): ProviderFixture {
                val firstStarted = directory.resolve("provider-first-started")
                val releaseFirst = directory.resolve("provider-first-release")
                val firstArguments = directory.resolve("provider-first-arguments")
                val secondArguments = directory.resolve("provider-second-arguments")
                val exhaustedArguments = directory.resolve("provider-exhausted-arguments")
                val rejected = directory.resolve("provider-rejected-once")
                val pids = directory.resolve("provider-pids")
                val executable = FakeDesktopNotificationsExecutable.create(
                    directory,
                    """
                        delivery_key=''
                        previous=''
                        for argument in "${'$'}@"; do
                          if [ "${'$'}previous" = '--delivery-key' ]; then delivery_key="${'$'}argument"; fi
                          previous="${'$'}argument"
                        done
                        printf '%s\n' "${'$'}${'$'}" >> '${pids.toAbsolutePath()}'
                        case "${'$'}delivery_key" in
                          actionable:*)
                            if [ ! -f '${rejected.toAbsolutePath()}' ]; then
                              printf '%s\n' "${'$'}@" > '${firstArguments.toAbsolutePath()}'
                              : > '${firstStarted.toAbsolutePath()}'
                              while [ ! -f '${releaseFirst.toAbsolutePath()}' ]; do sleep 0.01; done
                              : > '${rejected.toAbsolutePath()}'
                              printf '%s\n' '{"status":"failed","error":{"code":"dependency_unavailable","message":"safe"}}'
                              exit 1
                            fi
                            printf '%s\n' "${'$'}@" > '${secondArguments.toAbsolutePath()}'
                            printf '%s\n' '{"status":"accepted"}'
                            ;;
                          exhaustion:*)
                            printf '%s\n' "${'$'}@" > '${exhaustedArguments.toAbsolutePath()}'
                            printf '%s\n' '{"status":"failed","error":{"code":"delivery_failed","message":"safe"}}'
                            exit 1
                            ;;
                          *)
                            printf '%s\n' '{"status":"accepted"}'
                            ;;
                        esac
                    """.trimIndent(),
                )
                return ProviderFixture(
                    executable,
                    firstStarted,
                    releaseFirst,
                    firstArguments,
                    secondArguments,
                    exhaustedArguments,
                    pids,
                )
            }
        }
    }

    private class FakeBitbucketServer private constructor(
        private val server: HttpServer,
        private val rawActivityBody: String,
    ) : AutoCloseable {
        private val requests = ConcurrentLinkedQueue<String>()
        val baseUrl: URI = URI("http://127.0.0.1:${server.address.port}/2.0")
        fun requestPaths(): List<String> = requests.toList()

        override fun close() = server.stop(0)

        private fun respond(exchange: HttpExchange) {
            val path = exchange.requestURI.path
            requests += path
            val body = when {
                path.endsWith("/pullrequests") -> """{"values":[${pullRequest()}]}"""
                path.endsWith("/pullrequests/42") -> pullRequest()
                path.endsWith("/effective-default-reviewers") -> """{"values":[]}"""
                path.endsWith("/pullrequests/42/statuses") -> """{"values":[]}"""
                path.endsWith("/pullrequests/42/tasks") -> """{"values":[]}"""
                path.endsWith("/pullrequests/42/activity") -> activity(rawActivityBody)
                else -> null
            }
            try {
                if (body == null) exchange.respond(404, "{}") else exchange.respond(200, body)
            } finally {
                exchange.close()
            }
        }

        companion object {
            fun withRawActivity(rawActivityBody: String): FakeBitbucketServer {
                val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
                val fixture = FakeBitbucketServer(server, rawActivityBody)
                server.createContext("/", fixture::respond)
                server.start()
                return fixture
            }

            private fun pullRequest(): String = """
                {
                  "type":"pullrequest","id":42,"title":"Restart integration","state":"OPEN",
                  "author":{"type":"user","uuid":"{11111111-1111-1111-1111-111111111111}","display_name":"Ada Lovelace"},
                  "source":{"commit":{"hash":"abc123def456"}},
                  "destination":{"branch":{"name":"main"},"commit":{"hash":"fedcba654321"}},
                  "links":{"html":{"href":"https://bitbucket.org/acme-engineering/release-tools/pull-requests/42"}},
                  "created_on":"2026-08-01T10:15:30Z","updated_on":"2026-08-15T12:30:45Z",
                  "draft":false,"comment_count":1,"unresolved_comment_count":1,"participants":[]
                }
            """.trimIndent()

            private fun activity(rawActivityBody: String): String = """
                {"values":[{"comment":{
                  "type":"pullrequest_comment","id":501,
                  "created_on":"2026-08-15T10:00:00Z","updated_on":"2026-08-15T12:33:00Z",
                  "content":{"raw":"$rawActivityBody"},
                  "user":{"type":"user","uuid":"{44444444-4444-4444-4444-444444444444}","display_name":"Grace Hopper"},
                  "deleted":false,
                  "links":{"html":{"href":"https://bitbucket.org/acme-engineering/release-tools/pull-requests/42#comment-501"}}
                }}]}
            """.trimIndent()
        }
    }

    private companion object {
        const val RAW_CONTENT_MARKER = "RAW-LIVE-CONTENT-MUST-NOT-ESCAPE-8f4d"
    }
}

private fun HttpExchange.respond(status: Int, body: String) {
    val bytes = body.toByteArray(UTF_8)
    responseHeaders.set("Content-Type", "application/json")
    sendResponseHeaders(status, bytes.size.toLong())
    responseBody.use { it.write(bytes) }
}

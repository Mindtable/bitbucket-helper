package com.mindtable.bitbuckethelper

import com.mindtable.bitbuckethelper.bootstrap.BitbucketCredentials
import com.mindtable.bitbuckethelper.bootstrap.ServiceConfiguration
import com.mindtable.bitbuckethelper.bootstrap.ServiceRuntime
import com.mindtable.bitbuckethelper.support.FakeDesktopNotificationsExecutable
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import com.sun.net.httpserver.HttpsConfigurator
import com.sun.net.httpserver.HttpsServer
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.header
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.request.unixSocket
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.contentType
import java.net.InetSocketAddress
import java.net.URI
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.security.KeyStore
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.api.parallel.ResourceLock
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource

@ResourceLock("global-tls-test-state")
class V1ApplicationEndToEndTest {
    @TempDir
    lateinit var temporaryRoot: Path

    @ParameterizedTest(name = "complete v1 journey over {0}")
    @EnumSource(V1Transport::class)
    @Timeout(value = 45, unit = TimeUnit.SECONDS)
    fun `complete v1 journey is transport-parity typed and preserves last-known-good state`(
        transport: V1Transport,
    ) = runBlocking {
        val directory = Files.createDirectory(temporaryRoot.resolve(transport.name.lowercase()))
        val previousSslContext = SSLContext.getDefault()
        val previousSslProperties = SSL_PROPERTY_NAMES.associateWith(System::getProperty)
        V1TestRig.create(directory).use { rig ->
            val initial = rig.parityGet("/api/v1/dashboard")
            assertParityTyped(initial, "workspaceNotConfigured")

            val configured = rig.request(
                transport,
                HttpMethod.Put,
                WORKSPACE_PATH,
                """{"apiVersion":"1","bitbucketApiBaseUrl":"${rig.bitbucket.apiBaseUrl}","workspaceSlug":"acme-engineering"}""",
            )
            assertEquals(
                "workspaceConfigured",
                configured.result().string("type"),
                "${configured.body}; fake requests=${rig.bitbucket.requestPaths}",
            )
            assertTyped(configured, "workspaceConfigured")

            val repeatedConfiguration = rig.request(
                transport,
                HttpMethod.Put,
                WORKSPACE_PATH,
                """{"apiVersion":"1","bitbucketApiBaseUrl":"${rig.bitbucket.apiBaseUrl}","workspaceSlug":"acme-engineering"}""",
            )
            assertTyped(repeatedConfiguration, "workspaceAlreadyConfigured")
            assertEquals(
                configured.result().objectValue("configuration").string("workspaceId"),
                repeatedConfiguration.result().objectValue("configuration").string("workspaceId"),
            )

            val identityMismatch = rig.request(
                transport,
                HttpMethod.Put,
                WORKSPACE_PATH,
                """{"apiVersion":"1","bitbucketApiBaseUrl":"${rig.bitbucket.apiBaseUrl}","workspaceSlug":"other-engineering"}""",
            )
            assertTyped(identityMismatch, "workspaceIdentityMismatch")
            assertEquals(
                configured.result().objectValue("configuration").string("workspaceId"),
                identityMismatch.result().objectValue("current").string("workspaceId"),
            )

            val noRepositories = rig.request(
                transport,
                HttpMethod.Post,
                REFRESH_PATH,
                ALL_REPOSITORIES_BODY,
            )
            assertTyped(noRepositories, "noRepositoriesConfigured")

            assertTyped(
                rig.request(transport, HttpMethod.Post, REPOSITORIES_PATH, addRepositoryBody("release-tools")),
                "repositoryAdded",
            )
            assertTyped(
                rig.request(transport, HttpMethod.Post, REPOSITORIES_PATH, addRepositoryBody("web-store")),
                "repositoryAdded",
            )
            assertTyped(
                rig.request(transport, HttpMethod.Post, REPOSITORIES_PATH, addRepositoryBody("release-tools")),
                "repositoryAlreadyConfigured",
            )

            val configuredParity = rig.parityGet(WORKSPACE_PATH)
            assertParityTyped(configuredParity, "workspaceConfigured")
            assertEquals(configuredParity.browser.result(), configuredParity.unix.result())
            assertEquals(2, configuredParity.browser.result().objectValue("configuration").array("repositories").size)

            val firstRun = rig.request(transport, HttpMethod.Post, REFRESH_PATH, ALL_REPOSITORIES_BODY)
            assertTyped(firstRun, "refreshRunRegistered")
            val firstRunId = firstRun.result().objectValue("refreshRun").string("refreshRunId")
            val firstCompletion = rig.awaitRefresh(firstRunId, transport)
            assertTyped(firstCompletion, "refreshRunCompleted")
            assertEquals(
                listOf("succeeded", "succeeded"),
                firstCompletion.result().objectValue("refreshRun").array("repositories")
                    .map { it.jsonObject.string("type") }.sorted(),
            )
            val firstCompletionOverOtherTransport = rig.request(
                transport.other(),
                HttpMethod.Get,
                "$REFRESH_PATH/$firstRunId",
            )
            assertTyped(firstCompletionOverOtherTransport, "refreshRunCompleted")
            assertEquals(firstCompletion.result(), firstCompletionOverOtherTransport.result())

            val dashboard = rig.parityGet("/api/v1/dashboard")
            assertParityTyped(dashboard, "snapshotChanged")
            assertEquals(dashboard.browser.result(), dashboard.unix.result())
            val snapshot = dashboard.browser.result().objectValue("snapshot")
            val groups = snapshot.array("repositoryGroups").map { it.jsonObject }
            assertEquals(setOf(RELEASE_REPOSITORY_ID, WEB_REPOSITORY_ID), groups.map { it.string("repositoryId") }.toSet())
            assertTrue(groups.all { it.array("pullRequests").size == 1 })

            val releaseCard = groups.single { it.string("repositoryId") == RELEASE_REPOSITORY_ID }
                .array("pullRequests").single().jsonObject
            val pullRequestId = releaseCard.string("pullRequestId")
            val action = releaseCard.array("actionItems").first().jsonObject
            val actionItemId = action.string("actionItemId")
            val activityVersion = action.string("activityVersion")

            val pullRequests = rig.parityGet("/api/v1/pull-requests")
            assertParityTyped(pullRequests, "available")
            assertEquals(pullRequests.browser.result(), pullRequests.unix.result())
            val detail = rig.parityGet("/api/v1/pull-requests/$pullRequestId")
            assertParityTyped(detail, "pullRequestFound")
            assertEquals(detail.browser.result(), detail.unix.result())
            val inbox = rig.parityGet("/api/v1/inbox")
            assertParityTyped(inbox, "available")
            assertEquals(inbox.browser.result(), inbox.unix.result())
            assertTrue(inbox.browser.result().objectValue("inbox").array("items").isNotEmpty())
            val synchronization = rig.parityGet("/api/v1/synchronization")
            assertParityTyped(synchronization, "available")
            assertEquals(synchronization.browser.result(), synchronization.unix.result())

            listOf(dashboard.browser, pullRequests.browser, detail.browser, inbox.browser, synchronization.browser)
                .forEach { bulk ->
                    assertFalse(bulk.body.contains(V1TestRig.RAW_ACTIVITY_MARKER), bulk.body)
                    assertFalse(bulk.body.contains(V1TestRig.LIVE_MARKDOWN), bulk.body)
                }

            val contentPath = "/api/v1/action-items/$actionItemId/content?activityVersion=$activityVersion"
            val available = rig.parityGet(contentPath)
            assertParityTyped(available, "contentAvailable")
            assertEquals(V1TestRig.LIVE_MARKDOWN, available.browser.result().string("markdown"))
            assertEquals(
                available.browser.result().filterKeys { it != "fetchedAt" },
                available.unix.result().filterKeys { it != "fetchedAt" },
            )
            assertTrue(runCatching { Instant.parse(available.browser.result().string("fetchedAt")) }.isSuccess)
            assertTrue(runCatching { Instant.parse(available.unix.result().string("fetchedAt")) }.isSuccess)

            rig.bitbucket.commentMode = V1FakeBitbucket.CommentMode.ADVANCED
            val newer = rig.parityGet(contentPath)
            assertParityTyped(newer, "newerActivityObserved")
            assertEquals(newer.browser.result(), newer.unix.result())
            assertFalse(newer.browser.body.contains(V1TestRig.LIVE_MARKDOWN))

            rig.bitbucket.commentMode = V1FakeBitbucket.CommentMode.FAILURE
            val unavailable = rig.parityGet(contentPath)
            assertParityTyped(unavailable, "contentUnavailable")
            assertEquals(unavailable.browser.result(), unavailable.unix.result())
            assertFalse(unavailable.browser.body.contains(V1TestRig.LIVE_MARKDOWN))
            rig.bitbucket.commentMode = V1FakeBitbucket.CommentMode.CURRENT

            val staleAcknowledgment = rig.request(
                transport,
                HttpMethod.Put,
                "/api/v1/action-items/$actionItemId/acknowledgment",
                """{"apiVersion":"1","activityVersion":"av_stale-acceptance"}""",
            )
            assertTyped(staleAcknowledgment, "staleActivityVersion")
            val acknowledged = rig.request(
                transport,
                HttpMethod.Put,
                "/api/v1/action-items/$actionItemId/acknowledgment",
                """{"apiVersion":"1","activityVersion":"$activityVersion"}""",
            )
            assertTyped(acknowledged, "acknowledged")
            val idempotent = rig.request(
                transport,
                HttpMethod.Put,
                "/api/v1/action-items/$actionItemId/acknowledgment",
                """{"apiVersion":"1","activityVersion":"$activityVersion"}""",
            )
            assertTyped(idempotent, "alreadyAcknowledged")

            assertTyped(
                rig.request(transport, HttpMethod.Delete, "$REPOSITORIES_PATH/$RELEASE_REPOSITORY_ID"),
                "repositoryRemoved",
            )
            assertTyped(
                rig.request(transport, HttpMethod.Post, REPOSITORIES_PATH, addRepositoryBody("release-tools")),
                "repositoryAdded",
            )

            rig.bitbucket.failedRepositorySlug = "web-store"
            rig.bitbucket.blockRepository("web-store")
            val mixedRun = rig.request(transport, HttpMethod.Post, REFRESH_PATH, ALL_REPOSITORIES_BODY)
            assertTyped(mixedRun, "refreshRunRegistered")
            val mixedRunId = mixedRun.result().objectValue("refreshRun").string("refreshRunId")
            assertTrue(rig.bitbucket.blockedRepositoryRequestStarted.await(5, TimeUnit.SECONDS))
            try {
                val independentlyCompleted = rig.awaitRepositoryResult(
                    mixedRunId,
                    transport,
                    RELEASE_REPOSITORY_ID,
                    "succeeded",
                )
                assertTyped(independentlyCompleted, "refreshRunInProgress")
            } finally {
                rig.bitbucket.releaseBlockedRepository()
            }
            val mixedCompletion = rig.awaitRefresh(mixedRunId, transport)
            assertTyped(mixedCompletion, "refreshRunCompleted")
            assertEquals(
                setOf("succeeded", "failed"),
                mixedCompletion.result().objectValue("refreshRun").array("repositories")
                    .map { it.jsonObject.string("type") }.toSet(),
            )

            val degradedDashboard = rig.parityGet("/api/v1/dashboard")
            assertParityTyped(degradedDashboard, "snapshotChanged")
            assertEquals(degradedDashboard.browser.result(), degradedDashboard.unix.result())
            val degradedGroups = degradedDashboard.browser.result().objectValue("snapshot")
                .array("repositoryGroups").map { it.jsonObject }
            val preservedWebStore = degradedGroups.single { it.string("repositoryId") == WEB_REPOSITORY_ID }
            assertEquals(1, preservedWebStore.array("pullRequests").size)
            assertEquals("present", preservedWebStore.objectValue("synchronization").objectValue("problem").string("type"))
            assertTrue(rig.bitbucket.authorizationHeaders.all { it == rig.expectedAuthorization })
        }
        assertSame(previousSslContext, SSLContext.getDefault())
        assertEquals(previousSslProperties, SSL_PROPERTY_NAMES.associateWith(System::getProperty))
    }

    private fun assertTyped(response: V1HttpResponse, expectedType: String) {
        assertEquals(200, response.status, response.body)
        assertEquals("1", response.root().string("apiVersion"))
        assertTrue(response.root().string("requestId").matches(Regex("^req_[A-Za-z0-9_-]+$")))
        assertEquals(expectedType, response.result().string("type"), response.body)
    }

    private fun assertParityTyped(pair: V1TransportPair, expectedType: String) {
        assertTyped(pair.browser, expectedType)
        assertTyped(pair.unix, expectedType)
    }

    private fun addRepositoryBody(slug: String) =
        """{"apiVersion":"1","repositorySlug":"$slug"}"""

    private companion object {
        const val WORKSPACE_PATH = "/api/v1/configuration/workspace"
        const val REPOSITORIES_PATH = "$WORKSPACE_PATH/repositories"
        const val REFRESH_PATH = "/api/v1/refresh-runs"
        const val ALL_REPOSITORIES_BODY =
            """{"apiVersion":"1","target":{"type":"allConfiguredRepositories"}}"""
        const val RELEASE_REPOSITORY_ID = "repo_33333333-3333-3333-3333-333333333333"
        const val WEB_REPOSITORY_ID = "repo_66666666-6666-6666-6666-666666666666"
        val SSL_PROPERTY_NAMES = listOf(
            "javax.net.ssl.trustStore",
            "javax.net.ssl.trustStorePassword",
            "javax.net.ssl.trustStoreType",
        )
    }
}

enum class V1Transport {
    BROWSER,
    UNIX;

    fun other(): V1Transport = if (this == BROWSER) UNIX else BROWSER
}

internal data class V1TransportPair(val browser: V1HttpResponse, val unix: V1HttpResponse)

internal data class V1HttpResponse(
    val status: Int,
    val body: String,
    val headers: Map<String, List<String>>,
) {
    fun root(): JsonObject = Json.parseToJsonElement(body).jsonObject
    fun result(): JsonObject = root().objectValue("result")
}

internal class V1TestRig private constructor(
    val directory: Path,
    val databasePath: Path,
    val socketPath: Path,
    val notificationArgumentsPath: Path,
    val bitbucket: V1FakeBitbucket,
    private val runtime: ServiceRuntime,
    private val client: HttpClient,
    private val browserPort: Int,
    val username: String,
    val token: String,
) : AutoCloseable {
    val browserBase: String = "http://127.0.0.1:$browserPort"
    val expectedAuthorization: String = "Basic " + Base64.getEncoder()
        .encodeToString("$username:$token".toByteArray(UTF_8))
    private var csrfToken: String? = null

    suspend fun parityGet(path: String): V1TransportPair =
        V1TransportPair(browser(HttpMethod.Get, path), unix(HttpMethod.Get, path))

    suspend fun request(
        transport: V1Transport,
        method: HttpMethod,
        path: String,
        body: String? = null,
    ): V1HttpResponse = when (transport) {
        V1Transport.BROWSER -> browser(method, path, body)
        V1Transport.UNIX -> unix(method, path, body)
    }

    suspend fun awaitRefresh(runId: String, transport: V1Transport): V1HttpResponse {
        val deadline = System.nanoTime() + Duration.ofSeconds(12).toNanos()
        while (true) {
            val response = when (transport) {
                V1Transport.BROWSER -> browser(HttpMethod.Get, "/api/v1/refresh-runs/$runId")
                V1Transport.UNIX -> unix(HttpMethod.Get, "/api/v1/refresh-runs/$runId")
            }
            if (response.status == 200 && response.result().string("type") == "refreshRunCompleted") return response
            if (System.nanoTime() >= deadline) {
                throw AssertionError("refresh $runId did not complete; last response=${response.body}")
            }
            delay(25)
        }
    }

    suspend fun awaitRepositoryResult(
        runId: String,
        transport: V1Transport,
        repositoryId: String,
        expectedType: String,
    ): V1HttpResponse {
        val deadline = System.nanoTime() + Duration.ofSeconds(8).toNanos()
        while (true) {
            val response = request(transport, HttpMethod.Get, "/api/v1/refresh-runs/$runId")
            if (response.status == 200 && response.root()["result"] != null) {
                val run = response.result()["refreshRun"]?.jsonObject
                val observed = run?.array("repositories")?.any { repository ->
                    val value = repository.jsonObject
                    value.string("repositoryId") == repositoryId && value.string("type") == expectedType
                } == true
                if (observed) return response
            }
            if (System.nanoTime() >= deadline) {
                throw AssertionError("repository $repositoryId did not reach $expectedType; last response=${response.body}")
            }
            delay(25)
        }
    }

    suspend fun browser(
        method: HttpMethod,
        path: String,
        body: String? = null,
        headers: Map<String, String> = emptyMap(),
        authorizeMutation: Boolean = true,
        bodyContentType: ContentType? = ContentType.Application.Json,
    ): V1HttpResponse {
        if (method != HttpMethod.Get && authorizeMutation && csrfToken == null) loadCsrf()
        return execute(
            logicalBase = browserBase,
            socket = null,
            method = method,
            path = path,
            body = body,
            headers = buildMap {
                putAll(headers)
                if (method != HttpMethod.Get && authorizeMutation) {
                    put(HttpHeaders.Origin, browserBase)
                    put("X-CSRF-Token", requireNotNull(csrfToken))
                    put(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                }
            },
            bodyContentType = bodyContentType,
        )
    }

    suspend fun browserMutation(method: HttpMethod, path: String, body: String): V1HttpResponse =
        browser(method, path, body)

    suspend fun unix(
        method: HttpMethod,
        path: String,
        body: String? = null,
        headers: Map<String, String> = emptyMap(),
    ): V1HttpResponse = execute("http://untrusted.invalid", socketPath, method, path, body, headers)

    private suspend fun loadCsrf() {
        val session = browser(HttpMethod.Get, "/api/v1/browser-session")
        check(session.status == 200) { session.body }
        csrfToken = session.result().string("csrfToken")
    }

    private suspend fun execute(
        logicalBase: String,
        socket: Path?,
        method: HttpMethod,
        path: String,
        body: String?,
        headers: Map<String, String>,
        bodyContentType: ContentType? = ContentType.Application.Json,
    ): V1HttpResponse {
        val response = client.request("$logicalBase$path") {
            this.method = method
            socket?.let { unixSocket(it.toString()) }
            headers.forEach { (name, value) -> header(name, value) }
            if (body != null) {
                bodyContentType?.let { contentType(it) }
                setBody(body)
            }
        }
        return V1HttpResponse(
            status = response.status.value,
            body = response.bodyAsText(),
            headers = response.headers.names().associateWith { response.headers.getAll(it).orEmpty() },
        )
    }

    override fun close() {
        var failure: Throwable? = null
        runCatching { client.close() }.exceptionOrNull()?.let { failure = it }
        runCatching { runtime.close() }.exceptionOrNull()?.let {
            failure = failure?.apply { addSuppressed(it) } ?: it
        }
        runCatching { bitbucket.close() }.exceptionOrNull()?.let {
            failure = failure?.apply { addSuppressed(it) } ?: it
        }
        failure?.let { throw it }
    }

    companion object {
        const val RAW_ACTIVITY_MARKER = "raw-activity-private-sentinel-32"
        const val LIVE_MARKDOWN = "**live-private-markdown-sentinel-91**"

        suspend fun create(directory: Path): V1TestRig {
            Files.setPosixFilePermissions(directory, PosixFilePermissions.fromString("rwx------"))
            val notificationArguments = directory.resolve("notification-argv.txt")
            val executable = FakeDesktopNotificationsExecutable.create(
                directory,
                """
                    printf '%s\n' "${'$'}@" >> '${notificationArguments.toAbsolutePath()}'
                    printf '%s\n' '{"status":"accepted"}'
                """.trimIndent(),
            )
            val database = directory.resolve("v1-acceptance.sqlite")
            val socket = directory.resolve("v1-acceptance.sock")
            val username = "v1-acceptance@example.test"
            val token = "v1-secret-token-sentinel"
            val bitbucket = V1FakeBitbucket.start(directory)
            var runtime: ServiceRuntime? = null
            var client: HttpClient? = null
            try {
                val createdRuntime = ServiceRuntime.create(
                    ServiceConfiguration(
                        httpHost = "127.0.0.1",
                        httpPort = 0,
                        databasePath = database,
                        unixSocketPath = socket,
                        notificationExecutablePath = executable,
                        bitbucketRequestTimeout = Duration.ofSeconds(2),
                        credentials = BitbucketCredentials(username, token),
                    ),
                    Clock.fixed(Instant.parse("2026-08-15T12:40:00Z"), ZoneOffset.UTC),
                )
                runtime = createdRuntime
                createdRuntime.start()
                val createdClient = HttpClient(CIO)
                client = createdClient
                return V1TestRig(
                    directory,
                    database,
                    socket,
                    notificationArguments,
                    bitbucket,
                    createdRuntime,
                    createdClient,
                    createdRuntime.resolvedHttpPort(),
                    username,
                    token,
                )
            } catch (failure: Throwable) {
                runCatching { client?.close() }.exceptionOrNull()?.let(failure::addSuppressed)
                runCatching { runtime?.close() }.exceptionOrNull()?.let(failure::addSuppressed)
                runCatching { bitbucket.close() }.exceptionOrNull()?.let(failure::addSuppressed)
                throw failure
            }
        }
    }
}

internal class V1FakeBitbucket private constructor(
    private val server: HttpServer,
    private val executor: java.util.concurrent.ExecutorService,
    private val previousDefaultSslContext: SSLContext,
    private val previousSslProperties: Map<String, String?>,
) : AutoCloseable {
    enum class CommentMode { CURRENT, ADVANCED, FAILURE }

    val apiBaseUrl: URI = URI("https://127.0.0.1:${server.address.port}/2.0")
    val authorizationHeaders = CopyOnWriteArrayList<String?>()
    val requestPaths = CopyOnWriteArrayList<String>()
    val blockedRepositoryRequestStarted = CountDownLatch(1)
    private val blockedRepositoryRelease = CountDownLatch(1)
    @Volatile var commentMode: CommentMode = CommentMode.CURRENT
    @Volatile var failedRepositorySlug: String? = null
    @Volatile private var blockedRepositorySlug: String? = null

    fun blockRepository(slug: String) {
        blockedRepositorySlug = slug
    }

    fun releaseBlockedRepository() {
        blockedRepositoryRelease.countDown()
    }

    override fun close() {
        var failure: Throwable? = null
        failure = recordFailure(failure) { blockedRepositoryRelease.countDown() }
        failure = recordFailure(failure) { server.stop(0) }
        failure = recordFailure(failure) { executor.shutdownNow() }
        failure = recordFailure(failure) {
            restoreTlsState(previousDefaultSslContext, previousSslProperties)
        }
        failure?.let { throw it }
    }

    private fun handle(exchange: HttpExchange) {
        authorizationHeaders += exchange.requestHeaders.getFirst(HttpHeaders.Authorization)
        val path = exchange.requestURI.path
        requestPaths += path
        when {
            path == "/2.0/user" -> exchange.respondV1(200, currentUser())
            path == "/2.0/workspaces/acme-engineering" -> exchange.respondV1(200, workspace())
            path == "/2.0/workspaces/other-engineering" -> exchange.respondV1(200, otherWorkspace())
            path == "/2.0/repositories/acme-engineering/release-tools" ->
                exchange.respondV1(200, repository("release-tools"))
            path == "/2.0/repositories/acme-engineering/web-store" ->
                exchange.respondV1(200, repository("web-store"))
            path.endsWith("/pullrequests") -> {
                val slug = repositorySlug(path)
                if (blockedRepositorySlug == slug) {
                    blockedRepositoryRequestStarted.countDown()
                    blockedRepositoryRelease.await(8, TimeUnit.SECONDS)
                }
                if (failedRepositorySlug == slug) exchange.respondV1(503, "{\"detail\":\"private-upstream-detail\"}")
                else exchange.respondV1(200, pullRequests(slug))
            }
            path.endsWith("/pullrequests/42") -> exchange.respondV1(200, pullRequestDetail(repositorySlug(path)))
            path.endsWith("/effective-default-reviewers") -> exchange.respondV1(200, defaultReviewers())
            path.endsWith("/statuses") -> exchange.respondV1(200, statuses())
            path.endsWith("/tasks") -> exchange.respondV1(200, "{\"values\":[]}")
            path.endsWith("/activity") -> exchange.respondV1(200, activity(repositorySlug(path)))
            path.endsWith("/comments/501") -> when (commentMode) {
                CommentMode.CURRENT -> exchange.respondV1(200, comment("2026-08-15T12:35:00Z"))
                CommentMode.ADVANCED -> exchange.respondV1(200, comment("2026-08-15T12:39:00Z"))
                CommentMode.FAILURE -> exchange.respondV1(503, "{\"detail\":\"${V1TestRig.LIVE_MARKDOWN}\"}")
            }
            else -> exchange.respondV1(404, "{}")
        }
    }

    private fun repositorySlug(path: String): String =
        path.substringAfter("/repositories/acme-engineering/").substringBefore('/')

    private fun repositoryId(slug: String): String = when (slug) {
        "release-tools" -> "{33333333-3333-3333-3333-333333333333}"
        "web-store" -> "{66666666-6666-6666-6666-666666666666}"
        else -> error("unexpected repository slug $slug")
    }

    private fun repositoryName(slug: String): String = when (slug) {
        "release-tools" -> "Release Tools"
        "web-store" -> "Web Store"
        else -> error("unexpected repository slug $slug")
    }

    private fun currentUser(): String = fixture("current-user.json")

    private fun workspace(): String = fixture("workspace.json")

    private fun otherWorkspace(): String = fixture("workspace.json")
        .replace("{22222222-2222-2222-2222-222222222222}", "{77777777-7777-7777-7777-777777777777}")
        .replace("Acme Engineering", "Other Engineering")
        .replace("acme-engineering", "other-engineering")

    private fun repository(slug: String): String {
        val release = fixture("repository.json")
        return if (slug == "release-tools") {
            release
        } else {
            release
                .replace("{33333333-3333-3333-3333-333333333333}", repositoryId(slug))
                .replace("release-tools", slug)
                .replace("Release Tools", repositoryName(slug))
                .replace("Release automation tools", "Web storefront")
        }
    }

    private fun pullRequests(slug: String) =
        """{"values":[{"type":"pullrequest","id":42,"title":"${repositoryName(slug)} delivery","state":"OPEN","author":{"type":"user","uuid":"{11111111-1111-1111-1111-111111111111}","display_name":"Ada Lovelace"},"source":{"commit":{"hash":"def456-$slug"}},"links":{"html":{"href":"https://bitbucket.org/acme-engineering/$slug/pull-requests/42"}},"draft":false,"created_on":"2026-08-02T11:20:40Z","updated_on":"2026-08-15T12:30:45Z"}]}"""

    private fun pullRequestDetail(slug: String) =
        """{"type":"pullrequest","id":42,"title":"${repositoryName(slug)} delivery","state":"OPEN","author":{"type":"user","uuid":"{11111111-1111-1111-1111-111111111111}","display_name":"Ada Lovelace"},"source":{"commit":{"hash":"def456-$slug"}},"destination":{"branch":{"name":"main"},"commit":{"hash":"fedcba654321"}},"links":{"html":{"href":"https://bitbucket.org/acme-engineering/$slug/pull-requests/42"}},"created_on":"2026-08-02T11:20:40Z","updated_on":"2026-08-15T12:30:45Z","draft":false,"comment_count":1,"unresolved_comment_count":1,"participants":[]}"""

    private fun defaultReviewers() =
        """{"values":[{"type":"default_reviewer","reviewer_type":"repository","user":{"type":"user","uuid":"{44444444-4444-4444-4444-444444444444}","display_name":"Grace Hopper","nickname":"grace"}}]}"""

    private fun statuses() =
        """{"values":[{"type":"build","key":"unit","state":"SUCCESSFUL","updated_on":"2026-08-15T12:31:00Z"}]}"""

    private fun activity(slug: String) =
        """{"values":[{"comment":{"type":"pullrequest_comment","id":501,"created_on":"2026-08-15T10:00:00Z","updated_on":"2026-08-15T12:35:00Z","content":{"raw":"${V1TestRig.RAW_ACTIVITY_MARKER}-$slug"},"user":{"type":"user","uuid":"{44444444-4444-4444-4444-444444444444}","display_name":"Grace Hopper"},"deleted":false,"links":{"html":{"href":"https://bitbucket.org/acme-engineering/$slug/pull-requests/42#comment-501"}}}}]}"""

    private fun comment(updatedOn: String) =
        """{"type":"pullrequest_comment","id":501,"created_on":"2026-08-15T10:00:00Z","updated_on":"$updatedOn","content":{"raw":"${V1TestRig.LIVE_MARKDOWN}"},"user":{"type":"user","uuid":"{44444444-4444-4444-4444-444444444444}","display_name":"Grace Hopper"},"deleted":false}"""

    private fun fixture(name: String): String =
        requireNotNull(V1FakeBitbucket::class.java.getResource("/bitbucket/v1/$name")).readText()

    companion object {
        fun start(directory: Path): V1FakeBitbucket {
            val tls = localTlsContext(directory)
            val previousDefault = SSLContext.getDefault()
            val previousProperties = SSL_PROPERTIES.associateWith(System::getProperty)
            var executor: java.util.concurrent.ExecutorService? = null
            var server: HttpsServer? = null
            try {
                System.setProperty("javax.net.ssl.trustStore", tls.keyStorePath.toString())
                System.setProperty("javax.net.ssl.trustStorePassword", tls.password)
                System.setProperty("javax.net.ssl.trustStoreType", "PKCS12")
                SSLContext.setDefault(tls.context)
                val createdExecutor = Executors.newCachedThreadPool()
                executor = createdExecutor
                val createdServer = HttpsServer.create(InetSocketAddress("127.0.0.1", 0), 0)
                server = createdServer
                lateinit var fixture: V1FakeBitbucket
                createdServer.httpsConfigurator = HttpsConfigurator(tls.context)
                createdServer.executor = createdExecutor
                createdServer.createContext("/") { exchange ->
                    try {
                        fixture.handle(exchange)
                    } finally {
                        exchange.close()
                    }
                }
                fixture = V1FakeBitbucket(createdServer, createdExecutor, previousDefault, previousProperties)
                createdServer.start()
                return fixture
            } catch (failure: Throwable) {
                var cleanupFailure: Throwable? = failure
                server?.let { cleanupFailure = recordFailure(cleanupFailure) { it.stop(0) } }
                executor?.let { cleanupFailure = recordFailure(cleanupFailure) { it.shutdownNow() } }
                cleanupFailure = recordFailure(cleanupFailure) {
                    restoreTlsState(previousDefault, previousProperties)
                }
                throw requireNotNull(cleanupFailure)
            }
        }

        private fun localTlsContext(directory: Path): LocalTls {
            val passwordText = "v1-test-store-password"
            val password = passwordText.toCharArray()
            val keyStorePath = directory.resolve("v1-test-tls.p12")
            val keytool = Path.of(System.getProperty("java.home"), "bin", "keytool")
            val process = ProcessBuilder(
                keytool.toString(),
                "-genkeypair",
                "-alias", "v1-test",
                "-keyalg", "RSA",
                "-keysize", "2048",
                "-validity", "2",
                "-dname", "CN=127.0.0.1",
                "-ext", "SAN=ip:127.0.0.1",
                "-storetype", "PKCS12",
                "-keystore", keyStorePath.toString(),
                "-storepass", password.concatToString(),
                "-keypass", password.concatToString(),
                "-noprompt",
            ).redirectErrorStream(true).start()
            val finished = process.waitFor(10, TimeUnit.SECONDS)
            val stopped = if (finished) {
                true
            } else {
                process.destroy()
                if (process.waitFor(1, TimeUnit.SECONDS)) true else {
                    process.destroyForcibly()
                    process.waitFor(2, TimeUnit.SECONDS)
                }
            }
            val output = if (stopped) {
                process.inputStream.use { String(it.readNBytes(KEYTOOL_OUTPUT_LIMIT_BYTES), UTF_8) }
            } else {
                "<process did not stop>"
            }
            check(finished && stopped && process.exitValue() == 0) {
                "local TLS fixture generation failed: $output"
            }

            val keyStore = KeyStore.getInstance("PKCS12")
            Files.newInputStream(keyStorePath).use { keyStore.load(it, password) }
            val keyManagers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply {
                init(keyStore, password)
            }
            val trustManagers = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply {
                init(keyStore)
            }
            val context = SSLContext.getInstance("TLS").apply {
                init(keyManagers.keyManagers, trustManagers.trustManagers, null)
            }
            return LocalTls(context, keyStorePath, passwordText)
        }

        private fun restoreTlsState(
            previousDefault: SSLContext,
            previousProperties: Map<String, String?>,
        ) {
            var failure: Throwable? = null
            failure = recordFailure(failure) { SSLContext.setDefault(previousDefault) }
            failure = recordFailure(failure) { restoreProperties(previousProperties) }
            failure?.let { throw it }
        }

        private fun restoreProperties(previous: Map<String, String?>) {
            var failure: Throwable? = null
            previous.forEach { (name, value) ->
                failure = recordFailure(failure) {
                    if (value == null) System.clearProperty(name) else System.setProperty(name, value)
                }
            }
            failure?.let { throw it }
        }

        private inline fun recordFailure(current: Throwable?, action: () -> Unit): Throwable? {
            val observed = runCatching(action).exceptionOrNull() ?: return current
            if (current == null) return observed
            current.addSuppressed(observed)
            return current
        }

        private data class LocalTls(val context: SSLContext, val keyStorePath: Path, val password: String)

        private val SSL_PROPERTIES = listOf(
            "javax.net.ssl.trustStore",
            "javax.net.ssl.trustStorePassword",
            "javax.net.ssl.trustStoreType",
        )
        const val KEYTOOL_OUTPUT_LIMIT_BYTES = 4 * 1024
    }
}

internal fun HttpExchange.respondV1(status: Int, body: String) {
    val bytes = body.toByteArray(UTF_8)
    responseHeaders.set(HttpHeaders.ContentType, ContentType.Application.Json.toString())
    sendResponseHeaders(status, bytes.size.toLong())
    responseBody.use { it.write(bytes) }
}

internal fun JsonObject.objectValue(name: String): JsonObject = getValue(name).jsonObject
internal fun JsonObject.array(name: String): JsonArray = getValue(name).jsonArray
internal fun JsonObject.string(name: String): String = getValue(name).jsonPrimitive.content

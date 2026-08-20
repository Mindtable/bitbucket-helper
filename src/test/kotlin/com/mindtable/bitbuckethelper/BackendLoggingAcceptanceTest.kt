package com.mindtable.bitbuckethelper

import com.mindtable.bitbuckethelper.bootstrap.LoggingConfiguration
import com.mindtable.bitbuckethelper.bootstrap.ServiceLogLevel
import com.mindtable.bitbuckethelper.bootstrap.ServiceLogging
import com.mindtable.bitbuckethelper.support.FakeDesktopNotificationsExecutable
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
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.util.Base64
import java.util.zip.GZIPInputStream
import java.net.ServerSocket
import java.nio.charset.StandardCharsets.ISO_8859_1
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.api.parallel.ResourceLock

/**
 * The process boundary is intentional here: unit and in-process acceptance
 * tests cannot prove service-only Log4j initialization, inherited credentials,
 * final shutdown flushing, or product-command isolation.
 */
@ResourceLock("global-tls-test-state")
class BackendLoggingAcceptanceTest {
    @Test
    @Timeout(value = 120, unit = TimeUnit.SECONDS)
    fun `fat jar service keeps correlated typed private logs and cli stays log free`(
        @TempDir directory: Path,
    ) {
        // Keep the socket path below macOS's Unix-domain 104-byte limit.  The
        // absolute-path sentinel is used as the final log-directory component,
        // never as an ancestor of the socket.
        val serviceRoot = createPrivateDirectory(directory.toRealPath().resolve("p"))
        val databaseParent = createPrivateDirectory(serviceRoot.resolve("database-parent"))
        val socketParent = createPrivateTempDirectory("bbh-socket-")
        val logParent = createPrivateDirectory(serviceRoot.resolve("log-parent"))
        val processOutput = createPrivateDirectory(serviceRoot.resolve("process-output"))
        val bitbucketRoot = createPrivateDirectory(serviceRoot.resolve("bitbucket-fixture"))
        val databasePath = databaseParent.resolve("state.sqlite")
        val socketPath = socketParent.resolve("service.sock")
        val logDirectory = logParent.resolve(ABSOLUTE_PATH_SENTINEL)
        val terminalPath = processOutput.resolve("service-stderr.txt")
        val standardOutPath = processOutput.resolve("service-stdout.txt")
        val notificationArgumentsPath = processOutput.resolve("notification-arguments.txt")
        val username = "acceptance-user@example.test"
        val token = "privacy-token-sentinel"
        val expectedAuthorization = "Basic " + Base64.getEncoder()
            .encodeToString("$username:$token".toByteArray(UTF_8))
        val fakeNotification = FakeDesktopNotificationsExecutable.create(
            processOutput,
            """
                printf '%s\n' "${'$'}@" >> '${notificationArgumentsPath.toAbsolutePath()}'
                printf '%s\n' 'privacy-provider-output-sentinel' >&2
                printf '%s\n' '{"status":"accepted"}'
            """.trimIndent(),
        )
        val bitbucket = V1FakeBitbucket.start(bitbucketRoot)
        bitbucket.privateRepositoryDisplayNameMarker =
            "$NOTIFICATION_CONTENT_SENTINEL-$SQL_BIND_SENTINEL"
        bitbucket.privatePullRequestTitleMarker = USER_TITLE_SENTINEL
        bitbucket.privateActivityBodyMarker = ACTIVITY_BODY_SENTINEL
        bitbucket.privateUpstreamHeaderMarker = UPSTREAM_HEADER_SENTINEL
        var service: Process? = null
        var refreshRequestId = ""
        var refreshRunId = ""
        var releaseRepositoryId = ""
        var failedRepositoryId = ""

        try {
            service = startServiceWithPortRetry(
                databasePath = databasePath,
                socketPath = socketPath,
                logDirectory = logDirectory,
                notificationExecutable = fakeNotification,
                username = username,
                token = token,
                trustStore = bitbucketRoot.resolve("v1-test-tls.p12"),
                standardOut = standardOutPath,
                standardErr = terminalPath,
            )

            runBlocking {
                val client = HttpClient(CIO) { expectSuccess = false }
                try {
                val health = client.requestUnix(socketPath, HttpMethod.Get, "/api/v1/health")
                assertEquals(HttpStatusCode.OK.value, health.status, health.body)

                val rejected = client.requestUnix(
                    socketPath,
                    HttpMethod.Get,
                    "/api/v1/not-a-route/$PRIVATE_PATH_SENTINEL?$PRIVATE_QUERY_SENTINEL=1",
                    headers = mapOf(
                        HttpHeaders.Authorization to "Basic $AUTHORIZATION_SENTINEL",
                        HttpHeaders.Cookie to "session=$COOKIE_SENTINEL",
                        "X-Private-Upstream-Header" to UPSTREAM_HEADER_SENTINEL,
                    ),
                )
                assertEquals(HttpStatusCode.NotFound.value, rejected.status, rejected.body)
                assertFalse(rejected.body.contains(PRIVATE_PATH_SENTINEL))
                assertFalse(rejected.body.contains(PRIVATE_QUERY_SENTINEL))

                val unsupportedBody = client.requestUnix(
                    socketPath,
                    HttpMethod.Post,
                    "/api/v1/refresh-runs",
                    body = PRIVATE_BODY_SENTINEL,
                    bodyContentType = ContentType.Text.Plain,
                )
                assertEquals(HttpStatusCode.UnsupportedMediaType.value, unsupportedBody.status, unsupportedBody.body)
                assertFalse(unsupportedBody.body.contains(PRIVATE_BODY_SENTINEL))

                val configured = client.requestUnix(
                    socketPath,
                    HttpMethod.Put,
                    "/api/v1/configuration/workspace",
                    body = """
                        {"apiVersion":"1","bitbucketApiBaseUrl":"${bitbucket.apiBaseUrl}","workspaceSlug":"acme-engineering"}
                    """.trimIndent(),
                )
                assertEquals(HttpStatusCode.OK.value, configured.status, configured.body)

                val releaseAdded = client.requestUnix(
                    socketPath,
                    HttpMethod.Post,
                    "/api/v1/configuration/workspace/repositories",
                    body = """{"apiVersion":"1","repositorySlug":"release-tools"}""",
                )
                assertEquals(HttpStatusCode.OK.value, releaseAdded.status, releaseAdded.body)
                releaseRepositoryId = releaseAdded.root().objectValue("result")
                    .objectValue("repository").string("repositoryId")

                val failedAdded = client.requestUnix(
                    socketPath,
                    HttpMethod.Post,
                    "/api/v1/configuration/workspace/repositories",
                    body = """{"apiVersion":"1","repositorySlug":"web-store"}""",
                )
                assertEquals(HttpStatusCode.OK.value, failedAdded.status, failedAdded.body)
                failedRepositoryId = failedAdded.root().objectValue("result")
                    .objectValue("repository").string("repositoryId")
                bitbucket.failedRepositorySlug = "web-store"

                val registered = client.requestUnix(
                    socketPath,
                    HttpMethod.Post,
                    "/api/v1/refresh-runs",
                    body = ALL_REPOSITORIES_BODY,
                )
                assertEquals(HttpStatusCode.OK.value, registered.status, registered.body)
                val registeredRoot = registered.root()
                refreshRequestId = registeredRoot.string("requestId")
                refreshRunId = registeredRoot.objectValue("result")
                    .objectValue("refreshRun").string("refreshRunId")

                val completed = awaitRefresh(client, socketPath, refreshRunId)
                assertEquals(HttpStatusCode.OK.value, completed.status, completed.body)
                val repositoryResults = completed.root().objectValue("result")
                    .objectValue("refreshRun").array("repositories")
                    .map { it.jsonObject }
                assertTrue(repositoryResults.any { it.string("repositoryId") == releaseRepositoryId })
                assertTrue(repositoryResults.any { it.string("repositoryId") == failedRepositoryId })

                val dashboard = client.requestUnix(socketPath, HttpMethod.Get, "/api/v1/dashboard")
                assertEquals(HttpStatusCode.OK.value, dashboard.status, dashboard.body)
                assertTrue(dashboard.body.contains(USER_TITLE_SENTINEL))
                val action = dashboard.root().objectValue("result").objectValue("snapshot")
                    .array("repositoryGroups")
                    .flatMap { it.jsonObject.array("pullRequests") }
                    .flatMap { it.jsonObject.array("actionItems") }
                    .first().jsonObject
                val liveContent = client.requestUnix(
                    socketPath,
                    HttpMethod.Get,
                    "/api/v1/action-items/${action.string("actionItemId")}/content?activityVersion=${action.string("activityVersion")}",
                )
                assertEquals(HttpStatusCode.OK.value, liveContent.status, liveContent.body)
                assertTrue(liveContent.body.contains(V1TestRig.LIVE_MARKDOWN))
                assertTrue(liveContent.body.contains(ACTIVITY_BODY_SENTINEL))

                awaitFileContains(notificationArgumentsPath, "--delivery-key")
                val notificationArguments = Files.readString(notificationArgumentsPath, UTF_8)
                assertTrue(notificationArguments.contains(NOTIFICATION_CONTENT_SENTINEL))
                assertTrue(notificationArguments.contains(SQL_BIND_SENTINEL))

                repeat(160) {
                    val debugHealth = client.requestUnix(socketPath, HttpMethod.Get, "/api/v1/health")
                    assertEquals(HttpStatusCode.OK.value, debugHealth.status, debugHealth.body)
                }
                awaitArchive(logDirectory)
                } finally {
                    client.close()
                }
            }

            stopProcess(service)
            service = null

            val terminal = readIfPresent(terminalPath)
            val standardOut = readIfPresent(standardOutPath)
            val activeJsonLines = Files.readAllLines(logDirectory.resolve(ACTIVE_LOG_NAME), UTF_8)
            val archives = logArchives(logDirectory)
            assertTrue(archives.isNotEmpty(), "acceptance did not produce a JSONL archive")
            val jsonLines = archives.flatMap(::readGzipLines) + activeJsonLines
            val records = jsonLines.map { line -> Json.parseToJsonElement(line).jsonObject }
            val appRecords = records.filter { it.stringOrNull("event") != null }

            assertFalse(standardOut.contains(token))
            assertTrue(
                appRecords.any { it.string("event") == "service.starting" },
                "records=${records.size}, events=${records.map { it.stringOrNull("event") }}",
            )
            assertTrue(appRecords.any { it.string("event") == "service.stopped" }, appRecords.map { it.string("event") }.toString())
            assertEquals("service.stopped", appRecords.last().string("event"), appRecords.map { it.string("event") }.toString())
            assertEquals(
                PosixFilePermissions.fromString("rwx------"),
                Files.getPosixFilePermissions(logDirectory),
            )
            assertOwnerOnlyLogFiles(logDirectory)
            assertTypedFields(records)
            assertOneDestinationPerEvent(appRecords, terminal)

            val debugEvent = requireNotNull(
                appRecords.firstOrNull {
                    it.string("event") == "http.request.completed" && it.string("level") == "DEBUG"
                },
            )
            assertFalse(debugEvent.getValue("duration_ms").jsonPrimitive.isString)
            assertFalse(debugEvent.getValue("mutation").jsonPrimitive.isString)
            assertTrue(
                terminal.lineSequence().any {
                    it.contains("DEBUG") && it.contains("event=http.request.completed")
                },
            )

            assertTrue(appRecords.any { it.string("event") == "service.starting" && it.string("configured_level") == "DEBUG" })
            assertTrue(appRecords.any { it.string("event") == "http.request.completed" && it.string("request_id") == refreshRequestId && it.string("refresh_run_id") == refreshRunId })
            assertTrue(appRecords.any { it.string("event") == "refresh.run.registered" && it.string("refresh_run_id") == refreshRunId })
            assertTrue(appRecords.any { it.string("event").startsWith("refresh.repository.") && it.stringOrNull("refresh_run_id") == refreshRunId && it.string("repository_id") == releaseRepositoryId })
            assertTrue(appRecords.any { it.string("event").startsWith("refresh.repository.") && it.stringOrNull("refresh_run_id") == refreshRunId && it.string("repository_id") == failedRepositoryId })
            assertTrue(appRecords.any { it.string("event") == "bitbucket.request.failed" && it.string("repository_id") == failedRepositoryId })

            val schedulerStart = appRecords.firstOrNull { it.string("event") == "scheduler.job.started" }
            val schedulerCompletion = appRecords.firstOrNull { it.string("event") == "scheduler.job.completed" }
            assertNotNull(schedulerStart)
            assertNotNull(schedulerCompletion)
            assertEquals(schedulerStart!!.string("scheduler_execution_id"), schedulerCompletion!!.string("scheduler_execution_id"))

            val notification = requireNotNull(appRecords.firstOrNull { it.string("event") == "notification.delivery.completed" })
            assertTrue(notification.string("notification_intent_id").startsWith("ni_"))
            assertTrue(notification.string("notification_attempt_id").startsWith("na_"))

            val forbidden = listOf(
                token,
                expectedAuthorization,
                AUTHORIZATION_SENTINEL,
                COOKIE_SENTINEL,
                PRIVATE_QUERY_SENTINEL,
                PRIVATE_PATH_SENTINEL,
                PRIVATE_BODY_SENTINEL,
                UPSTREAM_HEADER_SENTINEL,
                "private-upstream-detail",
                V1TestRig.RAW_ACTIVITY_MARKER,
                V1TestRig.LIVE_MARKDOWN,
                USER_TITLE_SENTINEL,
                ACTIVITY_BODY_SENTINEL,
                NOTIFICATION_CONTENT_SENTINEL,
                PROVIDER_OUTPUT_SENTINEL,
                SQL_BIND_SENTINEL,
                ABSOLUTE_PATH_SENTINEL,
                EXCEPTION_MESSAGE_SENTINEL,
                NEWLINE_SENTINEL,
                ANSI_SENTINEL,
                CONTROL_SENTINEL,
            )
            forbidden.forEach { value ->
                assertFalse(standardOut.contains(value), "service stdout exposed $value")
                assertFalse(terminal.contains(value), "terminal exposed $value")
                assertFalse(jsonLines.any { it.contains(value) }, "JSON exposed $value")
            }
            assertFalse(terminal.contains(databasePath.toString()))
            assertFalse(terminal.contains(socketPath.toString()))
            assertFalse(terminal.contains(logDirectory.toString()))

            val databaseSurfaces = Files.list(databaseParent).use { paths ->
                paths.filter { Files.isRegularFile(it, LinkOption.NOFOLLOW_LINKS) }
                    .map { Files.readString(it, ISO_8859_1) }
                    .toList()
            }
            assertTrue(databaseSurfaces.any { it.contains(SQL_BIND_SENTINEL) })

            assertEquals(
                PosixFilePermissions.fromString("rwx------"),
                Files.getPosixFilePermissions(databaseParent),
            )
            assertEquals(
                PosixFilePermissions.fromString("rwx------"),
                Files.getPosixFilePermissions(socketParent),
            )
            assertEquals(
                PosixFilePermissions.fromString("rwx------"),
                Files.getPosixFilePermissions(logParent),
            )
        } finally {
            service?.let(::stopProcess)
            bitbucket.close()
            socketParent.toFile().deleteRecursively()
        }

        val cliRoot = createPrivateDirectory(directory.resolve("fresh-cli"))
        val cliSocketParent = createPrivateTempDirectory("bbh-cli-socket-")
        val cliLogParent = createPrivateDirectory(cliRoot.resolve("log-parent"))
        val cliSocket = cliSocketParent.resolve("missing.sock")
        val cliLogDirectory = cliLogParent.resolve("logs")
        val cliResult = runProductCommand(
            workingDirectory = cliRoot,
            socketPath = cliSocket,
            logDirectory = cliLogDirectory,
        )
        val unavailableDocument =
            """{"cliVersion":"1","error":{"code":"SERVICE_UNAVAILABLE","message":"Bitbucket Helper service is unavailable. Run 'bitbucket-helper service status' and then 'bitbucket-helper service start'."}}"""
        assertEquals(4, cliResult.exitCode, cliResult.output())
        assertArrayEquals((unavailableDocument + "\n").toByteArray(UTF_8), cliResult.standardOut)
        assertEquals("", cliResult.standardErr.toString(UTF_8))
        assertFalse(Files.exists(cliLogDirectory, LinkOption.NOFOLLOW_LINKS))
        assertFalse(Files.exists(cliLogDirectory.resolve(ACTIVE_LOG_NAME), LinkOption.NOFOLLOW_LINKS))
        cliSocketParent.toFile().deleteRecursively()
    }

    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    fun `fat jar startup failure sanitizes real corrupt database diagnostics`(
        @TempDir directory: Path,
    ) {
        val root = createPrivateDirectory(directory.toRealPath().resolve("startup-failure"))
        val databaseParent = createPrivateDirectory(root.resolve("database-parent"))
        val processOutput = createPrivateDirectory(root.resolve("process-output"))
        val logParent = createPrivateDirectory(root.resolve("log-parent"))
        val socketParent = createPrivateTempDirectory("bbh-failure-socket-")
        val databasePath = databaseParent.resolve(
            "$EXCEPTION_PATH_SENTINEL-$EXCEPTION_MESSAGE_SENTINEL.sqlite",
        )
        Files.writeString(databasePath, "not-a-sqlite-database-$EXCEPTION_MESSAGE_SENTINEL")
        Files.setPosixFilePermissions(databasePath, PosixFilePermissions.fromString("rw-------"))
        val socketPath = socketParent.resolve("service.sock")
        val logDirectory = logParent.resolve("logs")
        val stdoutPath = processOutput.resolve("stdout.txt")
        val stderrPath = processOutput.resolve("stderr.txt")
        val notification = FakeDesktopNotificationsExecutable.create(
            processOutput,
            "printf '%s\\n' '{\"status\":\"accepted\"}'",
        )
        var process: Process? = null
        try {
            process = startService(
                databasePath = databasePath,
                socketPath = socketPath,
                logDirectory = logDirectory,
                notificationExecutable = notification,
                httpPort = freePort(),
                username = "failure-user@example.test",
                token = "failure-token-sentinel",
                trustStore = null,
                standardOut = stdoutPath,
                standardErr = stderrPath,
            )
            assertTrue(process.waitFor(30, TimeUnit.SECONDS), "corrupt-database service did not exit")
            assertTrue(process.exitValue() != 0)

            val terminal = readIfPresent(stderrPath)
            val standardOut = readIfPresent(stdoutPath)
            val jsonLines = Files.readAllLines(logDirectory.resolve(ACTIVE_LOG_NAME), UTF_8)
            val records = jsonLines.map { Json.parseToJsonElement(it).jsonObject }
            val failure = requireNotNull(records.firstOrNull { it.stringOrNull("event") == "service.start.failed" })
            val exceptionTypes = failure.getValue("exception_types").jsonArray
                .map { it.jsonPrimitive.content }
            assertTrue(exceptionTypes.isNotEmpty())
            assertTrue(
                failure.getValue("stack_trace").jsonPrimitive.content.contains("SqliteDatabase") ||
                    failure.getValue("stack_trace").jsonPrimitive.content.contains("JooqApplicationPersistence"),
            )
            assertTrue(terminal.contains("event=service.start.failed"))
            assertTrue(exceptionTypes.any(terminal::contains))
            assertTrue(terminal.contains("SqliteDatabase") || terminal.contains("JooqApplicationPersistence"))

            listOf(
                "failure-token-sentinel",
                EXCEPTION_PATH_SENTINEL,
                EXCEPTION_MESSAGE_SENTINEL,
                NEWLINE_SENTINEL,
                ANSI_SENTINEL,
                CONTROL_SENTINEL,
            ).forEach { marker ->
                assertFalse(standardOut.contains(marker), "failure stdout exposed $marker")
                assertFalse(terminal.contains(marker), "failure terminal exposed $marker")
                assertFalse(jsonLines.any { it.contains(marker) }, "failure JSON exposed $marker")
            }
            assertFalse(terminal.contains(databasePath.toString()))
        } finally {
            process?.let(::stopProcess)
            socketParent.toFile().deleteRecursively()
        }
    }

    @Test
    fun `safe failure diagnostics keep the exact privacy boundary in both destinations`(
        @TempDir directory: Path,
    ) {
        val terminalBytes = ByteArrayOutputStream()
        val previousError = System.err
        val terminal = PrintStream(terminalBytes, true, UTF_8)
        val logDirectory = directory.toRealPath().resolve("diagnostic-logs")
        val message = listOf(
            EXCEPTION_MESSAGE_SENTINEL,
            NOTIFICATION_CONTENT_SENTINEL,
            NEWLINE_SENTINEL,
            "\n",
            ANSI_SENTINEL,
            "\u001B[31m",
            CONTROL_SENTINEL,
            "\u0000",
        ).joinToString("")
        System.setErr(terminal)
        try {
            ServiceLogging.open(LoggingConfiguration(ServiceLogLevel.DEBUG, logDirectory), "svc_privacy").use { session ->
                session.recorder.record(
                    com.mindtable.bitbuckethelper.observability.BackendLogEvent.ServiceStartFailed(
                        "scheduler",
                        IllegalStateException(message),
                    ),
                )
            }
        } finally {
            System.setErr(previousError)
        }
        val terminalText = terminalBytes.toString(UTF_8)
        val json = Files.readAllLines(logDirectory.resolve(ACTIVE_LOG_NAME), UTF_8)
            .map { Json.parseToJsonElement(it).jsonObject }
        listOf(EXCEPTION_MESSAGE_SENTINEL, NOTIFICATION_CONTENT_SENTINEL, NEWLINE_SENTINEL, ANSI_SENTINEL, CONTROL_SENTINEL)
            .forEach { value ->
                assertFalse(terminalText.contains(value))
                assertFalse(json.any { it.toString().contains(value) })
            }
        assertTrue(json.single().getValue("exception_types").toString().contains("IllegalStateException"))
        assertTrue(json.single().getValue("stack_trace").toString().contains("BackendLoggingAcceptanceTest"))
    }

    @Test
    fun `third party logger thresholds cannot follow application trace`() {
        val xml = BackendLoggingAcceptanceTest::class.java.getResourceAsStream("/log4j2.xml")!!
            .bufferedReader().use { it.readText() }
        assertTrue(xml.contains("<Logger name=\"io.ktor\" level=\"WARN\""), xml)
        assertTrue(xml.contains("<Logger name=\"io.ktor.server.plugins.statuspages\" level=\"WARN\""), xml)
        assertTrue(xml.contains("<Logger name=\"io.ktor.client\" level=\"WARN\""), xml)
        assertTrue(xml.contains("<Logger name=\"io.ktor.client.plugins.logging\" level=\"OFF\""), xml)
        assertFalse(Regex("<Logger name=\"(?:io\\.ktor|org\\.jooq)[^\"]*\" level=\"TRACE\"").containsMatchIn(xml))
        assertTrue(xml.contains("<Property name=\"rolloverSize\">\${sys:bitbucketHelper.logging.test.rollover.size:-10 MB}</Property>"))
        assertTrue(xml.contains("<IfLastModified age=\"14d\"/>"))
        assertTrue(xml.contains("<IfAccumulatedFileSize exceeds=\"200 MB\"/>"))
        assertTrue(xml.contains("filePattern=\"\${logDirectory}/bitbucket-helper-%d{yyyy-MM-dd}{UTC}-%i.jsonl.gz\""))
    }

    private fun startServiceWithPortRetry(
        databasePath: Path,
        socketPath: Path,
        logDirectory: Path,
        notificationExecutable: Path,
        username: String,
        token: String,
        trustStore: Path?,
        standardOut: Path,
        standardErr: Path,
    ): Process {
        var lastFailure: Throwable? = null
        repeat(MAX_PORT_ATTEMPTS) {
            val process = startService(
                databasePath = databasePath,
                socketPath = socketPath,
                logDirectory = logDirectory,
                notificationExecutable = notificationExecutable,
                httpPort = freePort(),
                username = username,
                token = token,
                trustStore = trustStore,
                standardOut = standardOut,
                standardErr = standardErr,
            )
            try {
                runBlocking {
                    HttpClient(CIO).use { client ->
                        awaitSocket(client, socketPath, process)
                    }
                }
                return process
            } catch (failure: Throwable) {
                lastFailure = failure
                stopProcess(process)
                Files.deleteIfExists(socketPath)
            }
        }
        throw AssertionError("service did not bind after $MAX_PORT_ATTEMPTS port candidates", lastFailure)
    }

    private fun startService(
        databasePath: Path,
        socketPath: Path,
        logDirectory: Path,
        notificationExecutable: Path,
        httpPort: Int,
        username: String,
        token: String,
        trustStore: Path?,
        standardOut: Path,
        standardErr: Path,
    ): Process {
        val javaExecutable = ProcessHandle.current().info().command().orElseThrow()
        val fatJar = locateSingleFatJar()
        val command = buildList {
            add(javaExecutable)
            trustStore?.let {
                add("-Djavax.net.ssl.trustStore=${it.toAbsolutePath().normalize()}")
                add("-Djavax.net.ssl.trustStorePassword=v1-test-store-password")
                add("-Djavax.net.ssl.trustStoreType=PKCS12")
            }
            add("-DbitbucketHelper.logging.test.rollover.size=$ROLLOVER_TEST_SIZE")
            add("--enable-native-access=ALL-UNNAMED")
            add("-jar")
            add(fatJar.toAbsolutePath().normalize().toString())
            add("service")
            add("run")
        }
        return ProcessBuilder(command)
            .redirectOutput(standardOut.toFile())
            .redirectError(standardErr.toFile())
            .apply {
                environment().apply {
                    put("BITBUCKET_USERNAME", username)
                    put("BITBUCKET_APP_PASSWORD", token)
                    put("BITBUCKET_HELPER_HTTP_PORT", httpPort.toString())
                    put("BITBUCKET_HELPER_DATABASE_PATH", databasePath.toAbsolutePath().normalize().toString())
                    put("BITBUCKET_HELPER_UNIX_SOCKET_PATH", socketPath.toAbsolutePath().normalize().toString())
                    put("BITBUCKET_HELPER_LOG_DIRECTORY", logDirectory.toAbsolutePath().normalize().toString())
                    put("BITBUCKET_HELPER_NOTIFICATION_EXECUTABLE", notificationExecutable.toAbsolutePath().normalize().toString())
                    remove("BITBUCKET_HELPER_LOG_LEVEL")
                }
            }
            .start()
    }

    private fun runProductCommand(
        workingDirectory: Path,
        socketPath: Path,
        logDirectory: Path,
    ): ProcessResult {
        val javaExecutable = ProcessHandle.current().info().command().orElseThrow()
        val processOutput = createPrivateDirectory(workingDirectory.resolve("output"))
        val stdout = processOutput.resolve("stdout.txt")
        val stderr = processOutput.resolve("stderr.txt")
        val process = ProcessBuilder(
            javaExecutable,
            "--enable-native-access=ALL-UNNAMED",
            "-jar",
            locateSingleFatJar().toAbsolutePath().normalize().toString(),
            "workspace",
            "show",
            "--output",
            "json",
        )
            .directory(workingDirectory.toFile())
            .redirectOutput(stdout.toFile())
            .redirectError(stderr.toFile())
            .apply {
                environment().apply {
                    put("BITBUCKET_HELPER_UNIX_SOCKET_PATH", socketPath.toAbsolutePath().normalize().toString())
                    put("BITBUCKET_HELPER_LOG_DIRECTORY", logDirectory.toAbsolutePath().normalize().toString())
                    remove("BITBUCKET_HELPER_USERNAME")
                    remove("BITBUCKET_HELPER_LOG_LEVEL")
                }
            }
            .start()
        val finished = process.waitFor(20, TimeUnit.SECONDS)
        if (!finished) stopProcess(process)
        return ProcessResult(
            exitCode = if (finished) process.exitValue() else -1,
            standardOut = if (Files.exists(stdout)) Files.readAllBytes(stdout) else ByteArray(0),
            standardErr = if (Files.exists(stderr)) Files.readAllBytes(stderr) else ByteArray(0),
        )
    }

    private suspend fun awaitSocket(
        client: HttpClient,
        socketPath: Path,
        service: Process,
    ) {
        val deadline = System.nanoTime() + 30_000_000_000L
        var lastFailure: Throwable? = null
        while (System.nanoTime() < deadline) {
            if (!service.isAlive) {
                throw AssertionError(
                    "service exited before readiness; exit=${runCatching { service.exitValue() }.getOrNull()}",
                    lastFailure,
                )
            }
            try {
                val response = client.requestUnix(socketPath, HttpMethod.Get, "/api/v1/health")
                if (response.status == HttpStatusCode.OK.value) return
                lastFailure = AssertionError("health status=${response.status}: ${response.body}")
            } catch (failure: Throwable) {
                lastFailure = failure
            }
            delay(100)
        }
        throw AssertionError(
            "service did not become ready; alive=${service.isAlive}, exit=${runCatching { service.exitValue() }.getOrNull()}",
            lastFailure,
        )
    }

    private suspend fun awaitRefresh(
        client: HttpClient,
        socketPath: Path,
        refreshRunId: String,
    ): WireResponse {
        val deadline = System.nanoTime() + 30_000_000_000L
        var last = WireResponse(-1, "", emptyMap())
        while (System.nanoTime() < deadline) {
            last = client.requestUnix(socketPath, HttpMethod.Get, "/api/v1/refresh-runs/$refreshRunId")
            if (last.status == HttpStatusCode.OK.value) {
                val result = last.root().objectValue("result")
                if (result.string("type") == "refreshRunCompleted") return last
            }
            delay(75)
        }
        throw AssertionError("refresh did not complete: ${last.body}")
    }

    private suspend fun awaitFileContains(path: Path, marker: String) {
        val deadline = System.nanoTime() + 20_000_000_000L
        while (System.nanoTime() < deadline) {
            if (Files.exists(path) && Files.readString(path, UTF_8).contains(marker)) return
            delay(75)
        }
        throw AssertionError("notification provider was not invoked")
    }

    private suspend fun awaitArchive(directory: Path) {
        val deadline = System.nanoTime() + 30_000_000_000L
        while (System.nanoTime() < deadline) {
            if (logArchives(directory).isNotEmpty()) return
            delay(100)
        }
        throw AssertionError("rolling log archive was not created")
    }

    private fun logArchives(directory: Path): List<Path> = Files.list(directory).use { entries ->
        entries.filter {
            Files.isRegularFile(it, LinkOption.NOFOLLOW_LINKS) &&
                it.fileName.toString().endsWith(".jsonl.gz")
        }.sorted().toList()
    }

    private fun readGzipLines(path: Path): List<String> =
        GZIPInputStream(Files.newInputStream(path)).bufferedReader(UTF_8).use { it.readLines() }

    private fun assertOwnerOnlyLogFiles(directory: Path) {
        Files.list(directory).use { entries ->
            entries.filter { Files.isRegularFile(it, LinkOption.NOFOLLOW_LINKS) }.forEach { file ->
                assertEquals(
                    PosixFilePermissions.fromString("rw-------"),
                    Files.getPosixFilePermissions(file),
                    file.toString(),
                )
            }
        }
    }

    private fun assertTypedFields(records: List<JsonObject>) {
        val numericFields = setOf(
            "browser_port",
            "status",
            "duration_ms",
            "repository_count",
            "started_count",
            "joined_count",
            "deferred_count",
            "not_configured_count",
            "attempt_number",
        )
        val booleanFields = setOf("mutation", "retryable", "ambiguous", "diagnostic_truncated")
        records.forEach { record ->
            numericFields.forEach { field -> record[field]?.let { assertFalse(it.jsonPrimitive.isString, field) } }
            booleanFields.forEach { field -> record[field]?.let { assertFalse(it.jsonPrimitive.isString, field) } }
        }
    }

    private fun assertOneDestinationPerEvent(records: List<JsonObject>, terminal: String) {
        val jsonCounts = records.map { it.string("event") }.groupingBy { it }.eachCount()
        val terminalCounts = terminal.lineSequence()
            .mapNotNull { Regex("event=([a-z0-9_.]+)").find(it)?.groupValues?.get(1) }
            .groupingBy { it }.eachCount()
        jsonCounts.forEach { (event, count) ->
            assertEquals(count, terminalCounts[event], "terminal/file event count differs for $event")
        }
        assertEquals(1, jsonCounts["service.starting"])
        assertEquals(1, jsonCounts["service.started"])
        assertEquals(1, jsonCounts["service.stopped"])
    }

    private fun locateSingleFatJar(): Path {
        val directory = Path.of(System.getProperty("user.dir"), "build", "libs")
        val jars = Files.list(directory).use { paths ->
            paths.filter { Files.isRegularFile(it) && it.fileName.toString().endsWith("-all.jar") }.toList()
        }
        assertEquals(1, jars.size, "Expected exactly one current *-all.jar")
        return jars.single()
    }

    private fun stopProcess(process: Process) {
        if (!process.isAlive) return
        process.destroy()
        if (!process.waitFor(15, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            assertTrue(process.waitFor(5, TimeUnit.SECONDS), "service process did not stop")
        }
    }

    private fun createPrivateDirectory(path: Path): Path {
        Files.createDirectory(path)
        Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rwx------"))
        return path
    }

    private fun createPrivateTempDirectory(prefix: String): Path =
        Files.createTempDirectory(Path.of("/tmp"), prefix).also {
            Files.setPosixFilePermissions(it, PosixFilePermissions.fromString("rwx------"))
        }

    private fun readIfPresent(path: Path): String = if (Files.exists(path)) Files.readString(path, UTF_8) else ""

    private fun freePort(): Int = ServerSocket(0).use { it.localPort }

    internal data class WireResponse(
        val status: Int,
        val body: String,
        val headers: Map<String, List<String>>,
    ) {
        fun root(): JsonObject = Json.parseToJsonElement(body).jsonObject
    }

    private data class ProcessResult(
        val exitCode: Int,
        val standardOut: ByteArray,
        val standardErr: ByteArray,
    ) {
        fun output(): String = "stdout:\n${standardOut.toString(UTF_8)}\nstderr:\n${standardErr.toString(UTF_8)}"
    }

    private companion object {
        const val ACTIVE_LOG_NAME = "bitbucket-helper.jsonl"
        const val MAX_PORT_ATTEMPTS = 3
        const val ROLLOVER_TEST_SIZE = "32 KB"
        const val ABSOLUTE_PATH_SENTINEL = "privacy-absolute-path-sentinel"
        const val AUTHORIZATION_SENTINEL = "privacy-authorization-sentinel"
        const val COOKIE_SENTINEL = "privacy-cookie-sentinel"
        const val PRIVATE_QUERY_SENTINEL = "privacy-query-sentinel"
        const val PRIVATE_PATH_SENTINEL = "privacy-path-sentinel"
        const val PRIVATE_BODY_SENTINEL = "privacy-body-sentinel"
        const val UPSTREAM_HEADER_SENTINEL = "privacy-upstream-header-sentinel"
        const val USER_TITLE_SENTINEL = "privacy-user-title-sentinel"
        const val ACTIVITY_BODY_SENTINEL = "privacy-activity-body-sentinel"
        const val NOTIFICATION_CONTENT_SENTINEL = "privacy-notification-content-sentinel"
        const val PROVIDER_OUTPUT_SENTINEL = "privacy-provider-output-sentinel"
        const val SQL_BIND_SENTINEL = "privacy-sql-bind-sentinel"
        const val EXCEPTION_MESSAGE_SENTINEL = "privacy-exception-message-sentinel"
        const val EXCEPTION_PATH_SENTINEL = "privacy-exception-path-sentinel"
        const val NEWLINE_SENTINEL = "privacy-newline-sentinel"
        const val ANSI_SENTINEL = "privacy-ansi-sentinel"
        const val CONTROL_SENTINEL = "privacy-control-sentinel"
        const val ALL_REPOSITORIES_BODY =
            """{"apiVersion":"1","target":{"type":"allConfiguredRepositories"}}"""
    }
}

private suspend fun HttpClient.requestUnix(
    socketPath: Path,
    method: HttpMethod,
    path: String,
    body: String? = null,
    headers: Map<String, String> = emptyMap(),
    bodyContentType: ContentType = ContentType.Application.Json,
): BackendLoggingAcceptanceTest.WireResponse {
    val response = request("http://localhost$path") {
        this.method = method
        unixSocket(socketPath.toString())
        headers.forEach { (name, value) -> header(name, value) }
        if (body != null) {
            contentType(bodyContentType)
            setBody(body)
        }
    }
    return BackendLoggingAcceptanceTest.WireResponse(
        status = response.status.value,
        body = response.bodyAsText(),
        headers = response.headers.names().associateWith { response.headers.getAll(it).orEmpty() },
    )
}

private fun JsonObject.stringOrNull(name: String): String? = this[name]?.jsonPrimitive?.content

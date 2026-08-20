package com.mindtable.bitbuckethelper.adapter.inbound.http

import com.mindtable.bitbuckethelper.adapter.outbound.bitbucket.GeneratedBitbucketGateway
import com.mindtable.bitbuckethelper.application.model.AcknowledgeActionItemResult
import com.mindtable.bitbuckethelper.application.model.AddRepositoryResult
import com.mindtable.bitbuckethelper.application.model.ConfigureWorkspaceResult
import com.mindtable.bitbuckethelper.application.model.DashboardResult
import com.mindtable.bitbuckethelper.application.model.GatewayPullRequestSummary
import com.mindtable.bitbuckethelper.application.model.GatewayRepositoryAddress
import com.mindtable.bitbuckethelper.application.model.GatewayResult
import com.mindtable.bitbuckethelper.application.model.GetInboxResult
import com.mindtable.bitbuckethelper.application.model.GetPullRequestResult
import com.mindtable.bitbuckethelper.application.model.GetRefreshRunResult
import com.mindtable.bitbuckethelper.application.model.GetSynchronizationStatusResult
import com.mindtable.bitbuckethelper.application.model.GetWorkspaceConfigurationResult
import com.mindtable.bitbuckethelper.application.model.HealthComponent
import com.mindtable.bitbuckethelper.application.model.HealthComponentSnapshot
import com.mindtable.bitbuckethelper.application.model.HealthSnapshot
import com.mindtable.bitbuckethelper.application.model.HealthStatus
import com.mindtable.bitbuckethelper.application.model.ListPullRequestsResult
import com.mindtable.bitbuckethelper.application.model.LiveActivityContentResult
import com.mindtable.bitbuckethelper.application.model.RefreshTarget
import com.mindtable.bitbuckethelper.application.model.RemoveRepositoryResult
import com.mindtable.bitbuckethelper.application.model.StartRefreshRunCommand
import com.mindtable.bitbuckethelper.application.model.StartRefreshRunResult
import com.mindtable.bitbuckethelper.application.port.inbound.AcknowledgeActionItem
import com.mindtable.bitbuckethelper.application.port.inbound.AddRepository
import com.mindtable.bitbuckethelper.application.port.inbound.ConfigureWorkspace
import com.mindtable.bitbuckethelper.application.port.inbound.GetDashboardSnapshot
import com.mindtable.bitbuckethelper.application.port.inbound.GetHealthSnapshot
import com.mindtable.bitbuckethelper.application.port.inbound.GetInbox
import com.mindtable.bitbuckethelper.application.port.inbound.GetLiveActivityContent
import com.mindtable.bitbuckethelper.application.port.inbound.GetPullRequest
import com.mindtable.bitbuckethelper.application.port.inbound.GetRefreshRun
import com.mindtable.bitbuckethelper.application.port.inbound.GetSynchronizationStatus
import com.mindtable.bitbuckethelper.application.port.inbound.GetWorkspaceConfiguration
import com.mindtable.bitbuckethelper.application.port.inbound.ListPullRequests
import com.mindtable.bitbuckethelper.application.port.inbound.RemoveRepository
import com.mindtable.bitbuckethelper.application.port.inbound.StartRefreshRun
import com.mindtable.bitbuckethelper.application.port.outbound.BitbucketGateway
import com.mindtable.bitbuckethelper.observability.BackendEventRecorder
import com.mindtable.bitbuckethelper.observability.BackendLogEvent
import com.mindtable.bitbuckethelper.observability.MonotonicTimeSource
import com.mindtable.bitbuckethelper.domain.shared.RepositoryId
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.request.unixSocket
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.net.InetSocketAddress
import java.net.URI
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.PosixFilePermission
import java.time.Duration
import java.time.Instant
import java.util.concurrent.Executors
import java.util.logging.Formatter
import java.util.logging.Handler
import java.util.logging.Level
import java.util.logging.LogRecord
import java.util.logging.Logger
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ApiTransportAcceptanceTest {
    @Test
    fun `the supplied recorder observes browser and unix requests`() = runBlocking {
        val events = mutableListOf<BackendLogEvent>()
        withRunningServers(
            dependencies = AcceptanceState().dependencies(),
            backendEventRecorder = BackendEventRecorder(events::add),
        ) { servers, socketPath ->
            HttpClient(CIO).use { client ->
                val browser = client.get("http://127.0.0.1:${servers.browserPort}/api/v1/health")
                val unix = client.get("http://untrusted.invalid/api/v1/health") {
                    unixSocket(socketPath.toString())
                }
                assertEquals(HttpStatusCode.OK, browser.status)
                assertEquals(HttpStatusCode.OK, unix.status)
            }
        }

        val requests = events.filterIsInstance<BackendLogEvent.HttpRequestCompleted>()
        assertEquals(listOf("browser", "unix"), requests.map { it.transport }.sorted())
        assertEquals(setOf("health"), requests.map { it.operation }.toSet())
    }

    @Test
    fun `unix missing browser session is one fixed route not found event`() = runBlocking {
        val events = mutableListOf<BackendLogEvent>()
        var now = 1_000_000L
        val time = MonotonicTimeSource {
            now += 3_000_000L
            now
        }
        var responseRequestId: String? = null
        withRunningServers(
            dependencies = AcceptanceState().dependencies(),
            backendEventRecorder = BackendEventRecorder(events::add),
            monotonicTimeSource = time,
        ) { _, socketPath ->
            HttpClient(CIO).use { client ->
                val response = client.get("http://untrusted.invalid/api/v1/browser-session?private=query-sentinel") {
                    unixSocket(socketPath.toString())
                }
                val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
                assertEquals(HttpStatusCode.NotFound, response.status)
                responseRequestId = body.getValue("requestId").jsonPrimitive.content
            }
        }

        val event = events.single() as BackendLogEvent.HttpRequestRejected
        assertEquals(responseRequestId, event.requestId)
        assertEquals("unix", event.transport)
        assertEquals("route_not_found", event.operation)
        assertEquals(404, event.status)
        assertEquals("ROUTE_NOT_FOUND", event.requestErrorCode)
        assertEquals(3L, event.durationMilliseconds)
        assertEquals(com.mindtable.bitbuckethelper.observability.BackendLogLevel.WARN, event.level)
        assertFalse(event.toString().contains("browser-session"))
        assertFalse(event.toString().contains("query-sentinel"))
    }

    @Test
    fun `both real transports expose every endpoint family and keep live content private`() = runBlocking {
        val state = AcceptanceState()
        val capture = captureProcessOutput {
            println(STDOUT_CAPTURE_CANARY)
            System.err.println(STDERR_CAPTURE_CANARY)
            Logger.getLogger(LOGGER_NAME).log(Level.INFO, JUL_CAPTURE_TEMPLATE, JUL_CAPTURE_PARAMETER)
            withRunningServers(state.dependencies()) { servers, socketPath ->
                HttpClient(CIO).use { client ->
                    val browserBase = "http://127.0.0.1:${servers.browserPort}"
                    val session = client.get("$browserBase/api/v1/browser-session")
                    val csrfToken = session.result().string("csrfToken")

                    assertEquals(HttpStatusCode.OK, session.status)
                    assertFalse(session.hasCorsHeaders())
                    assertEquals(OWNER_DIRECTORY_PERMISSIONS, Files.getPosixFilePermissions(socketPath.parent))
                    assertEquals(OWNER_SOCKET_PERMISSIONS, Files.getPosixFilePermissions(socketPath))
                    assertTrue(
                        Files.readAttributes(
                            socketPath,
                            BasicFileAttributes::class.java,
                            LinkOption.NOFOLLOW_LINKS,
                        ).isOther,
                    )

                    for (routeCase in ROUTE_MATRIX) {
                        val browser = client.execute(
                            routeCase = routeCase,
                            logicalBase = browserBase,
                            csrfToken = csrfToken,
                        )
                        val unix = client.execute(
                            routeCase = routeCase,
                            logicalBase = "http://untrusted.invalid",
                            socketPath = socketPath,
                        )

                        assertSuccessfulResult(browser, routeCase.expectedType, routeCase.path)
                        assertSuccessfulResult(unix, routeCase.expectedType, routeCase.path)
                        assertFalse(browser.hasCorsHeaders(), routeCase.path)
                        assertFalse(unix.hasCorsHeaders(), routeCase.path)
                        if (routeCase.expectedType == "contentAvailable") {
                            assertEquals(SENSITIVE_MARKER, browser.result().string("markdown"))
                            assertEquals(SENSITIVE_MARKER, unix.result().string("markdown"))
                        } else {
                            assertFalse(browser.bodyAsText().contains(SENSITIVE_MARKER))
                            assertFalse(unix.bodyAsText().contains(SENSITIVE_MARKER))
                        }
                    }

                    val unixSession = client.get("http://untrusted.invalid/api/v1/browser-session") {
                        unixSocket(socketPath.toString())
                        header(HttpHeaders.Origin, "https://attacker.invalid")
                    }
                    assertRequestError(unixSession, HttpStatusCode.NotFound, "ROUTE_NOT_FOUND")
                }
            }
        }

        assertEquals(2, state.startRefreshCommands.size)
        assertTrue(
            state.startRefreshCommands.all {
                it == StartRefreshRunCommand(RefreshTarget.Repositories(listOf(REPOSITORY_A, REPOSITORY_B)))
            },
        )
        assertTrue(capture.stdout.contains(STDOUT_CAPTURE_CANARY), "stdout capture canary was not observed")
        assertTrue(capture.stderr.contains(STDERR_CAPTURE_CANARY), "stderr capture canary was not observed")
        assertTrue(capture.jul.contains(JUL_CAPTURE_FORMATTED), "formatted JUL parameter canary was not observed")
        capture.surfaces.forEach { surface ->
            assertFalse(surface.contains(SENSITIVE_MARKER), "live content escaped into a captured log surface")
        }
    }

    @Test
    fun `real loopback transport returns every safe request error class and enforces browser security`() = runBlocking {
        val state = AcceptanceState(healthFailure = IllegalStateException("$SENSITIVE_MARKER upstream stack detail"))
        val capture = captureProcessOutput {
            withRunningServers(state.dependencies()) { servers, _ ->
                HttpClient(CIO).use { client ->
                    val base = "http://127.0.0.1:${servers.browserPort}"
                    val session = client.get("$base/api/v1/browser-session")
                    val csrf = session.result().string("csrfToken")

                    val invalid = client.get("$base/api/v1/pull-requests/not-an-id")
                    val forbidden = client.get("$base/api/v1/dashboard") {
                        header(HttpHeaders.Host, "localhost:${servers.browserPort}")
                    }
                    val notFound = client.get("$base/api/v1/unknown?secret=$SENSITIVE_MARKER")
                    val methodNotAllowed = client.request("$base/api/v1/dashboard") {
                        method = HttpMethod.Patch
                        header(HttpHeaders.Origin, base)
                        header(CSRF_HEADER, csrf)
                        contentType(ContentType.Application.Json)
                        setBody("{}")
                    }
                    val unsupportedContent = client.request("$base/api/v1/refresh-runs") {
                        method = HttpMethod.Post
                        header(HttpHeaders.Origin, base)
                        header(CSRF_HEADER, csrf)
                        contentType(ContentType.Text.Plain)
                        setBody(SENSITIVE_MARKER)
                    }
                    val missingCsrf = client.request("$base/api/v1/refresh-runs") {
                        method = HttpMethod.Post
                        header(HttpHeaders.Origin, base)
                        contentType(ContentType.Application.Json)
                        setBody("""{"apiVersion":"1","target":{"type":"allConfiguredRepositories"}}""")
                    }
                    val internal = client.get("$base/api/v1/health")

                    val errors = listOf(
                        Triple(invalid, HttpStatusCode.BadRequest, "INVALID_REQUEST"),
                        Triple(forbidden, HttpStatusCode.Forbidden, "FORBIDDEN"),
                        Triple(notFound, HttpStatusCode.NotFound, "ROUTE_NOT_FOUND"),
                        Triple(methodNotAllowed, HttpStatusCode.MethodNotAllowed, "METHOD_NOT_ALLOWED"),
                        Triple(unsupportedContent, HttpStatusCode.UnsupportedMediaType, "UNSUPPORTED_CONTENT_TYPE"),
                        Triple(missingCsrf, HttpStatusCode.Forbidden, "FORBIDDEN"),
                        Triple(internal, HttpStatusCode.InternalServerError, "INTERNAL_SERVER_ERROR"),
                    )
                    for ((response, status, code) in errors) {
                        assertRequestError(response, status, code)
                        assertFalse(response.bodyAsText().contains(SENSITIVE_MARKER), code)
                        assertFalse(response.hasCorsHeaders(), code)
                    }

                    val correctOriginRead = client.get("$base/api/v1/dashboard") {
                        header(HttpHeaders.Origin, base)
                    }
                    assertEquals(HttpStatusCode.OK, correctOriginRead.status)
                    val wrongOriginRead = client.get("$base/api/v1/dashboard") {
                        header(HttpHeaders.Origin, "https://attacker.invalid")
                    }
                    assertRequestError(wrongOriginRead, HttpStatusCode.Forbidden, "FORBIDDEN")
                    assertTrue(state.startRefreshCommands.isEmpty(), "rejected mutations invoked the use case")
                }
            }
        }

        capture.surfaces.forEach { surface ->
            assertFalse(surface.contains(SENSITIVE_MARKER), "request data escaped into a captured log surface")
        }
    }

    @Test
    fun `health reports every typed state and socket is removed after idempotent close`() = runBlocking {
        val states = ArrayDeque(listOf(HealthStatus.HEALTHY, HealthStatus.DEGRADED, HealthStatus.UNHEALTHY))
        val parent = secureTemporaryDirectory("bbh-acceptance-health-")
        val socketPath = parent.resolve("api.sock")
        val dependencies = AcceptanceState(healthStatuses = states).dependencies()
        val servers = startServers(socketPath, dependencies)
        try {
            HttpClient(CIO).use { client ->
                val base = "http://127.0.0.1:${servers.browserPort}"
                for (expected in listOf("healthy", "degraded", "unhealthy")) {
                    val response = client.get("$base/api/v1/health")
                    assertEquals(HttpStatusCode.OK, response.status)
                    assertEquals("healthSnapshot", response.result().string("type"))
                    assertEquals(expected, response.result().string("status"))
                    assertEquals(4, response.result().getValue("components").jsonArray.size)
                }
            }
        } finally {
            servers.close()
            servers.close()
        }

        assertFalse(Files.exists(socketPath, LinkOption.NOFOLLOW_LINKS))
        Files.deleteIfExists(lifecycleLockPath(socketPath))
        Files.delete(parent)
    }

    @Test
    fun `multipage Bitbucket output maps to refresh input without generated DTOs crossing seams`() = runBlocking {
        val requestPaths = mutableListOf<URI>()
        withFakeBitbucket { exchange ->
            requestPaths += exchange.requestURI
            val body = if (exchange.requestURI.rawQuery?.contains("page=2") == true) {
                fixture("pull-requests-page-2.json")
            } else {
                fixture("pull-requests-page-1.json")
            }
            exchange.respond(200, body)
        }.use { fake ->
            GeneratedBitbucketGateway.create(
                requestTimeout = Duration.ofSeconds(2),
                username = "acceptance-user",
                appPassword = "acceptance-password",
            ).use { gateway ->
                val result = gateway.listAuthoredOpenPullRequests(
                    repository = GatewayRepositoryAddress(
                        id = REPOSITORY_A,
                        apiBaseUrl = URI("${fake.baseUrl}/configured/2.0"),
                        workspaceSlug = "acme-engineering",
                        repositorySlug = "release-tools",
                    ),
                    currentUserStableId = BITBUCKET_USER_ID,
                )
                val summaries = (result as GatewayResult.Success<List<GatewayPullRequestSummary>>).value

                assertEquals(listOf(41L, 42L), summaries.map(GatewayPullRequestSummary::upstreamNumber))
                assertTrue(summaries.all { it.repositoryId == REPOSITORY_A })

                val state = AcceptanceState()
                withRunningServers(state.dependencies()) { servers, _ ->
                    HttpClient(CIO).use { client ->
                        val base = "http://127.0.0.1:${servers.browserPort}"
                        val csrf = client.get("$base/api/v1/browser-session").result().string("csrfToken")
                        val repositoryIds = summaries.map(GatewayPullRequestSummary::repositoryId).distinct()
                        val response = client.request("$base/api/v1/refresh-runs") {
                            method = HttpMethod.Post
                            header(HttpHeaders.Origin, base)
                            header(CSRF_HEADER, csrf)
                            contentType(ContentType.Application.Json)
                            setBody(
                                """{"apiVersion":"1","target":{"type":"repositories","repositoryIds":[${repositoryIds.joinToString { "\"${it.value}\"" }}]}}""",
                            )
                        }
                        assertSuccessfulResult(response, "workspaceNotConfigured", "refresh input")
                        assertEquals(
                            StartRefreshRunCommand(RefreshTarget.Repositories(repositoryIds)),
                            state.startRefreshCommands.single(),
                        )
                    }
                }
            }
        }

        assertEquals(2, requestPaths.size)
        assertTrue(requestPaths[1].rawQuery.contains("page=2"))
        assertNoGeneratedTypesCrossApplicationSeams()
    }

    private fun AcceptanceState.dependencies() = LocalApiServerDependencies(
        read = ReadApiV1Dependencies(
            getDashboardSnapshot = GetDashboardSnapshot { DashboardResult.WorkspaceNotConfigured },
            listPullRequests = ListPullRequests { ListPullRequestsResult.WorkspaceNotConfigured },
            getPullRequest = GetPullRequest { query -> GetPullRequestResult.PullRequestNotFound(query.pullRequestId) },
            getInbox = GetInbox { GetInboxResult.WorkspaceNotConfigured },
            getSynchronizationStatus = GetSynchronizationStatus {
                GetSynchronizationStatusResult.WorkspaceNotConfigured
            },
        ),
        actionItems = ActionItemApiV1Dependencies(
            getLiveActivityContent = GetLiveActivityContent { command ->
                LiveActivityContentResult.ContentAvailable(
                    actionItemId = command.actionItemId,
                    requestedVersion = command.activityVersion,
                    markdown = SENSITIVE_MARKER,
                    fetchedAt = NOW,
                )
            },
            acknowledgeActionItem = AcknowledgeActionItem { command ->
                AcknowledgeActionItemResult.Acknowledged(
                    actionItemId = command.actionItemId,
                    requestedVersion = command.activityVersion,
                    acknowledgedAt = NOW,
                )
            },
        ),
        refreshRuns = RefreshRunApiV1Dependencies(
            startRefreshRun = StartRefreshRun { command ->
                startRefreshCommands += command
                StartRefreshRunResult.WorkspaceNotConfigured
            },
            getRefreshRun = GetRefreshRun { id -> GetRefreshRunResult.RefreshRunUnavailable(id) },
        ),
        configuration = ConfigurationApiV1Dependencies(
            getWorkspaceConfiguration = GetWorkspaceConfiguration {
                GetWorkspaceConfigurationResult.WorkspaceNotConfigured
            },
            configureWorkspace = ConfigureWorkspace { ConfigureWorkspaceResult.WorkspaceNotFound },
            addRepository = AddRepository { AddRepositoryResult.WorkspaceNotConfigured },
            removeRepository = RemoveRepository { command ->
                RemoveRepositoryResult.RepositoryNotConfigured(command.repositoryId)
            },
        ),
        getHealthSnapshot = GetHealthSnapshot {
            healthFailure?.let { throw it }
            healthSnapshot(healthStatuses.removeFirstOrNull() ?: HealthStatus.HEALTHY)
        },
    )

    private fun healthSnapshot(status: HealthStatus) = HealthSnapshot(
        status = status,
        serviceVersion = "0.1.0-acceptance",
        supportedApiVersion = "1",
        serviceInstanceId = "svc_acceptance",
        startedAt = NOW,
        components = listOf(
            HealthComponentSnapshot(HealthComponent.PERSISTENCE, status, "PERSISTENCE_SAFE"),
            HealthComponentSnapshot(HealthComponent.SCHEDULER, status, "SCHEDULER_SAFE"),
            HealthComponentSnapshot(HealthComponent.INSTALLATION_PATH, status, "INSTALLATION_SAFE"),
            HealthComponentSnapshot(HealthComponent.NOTIFICATION_ADAPTER, status, "NOTIFICATION_SAFE"),
        ),
    )

    private suspend fun withRunningServers(
        dependencies: LocalApiServerDependencies,
        backendEventRecorder: BackendEventRecorder = BackendEventRecorder.NONE,
        monotonicTimeSource: MonotonicTimeSource = MonotonicTimeSource.SYSTEM,
        block: suspend (LocalApiServers, Path) -> Unit,
    ) {
        val parent = secureTemporaryDirectory("bbh-acceptance-")
        val socketPath = parent.resolve("api.sock")
        val servers = startServers(socketPath, dependencies, backendEventRecorder, monotonicTimeSource)
        try {
            block(servers, socketPath)
        } finally {
            servers.close()
            Files.deleteIfExists(lifecycleLockPath(socketPath))
            Files.deleteIfExists(parent)
        }
    }

    private fun startServers(
        socketPath: Path,
        dependencies: LocalApiServerDependencies,
        backendEventRecorder: BackendEventRecorder = BackendEventRecorder.NONE,
        monotonicTimeSource: MonotonicTimeSource = MonotonicTimeSource.SYSTEM,
    ): LocalApiServers = LocalApiServers.start(
        LocalApiServerConfiguration(host = "127.0.0.1", port = 0, socketPath = socketPath),
        dependencies,
        backendEventRecorder = backendEventRecorder,
        monotonicTimeSource = monotonicTimeSource,
    )

    private suspend fun HttpClient.execute(
        routeCase: RouteCase,
        logicalBase: String,
        csrfToken: String? = null,
        socketPath: Path? = null,
    ): HttpResponse = request("$logicalBase${routeCase.path}") {
        method = routeCase.method
        if (socketPath != null) unixSocket(socketPath.toString())
        if (routeCase.method != HttpMethod.Get) {
            contentType(ContentType.Application.Json)
            if (csrfToken != null) {
                header(HttpHeaders.Origin, logicalBase)
                header(CSRF_HEADER, csrfToken)
            }
        }
        routeCase.body?.let(::setBody)
    }

    private suspend fun assertSuccessfulResult(response: HttpResponse, type: String, clue: String) {
        assertEquals(HttpStatusCode.OK, response.status, clue)
        assertEquals("application/json", response.headers[HttpHeaders.ContentType]?.substringBefore(';'), clue)
        assertEquals("no-store", response.headers[HttpHeaders.CacheControl], clue)
        assertEquals("1", response.document().string("apiVersion"), clue)
        assertTrue(response.document().string("requestId").matches(REQUEST_ID_PATTERN), clue)
        assertEquals(type, response.result().string("type"), clue)
    }

    private suspend fun assertRequestError(response: HttpResponse, status: HttpStatusCode, code: String) {
        assertEquals(status, response.status, code)
        assertEquals("application/json", response.headers[HttpHeaders.ContentType]?.substringBefore(';'), code)
        assertEquals("no-store", response.headers[HttpHeaders.CacheControl], code)
        assertEquals("1", response.document().string("apiVersion"), code)
        assertTrue(response.document().string("requestId").matches(REQUEST_ID_PATTERN), code)
        assertEquals(code, response.document().getValue("error").jsonObject.string("code"), code)
    }

    private suspend fun HttpResponse.document(): JsonObject = Json.parseToJsonElement(bodyAsText()).jsonObject
    private suspend fun HttpResponse.result(): JsonObject = document().getValue("result").jsonObject
    private fun JsonObject.string(name: String): String = getValue(name).jsonPrimitive.content

    private fun HttpResponse.hasCorsHeaders(): Boolean = listOf(
        HttpHeaders.AccessControlAllowOrigin,
        HttpHeaders.AccessControlAllowHeaders,
        HttpHeaders.AccessControlAllowMethods,
        HttpHeaders.AccessControlAllowCredentials,
    ).any(headers::contains)

    private fun assertNoGeneratedTypesCrossApplicationSeams() {
        val bitbucketSignatures = BitbucketGateway::class.java.declaredMethods.flatMap { method ->
            listOf(method.genericReturnType.typeName) + method.genericParameterTypes.map { it.typeName }
        }
        assertTrue(bitbucketSignatures.isNotEmpty())
        assertTrue(bitbucketSignatures.none { it.contains(BITBUCKET_GENERATED_PACKAGE) })

        val fakeUseCasePorts = listOf(
            GetDashboardSnapshot::class.java,
            ListPullRequests::class.java,
            GetPullRequest::class.java,
            GetInbox::class.java,
            GetSynchronizationStatus::class.java,
            GetLiveActivityContent::class.java,
            AcknowledgeActionItem::class.java,
            StartRefreshRun::class.java,
            GetRefreshRun::class.java,
            GetWorkspaceConfiguration::class.java,
            ConfigureWorkspace::class.java,
            AddRepository::class.java,
            RemoveRepository::class.java,
            GetHealthSnapshot::class.java,
        )
        val fakeUseCaseSignatures = fakeUseCasePorts.flatMap { type ->
            type.declaredMethods.flatMap { method ->
                listOf(method.genericReturnType.typeName) + method.genericParameterTypes.map { it.typeName }
            }
        }
        assertTrue(fakeUseCaseSignatures.isNotEmpty())
        assertTrue(fakeUseCaseSignatures.none { it.contains(PRODUCT_GENERATED_PACKAGE) })
    }

    private suspend fun <T> captureProcessOutput(block: suspend () -> T): CapturedOutput = synchronized(LOG_CAPTURE_LOCK) {
        val originalOut = System.out
        val originalErr = System.err
        val stdoutBytes = ByteArrayOutputStream()
        val stderrBytes = ByteArrayOutputStream()
        val stdoutStream = PrintStream(stdoutBytes, true, UTF_8)
        val stderrStream = PrintStream(stderrBytes, true, UTF_8)
        val rootLogger = Logger.getLogger("")
        val jul = StringBuilder()
        val formatter = object : Formatter() {
            override fun format(record: LogRecord): String = buildString {
                append(formatMessage(record)).append('\n')
                record.thrown?.let { append(it.stackTraceToString()).append('\n') }
            }
        }
        val handler = object : Handler() {
            override fun publish(record: LogRecord) {
                jul.append(formatter.format(record))
            }

            override fun flush() = Unit
            override fun close() = Unit
        }
        handler.level = Level.ALL
        rootLogger.addHandler(handler)
        System.setOut(stdoutStream)
        System.setErr(stderrStream)
        try {
            runBlocking { block() }
            stdoutStream.flush()
            stderrStream.flush()
            CapturedOutput(
                stdout = stdoutBytes.toString(UTF_8),
                stderr = stderrBytes.toString(UTF_8),
                jul = jul.toString(),
            )
        } finally {
            System.setOut(originalOut)
            System.setErr(originalErr)
            rootLogger.removeHandler(handler)
            stdoutStream.close()
            stderrStream.close()
        }
    }

    private fun secureTemporaryDirectory(prefix: String): Path =
        Files.createTempDirectory(Paths.get("/tmp"), prefix).also { directory ->
            Files.setPosixFilePermissions(directory, OWNER_DIRECTORY_PERMISSIONS)
        }

    private fun lifecycleLockPath(socketPath: Path): Path = socketPath.resolveSibling(".${socketPath.fileName}.lock")

    private fun fixture(name: String): String =
        requireNotNull(javaClass.getResource("/bitbucket/v1/$name")).readText()

    private fun withFakeBitbucket(handler: (HttpExchange) -> Unit): FakeBitbucketServer {
        val executor = Executors.newCachedThreadPool()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.executor = executor
        server.createContext("/") { exchange ->
            try {
                handler(exchange)
            } finally {
                exchange.close()
            }
        }
        server.start()
        return FakeBitbucketServer(server, executor)
    }

    private fun HttpExchange.respond(status: Int, body: String) {
        responseHeaders.add("Content-Type", "application/json")
        val bytes = body.toByteArray(UTF_8)
        sendResponseHeaders(status, bytes.size.toLong())
        responseBody.use { it.write(bytes) }
    }

    private class FakeBitbucketServer(
        private val server: HttpServer,
        private val executor: java.util.concurrent.ExecutorService,
    ) : AutoCloseable {
        val baseUrl: URI = URI("http://127.0.0.1:${server.address.port}")

        override fun close() {
            server.stop(0)
            executor.shutdownNow()
        }
    }

    private class AcceptanceState(
        val healthStatuses: ArrayDeque<HealthStatus> = ArrayDeque(),
        val healthFailure: RuntimeException? = null,
    ) {
        val startRefreshCommands = mutableListOf<StartRefreshRunCommand>()
    }

    private data class CapturedOutput(
        val stdout: String,
        val stderr: String,
        val jul: String,
    ) {
        val surfaces: List<String> get() = listOf(stdout, stderr, jul)
    }

    private data class RouteCase(
        val method: HttpMethod,
        val path: String,
        val expectedType: String,
        val body: String? = null,
    )

    private companion object {
        const val CSRF_HEADER = "X-CSRF-Token"
        const val SENSITIVE_MARKER = "sentinel-live-content-credential"
        const val STDOUT_CAPTURE_CANARY = "acceptance-stdout-capture-canary"
        const val STDERR_CAPTURE_CANARY = "acceptance-stderr-capture-canary"
        const val LOGGER_NAME = "com.mindtable.bitbuckethelper.acceptance.capture"
        const val JUL_CAPTURE_TEMPLATE = "acceptance JUL capture {0}"
        const val JUL_CAPTURE_PARAMETER = "parameter-canary"
        const val JUL_CAPTURE_FORMATTED = "acceptance JUL capture parameter-canary"
        const val BITBUCKET_USER_ID = "{11111111-1111-1111-1111-111111111111}"
        const val BITBUCKET_GENERATED_PACKAGE = "com.mindtable.bitbuckethelper.adapter.outbound.bitbucket.generated"
        const val PRODUCT_GENERATED_PACKAGE = "com.mindtable.bitbuckethelper.generated.api.v1.model"
        val NOW: Instant = Instant.parse("2026-08-17T07:00:00Z")
        val REPOSITORY_A = RepositoryId("repo_33333333-3333-3333-3333-333333333333")
        val REPOSITORY_B = RepositoryId("repo_44444444-4444-4444-4444-444444444444")
        val REQUEST_ID_PATTERN = Regex("^req_[A-Za-z0-9_-]+$")
        val LOG_CAPTURE_LOCK = Any()
        val OWNER_DIRECTORY_PERMISSIONS = setOf(
            PosixFilePermission.OWNER_READ,
            PosixFilePermission.OWNER_WRITE,
            PosixFilePermission.OWNER_EXECUTE,
        )
        val OWNER_SOCKET_PERMISSIONS = setOf(
            PosixFilePermission.OWNER_READ,
            PosixFilePermission.OWNER_WRITE,
        )
        val ROUTE_MATRIX = listOf(
            RouteCase(HttpMethod.Get, "/api/v1/dashboard", "workspaceNotConfigured"),
            RouteCase(HttpMethod.Get, "/api/v1/pull-requests", "workspaceNotConfigured"),
            RouteCase(HttpMethod.Get, "/api/v1/pull-requests/pr_acceptance", "pullRequestNotFound"),
            RouteCase(HttpMethod.Get, "/api/v1/inbox", "workspaceNotConfigured"),
            RouteCase(HttpMethod.Get, "/api/v1/synchronization", "workspaceNotConfigured"),
            RouteCase(
                HttpMethod.Get,
                "/api/v1/action-items/ai_acceptance/content?activityVersion=av_acceptance",
                "contentAvailable",
            ),
            RouteCase(
                HttpMethod.Put,
                "/api/v1/action-items/ai_acceptance/acknowledgment",
                "acknowledged",
                """{"apiVersion":"1","activityVersion":"av_acceptance"}""",
            ),
            RouteCase(
                HttpMethod.Post,
                "/api/v1/refresh-runs",
                "workspaceNotConfigured",
                """{"apiVersion":"1","target":{"type":"repositories","repositoryIds":["${REPOSITORY_A.value}","${REPOSITORY_B.value}"]}}""",
            ),
            RouteCase(HttpMethod.Get, "/api/v1/refresh-runs/rr_acceptance", "refreshRunUnavailable"),
            RouteCase(HttpMethod.Get, "/api/v1/configuration/workspace", "workspaceNotConfigured"),
            RouteCase(
                HttpMethod.Put,
                "/api/v1/configuration/workspace",
                "workspaceNotFound",
                """{"apiVersion":"1","bitbucketApiBaseUrl":"https://api.bitbucket.org/2.0","workspaceSlug":"acme"}""",
            ),
            RouteCase(
                HttpMethod.Post,
                "/api/v1/configuration/workspace/repositories",
                "workspaceNotConfigured",
                """{"apiVersion":"1","repositorySlug":"release-tools"}""",
            ),
            RouteCase(
                HttpMethod.Delete,
                "/api/v1/configuration/workspace/repositories/${REPOSITORY_A.value}",
                "repositoryNotConfigured",
            ),
            RouteCase(HttpMethod.Get, "/api/v1/health", "healthSnapshot"),
        )
    }
}

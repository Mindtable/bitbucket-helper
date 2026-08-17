package com.mindtable.bitbuckethelper.adapter.inbound.http

import com.mindtable.bitbuckethelper.application.model.AcknowledgeActionItemResult
import com.mindtable.bitbuckethelper.application.model.AddRepositoryResult
import com.mindtable.bitbuckethelper.application.model.ConfigureWorkspaceResult
import com.mindtable.bitbuckethelper.application.model.DashboardResult
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
import com.mindtable.bitbuckethelper.application.model.RemoveRepositoryResult
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
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.get
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
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.channels.ServerSocketChannel
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.PosixFilePermission
import java.time.Instant
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class LocalTransportParityTest {
    @Test
    fun `loopback and Unix expose the complete same business route matrix`() = runBlocking {
        withRunningServers { servers, socketPath ->
            HttpClient(CIO).use { client ->
                val browserBase = "http://127.0.0.1:${servers.browserPort}"
                val browserSessionResponse = client.get("$browserBase/api/v1/browser-session")
                val browserSession = Json.parseToJsonElement(browserSessionResponse.bodyAsText()).jsonObject
                    .getValue("result").jsonObject
                val csrfToken = browserSession.getValue("csrfToken").jsonPrimitive.content

                for (routeCase in ROUTE_MATRIX) {
                    val browser = client.executeRouteCase(
                        routeCase = routeCase,
                        logicalBase = browserBase,
                        csrfToken = csrfToken,
                    )
                    val unix = client.executeRouteCase(
                        routeCase = routeCase,
                        logicalBase = "http://untrusted.invalid",
                        socketPath = socketPath,
                    )

                    assertEquals(HttpStatusCode.OK, browser.status, routeCase.path)
                    assertEquals(HttpStatusCode.OK, unix.status, routeCase.path)
                    assertEquals("application/json", browser.headers[HttpHeaders.ContentType]?.substringBefore(';'))
                    assertEquals("application/json", unix.headers[HttpHeaders.ContentType]?.substringBefore(';'))
                    assertEquals("no-store", browser.headers[HttpHeaders.CacheControl])
                    assertEquals("no-store", unix.headers[HttpHeaders.CacheControl])
                    assertEquals(routeCase.expectedType, browser.resultType())
                    assertEquals(routeCase.expectedType, unix.resultType())
                    assertFalse(browser.headers.contains(HttpHeaders.AccessControlAllowOrigin))
                    assertFalse(unix.headers.contains(HttpHeaders.AccessControlAllowOrigin))
                }
            }
        }
    }

    @Test
    fun `Unix has no browser session and ignores browser transport headers`() = runBlocking {
        withRunningServers { _, socketPath ->
            HttpClient(CIO).use { client ->
                val response = client.get("http://untrusted.invalid/api/v1/browser-session") {
                    unixSocket(socketPath.toString())
                    header(HttpHeaders.Origin, "https://attacker.invalid")
                }
                val error = Json.parseToJsonElement(response.bodyAsText()).jsonObject
                    .getValue("error").jsonObject

                assertEquals(HttpStatusCode.NotFound, response.status)
                assertEquals("ROUTE_NOT_FOUND", error.getValue("code").jsonPrimitive.content)
                assertEquals("no-store", response.headers[HttpHeaders.CacheControl])
            }
        }
    }

    @Test
    fun `socket starts as owner read-write and close is idempotent and removes it`() {
        val parent = secureTemporaryDirectory("bbh-socket-mode-")
        val socketPath = parent.resolve("api.sock")
        val servers = startServers(socketPath)
        try {
            assertTrue(
                Files.readAttributes(socketPath, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS).isOther,
            )
            assertEquals(OWNER_SOCKET_PERMISSIONS, Files.getPosixFilePermissions(socketPath))
        } finally {
            servers.close()
            servers.close()
        }

        assertFalse(Files.exists(socketPath))
        Files.delete(parent)
    }

    @Test
    fun `startup removes an owned stale Unix socket and binds the configured target`() {
        val parent = secureTemporaryDirectory("bbh-stale-socket-")
        val socketPath = parent.resolve("api.sock")
        ServerSocketChannel.open(StandardProtocolFamily.UNIX).use { channel ->
            channel.bind(UnixDomainSocketAddress.of(socketPath))
        }
        assertTrue(Files.exists(socketPath))

        val servers = startServers(socketPath)
        try {
            assertEquals(OWNER_SOCKET_PERMISSIONS, Files.getPosixFilePermissions(socketPath))
        } finally {
            servers.close()
            Files.delete(parent)
        }
    }

    @Test
    fun `startup fails closed for permissive parent and non-socket target`() {
        val permissiveParent = Files.createTempDirectory(Paths.get("/tmp"), "bbh-permissive-")
        Files.setPosixFilePermissions(permissiveParent, WORLD_READABLE_DIRECTORY_PERMISSIONS)
        val rejectedSocket = permissiveParent.resolve("api.sock")

        assertThrows(IllegalStateException::class.java) { startServers(rejectedSocket) }
        assertFalse(Files.exists(rejectedSocket))
        Files.delete(permissiveParent)

        val secureParent = secureTemporaryDirectory("bbh-wrong-type-")
        val regularTarget = secureParent.resolve("api.sock")
        Files.writeString(regularTarget, "sentinel-do-not-delete")

        assertThrows(IllegalStateException::class.java) { startServers(regularTarget) }
        assertEquals("sentinel-do-not-delete", Files.readString(regularTarget))
        Files.delete(regularTarget)
        Files.delete(secureParent)
    }

    @Test
    fun `startup never removes an active Unix socket`() {
        val parent = secureTemporaryDirectory("bbh-active-socket-")
        val socketPath = parent.resolve("api.sock")
        ServerSocketChannel.open(StandardProtocolFamily.UNIX).use { activeServer ->
            activeServer.bind(UnixDomainSocketAddress.of(socketPath))

            assertThrows(IllegalStateException::class.java) { startServers(socketPath) }
            assertTrue(Files.exists(socketPath))
        }
        Files.deleteIfExists(socketPath)
        Files.delete(parent)
    }

    private suspend fun withRunningServers(
        block: suspend (LocalApiServers, Path) -> Unit,
    ) {
        val parent = secureTemporaryDirectory("bbh-parity-")
        val socketPath = parent.resolve("api.sock")
        val servers = startServers(socketPath)
        try {
            block(servers, socketPath)
        } finally {
            servers.close()
            Files.deleteIfExists(parent)
        }
    }

    private fun startServers(socketPath: Path): LocalApiServers = LocalApiServers.start(
        configuration = LocalApiServerConfiguration(
            host = "127.0.0.1",
            port = 0,
            socketPath = socketPath,
        ),
        dependencies = fakeDependencies(),
    )

    private fun secureTemporaryDirectory(prefix: String): Path =
        Files.createTempDirectory(Paths.get("/tmp"), prefix).also { directory ->
            Files.setPosixFilePermissions(directory, OWNER_DIRECTORY_PERMISSIONS)
        }

    private suspend fun HttpClient.executeRouteCase(
        routeCase: RouteCase,
        logicalBase: String,
        csrfToken: String? = null,
        socketPath: Path? = null,
    ) = request("$logicalBase${routeCase.path}") {
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

    private suspend fun io.ktor.client.statement.HttpResponse.resultType(): String =
        Json.parseToJsonElement(bodyAsText()).jsonObject
            .getValue("result").jsonObject
            .getValue("type").jsonPrimitive.content

    private fun fakeDependencies() = LocalApiServerDependencies(
        read = ReadApiV1Dependencies(
            getDashboardSnapshot = GetDashboardSnapshot { DashboardResult.WorkspaceNotConfigured },
            listPullRequests = ListPullRequests { ListPullRequestsResult.WorkspaceNotConfigured },
            getPullRequest = GetPullRequest { GetPullRequestResult.WorkspaceNotConfigured },
            getInbox = GetInbox { GetInboxResult.WorkspaceNotConfigured },
            getSynchronizationStatus = GetSynchronizationStatus {
                GetSynchronizationStatusResult.WorkspaceNotConfigured
            },
        ),
        actionItems = ActionItemApiV1Dependencies(
            getLiveActivityContent = GetLiveActivityContent { command ->
                LiveActivityContentResult.ActionItemNotFound(command.actionItemId, command.activityVersion)
            },
            acknowledgeActionItem = AcknowledgeActionItem { command ->
                AcknowledgeActionItemResult.AcknowledgmentRejected(command.actionItemId, command.activityVersion)
            },
        ),
        refreshRuns = RefreshRunApiV1Dependencies(
            startRefreshRun = StartRefreshRun { StartRefreshRunResult.WorkspaceNotConfigured },
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
        getHealthSnapshot = GetHealthSnapshot { healthSnapshot() },
    )

    private fun healthSnapshot() = HealthSnapshot(
        status = HealthStatus.HEALTHY,
        serviceVersion = "0.1.0-test",
        supportedApiVersion = "1",
        serviceInstanceId = "svc_parity-test",
        startedAt = Instant.parse("2026-08-17T07:00:00Z"),
        components = listOf(
            HealthComponentSnapshot(HealthComponent.PERSISTENCE, HealthStatus.HEALTHY, "READY"),
            HealthComponentSnapshot(HealthComponent.SCHEDULER, HealthStatus.HEALTHY, "RUNNING"),
            HealthComponentSnapshot(HealthComponent.INSTALLATION_PATH, HealthStatus.HEALTHY, "VALID"),
            HealthComponentSnapshot(HealthComponent.NOTIFICATION_ADAPTER, HealthStatus.HEALTHY, "AVAILABLE"),
        ),
    )

    private data class RouteCase(
        val method: HttpMethod,
        val path: String,
        val expectedType: String,
        val body: String? = null,
    )

    private companion object {
        const val CSRF_HEADER = "X-CSRF-Token"
        val OWNER_DIRECTORY_PERMISSIONS = setOf(
            PosixFilePermission.OWNER_READ,
            PosixFilePermission.OWNER_WRITE,
            PosixFilePermission.OWNER_EXECUTE,
        )
        val OWNER_SOCKET_PERMISSIONS = setOf(
            PosixFilePermission.OWNER_READ,
            PosixFilePermission.OWNER_WRITE,
        )
        val WORLD_READABLE_DIRECTORY_PERMISSIONS = OWNER_DIRECTORY_PERMISSIONS + setOf(
            PosixFilePermission.GROUP_READ,
            PosixFilePermission.GROUP_EXECUTE,
            PosixFilePermission.OTHERS_READ,
            PosixFilePermission.OTHERS_EXECUTE,
        )
        val ROUTE_MATRIX = listOf(
            RouteCase(HttpMethod.Get, "/api/v1/dashboard", "workspaceNotConfigured"),
            RouteCase(HttpMethod.Get, "/api/v1/pull-requests", "workspaceNotConfigured"),
            RouteCase(HttpMethod.Get, "/api/v1/pull-requests/pr_parity", "workspaceNotConfigured"),
            RouteCase(HttpMethod.Get, "/api/v1/inbox", "workspaceNotConfigured"),
            RouteCase(HttpMethod.Get, "/api/v1/synchronization", "workspaceNotConfigured"),
            RouteCase(
                HttpMethod.Get,
                "/api/v1/action-items/ai_parity/content?activityVersion=av_parity",
                "actionItemNotFound",
            ),
            RouteCase(
                HttpMethod.Put,
                "/api/v1/action-items/ai_parity/acknowledgment",
                "acknowledgmentRejected",
                """{"apiVersion":"1","activityVersion":"av_parity"}""",
            ),
            RouteCase(
                HttpMethod.Post,
                "/api/v1/refresh-runs",
                "workspaceNotConfigured",
                """{"apiVersion":"1","target":{"type":"allConfiguredRepositories"}}""",
            ),
            RouteCase(HttpMethod.Get, "/api/v1/refresh-runs/rr_parity", "refreshRunUnavailable"),
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
                """{"apiVersion":"1","repositorySlug":"tools"}""",
            ),
            RouteCase(
                HttpMethod.Delete,
                "/api/v1/configuration/workspace/repositories/repo_parity",
                "repositoryNotConfigured",
            ),
            RouteCase(HttpMethod.Get, "/api/v1/health", "healthSnapshot"),
        )
    }
}

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
import io.ktor.http.withCharset
import java.io.IOException
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.channels.ServerSocketChannel
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.PosixFilePermission
import java.time.Instant
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
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
    fun `browser root serves the SPA while Unix root remains an empty transport 404`() = runBlocking {
        withRunningServers { servers, socketPath ->
            HttpClient(CIO).use { client ->
                val browser = client.get("http://127.0.0.1:${servers.browserPort}/")
                val unix = client.get("http://untrusted.invalid/") {
                    unixSocket(socketPath.toString())
                }

                assertEquals(HttpStatusCode.OK, browser.status)
                assertEquals(PARITY_SHELL, browser.bodyAsText())
                assertEquals(ContentType.Text.Html.withCharset(Charsets.UTF_8), browser.contentType())
                assertEquals(HttpStatusCode.NotFound, unix.status)
                assertEquals("", unix.bodyAsText())
            }
        }
    }

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
        assertTrue(Files.isRegularFile(lifecycleLockPath(socketPath), LinkOption.NOFOLLOW_LINKS))
        assertEquals(OWNER_SOCKET_PERMISSIONS, Files.getPosixFilePermissions(lifecycleLockPath(socketPath)))
        deleteLifecycleLock(socketPath)
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
            deleteLifecycleLock(socketPath)
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
        deleteLifecycleLock(regularTarget)
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
        deleteLifecycleLock(socketPath)
        Files.delete(parent)
    }

    @Test
    fun `pre-bind replacement is rejected without removing the contender socket`() {
        val parent = secureTemporaryDirectory("bbh-failed-bind-")
        val socketPath = parent.resolve("api.sock")
        lateinit var contender: ServerSocketChannel
        val hooks = TestFileSystemHooks(
            actions = mapOf(
                LocalApiServerFileSystemEvent.BEFORE_UNIX_BIND to {
                    contender = ServerSocketChannel.open(StandardProtocolFamily.UNIX)
                    contender.bind(UnixDomainSocketAddress.of(socketPath))
                },
            ),
        )

        assertThrows(IllegalStateException::class.java) { startServers(socketPath, hooks) }
        assertTrue(Files.exists(socketPath, LinkOption.NOFOLLOW_LINKS))

        contender.close()
        Files.delete(socketPath)
        deleteLifecycleLock(socketPath)
        Files.delete(parent)
    }

    @Test
    fun `post-start failure before identity capture leaves a replacement socket untouched`() {
        val parent = secureTemporaryDirectory("bbh-post-start-failure-")
        val socketPath = parent.resolve("api.sock")
        val movedBoundSocketPath = parent.resolve("moved-bound.sock")
        lateinit var replacement: ServerSocketChannel
        val hooks = TestFileSystemHooks(
            actions = mapOf(
                LocalApiServerFileSystemEvent.AFTER_UNIX_START_BEFORE_IDENTITY_CAPTURE to {
                    Files.move(socketPath, movedBoundSocketPath)
                    replacement = ServerSocketChannel.open(StandardProtocolFamily.UNIX)
                    replacement.bind(UnixDomainSocketAddress.of(socketPath))
                    throw IOException("simulated failure before identity capture")
                },
            ),
        )

        assertThrows(IllegalStateException::class.java) { startServers(socketPath, hooks) }
        assertTrue(Files.exists(socketPath, LinkOption.NOFOLLOW_LINKS))
        assertTrue(Files.exists(movedBoundSocketPath, LinkOption.NOFOLLOW_LINKS))

        replacement.close()
        Files.delete(socketPath)
        Files.delete(movedBoundSocketPath)
        deleteLifecycleLock(socketPath)
        Files.delete(parent)
    }

    @Test
    fun `second cooperative process fails on lifecycle lock before stale cleanup or bind`() {
        val parent = secureTemporaryDirectory("bbh-lock-contention-")
        val socketPath = parent.resolve("api.sock")
        val lockAcquired = CountDownLatch(1)
        val releaseFirst = CountDownLatch(1)
        val hooks = TestFileSystemHooks(
            actions = mapOf(
                LocalApiServerFileSystemEvent.LIFECYCLE_LOCK_ACQUIRED to {
                    lockAcquired.countDown()
                    check(releaseFirst.await(10, TimeUnit.SECONDS))
                },
            ),
        )
        val firstFuture = CompletableFuture.supplyAsync { startServers(socketPath, hooks) }
        try {
            assertTrue(lockAcquired.await(10, TimeUnit.SECONDS))
            assertThrows(IllegalStateException::class.java) { startServers(socketPath) }
            assertFalse(Files.exists(socketPath, LinkOption.NOFOLLOW_LINKS))
        } finally {
            releaseFirst.countDown()
            val first = firstFuture.get(10, TimeUnit.SECONDS)
            first.close()
        }

        deleteLifecycleLock(socketPath)
        Files.delete(parent)
    }

    @Test
    fun `close leaves a same-user replacement socket untouched and reports identity loss`() {
        val parent = secureTemporaryDirectory("bbh-close-replacement-")
        val socketPath = parent.resolve("api.sock")
        val servers = startServers(socketPath)
        Files.delete(socketPath)
        ServerSocketChannel.open(StandardProtocolFamily.UNIX).use { replacement ->
            replacement.bind(UnixDomainSocketAddress.of(socketPath))

            assertThrows(IllegalStateException::class.java) { servers.close() }
            assertTrue(Files.exists(socketPath, LinkOption.NOFOLLOW_LINKS))
        }

        Files.delete(socketPath)
        deleteLifecycleLock(socketPath)
        Files.delete(parent)
    }

    @Test
    fun `stale cleanup detects replacement after probe and never deletes it`() {
        val parent = secureTemporaryDirectory("bbh-stale-replacement-")
        val socketPath = parent.resolve("api.sock")
        ServerSocketChannel.open(StandardProtocolFamily.UNIX).use { stale ->
            stale.bind(UnixDomainSocketAddress.of(socketPath))
        }
        lateinit var replacement: ServerSocketChannel
        val hooks = TestFileSystemHooks(
            actions = mapOf(
                LocalApiServerFileSystemEvent.STALE_SOCKET_PROBED to {
                    Files.delete(socketPath)
                    replacement = ServerSocketChannel.open(StandardProtocolFamily.UNIX)
                    replacement.bind(UnixDomainSocketAddress.of(socketPath))
                },
            ),
        )

        assertThrows(IllegalStateException::class.java) { startServers(socketPath, hooks) }
        assertTrue(Files.exists(socketPath, LinkOption.NOFOLLOW_LINKS))

        replacement.close()
        Files.delete(socketPath)
        deleteLifecycleLock(socketPath)
        Files.delete(parent)
    }

    @Test
    fun `permission hardening never follows a replacement symlink`() {
        val parent = secureTemporaryDirectory("bbh-chmod-symlink-")
        val socketPath = parent.resolve("api.sock")
        val sentinel = parent.resolve("sentinel.txt")
        Files.writeString(sentinel, "sentinel-do-not-chmod")
        Files.setPosixFilePermissions(sentinel, OWNER_READ_ONLY_PERMISSIONS)
        val hooks = TestFileSystemHooks(
            actions = mapOf(
                LocalApiServerFileSystemEvent.BOUND_SOCKET_IDENTITY_CAPTURED to {
                    Files.delete(socketPath)
                    Files.createSymbolicLink(socketPath, sentinel.fileName)
                },
            ),
        )

        assertThrows(IllegalStateException::class.java) { startServers(socketPath, hooks) }
        assertTrue(Files.isSymbolicLink(socketPath))
        assertEquals(OWNER_READ_ONLY_PERMISSIONS, Files.getPosixFilePermissions(sentinel))

        Files.delete(socketPath)
        Files.delete(sentinel)
        deleteLifecycleLock(socketPath)
        Files.delete(parent)
    }

    @Test
    fun `parent replacement is detected before socket preparation continues`() {
        val parent = secureTemporaryDirectory("bbh-parent-replacement-")
        val movedParent = parent.resolveSibling("${parent.fileName}-moved")
        val socketPath = parent.resolve("api.sock")
        val hooks = TestFileSystemHooks(
            actions = mapOf(
                LocalApiServerFileSystemEvent.PARENT_IDENTITY_CAPTURED to {
                    Files.move(parent, movedParent)
                    Files.createDirectory(parent)
                    Files.setPosixFilePermissions(parent, OWNER_DIRECTORY_PERMISSIONS)
                },
            ),
        )

        assertThrows(IllegalStateException::class.java) { startServers(socketPath, hooks) }
        assertFalse(Files.exists(socketPath, LinkOption.NOFOLLOW_LINKS))
        assertFalse(Files.exists(movedParent.resolve("api.sock"), LinkOption.NOFOLLOW_LINKS))

        Files.delete(parent)
        Files.delete(movedParent)
    }

    @Test
    fun `symlink socket target and symlink parent both fail closed without following`() {
        val parent = secureTemporaryDirectory("bbh-symlink-target-")
        val socketPath = parent.resolve("api.sock")
        val sentinel = parent.resolve("sentinel.txt")
        Files.writeString(sentinel, "sentinel-do-not-touch")
        Files.createSymbolicLink(socketPath, sentinel.fileName)

        assertThrows(IllegalStateException::class.java) { startServers(socketPath) }
        assertTrue(Files.isSymbolicLink(socketPath))
        assertEquals("sentinel-do-not-touch", Files.readString(sentinel))

        Files.delete(socketPath)
        Files.delete(sentinel)
        deleteLifecycleLock(socketPath)
        Files.delete(parent)

        val root = secureTemporaryDirectory("bbh-symlink-parent-")
        val realParent = root.resolve("real")
        val linkedParent = root.resolve("linked")
        Files.createDirectory(realParent)
        Files.setPosixFilePermissions(realParent, OWNER_DIRECTORY_PERMISSIONS)
        Files.createSymbolicLink(linkedParent, realParent.fileName)

        assertThrows(IllegalStateException::class.java) { startServers(linkedParent.resolve("api.sock")) }
        assertFalse(Files.exists(realParent.resolve("api.sock"), LinkOption.NOFOLLOW_LINKS))

        Files.delete(linkedParent)
        Files.delete(realParent)
        Files.delete(root)
    }

    @Test
    fun `simulated foreign-owned stale socket remains untouched`() {
        val parent = secureTemporaryDirectory("bbh-foreign-owner-")
        val socketPath = parent.resolve("api.sock")
        ServerSocketChannel.open(StandardProtocolFamily.UNIX).use { stale ->
            stale.bind(UnixDomainSocketAddress.of(socketPath))
        }
        val hooks = TestFileSystemHooks(
            ownerDecision = { path, actual -> if (path == socketPath) false else actual },
        )

        assertThrows(IllegalStateException::class.java) { startServers(socketPath, hooks) }
        assertTrue(Files.exists(socketPath, LinkOption.NOFOLLOW_LINKS))

        Files.delete(socketPath)
        deleteLifecycleLock(socketPath)
        Files.delete(parent)
    }

    @Test
    fun `chmod failure aborts startup and removes only the captured bound socket`() {
        val parent = secureTemporaryDirectory("bbh-chmod-failure-")
        val socketPath = parent.resolve("api.sock")
        val hooks = TestFileSystemHooks(
            actions = mapOf(
                LocalApiServerFileSystemEvent.BEFORE_SOCKET_CHMOD to {
                    throw IOException("simulated chmod failure")
                },
            ),
        )

        assertThrows(IllegalStateException::class.java) { startServers(socketPath, hooks) }
        assertFalse(Files.exists(socketPath, LinkOption.NOFOLLOW_LINKS))

        deleteLifecycleLock(socketPath)
        Files.delete(parent)
    }

    @Test
    fun `delete failure leaves the captured socket and close reports failure`() {
        val parent = secureTemporaryDirectory("bbh-delete-failure-")
        val socketPath = parent.resolve("api.sock")
        val hooks = TestFileSystemHooks(
            actions = mapOf(
                LocalApiServerFileSystemEvent.BEFORE_BOUND_SOCKET_DELETE to {
                    throw IOException("simulated delete failure")
                },
            ),
        )
        val servers = startServers(socketPath, hooks)

        assertThrows(IllegalStateException::class.java) { servers.close() }
        assertTrue(Files.exists(socketPath, LinkOption.NOFOLLOW_LINKS))

        Files.delete(socketPath)
        deleteLifecycleLock(socketPath)
        Files.delete(parent)
    }

    @Test
    fun `persistent lifecycle lock must be a same-user regular non-symlink 0600 file`() {
        val parent = secureTemporaryDirectory("bbh-unsafe-lock-")
        val socketPath = parent.resolve("api.sock")
        val lockPath = lifecycleLockPath(socketPath)
        val sentinel = parent.resolve("sentinel.txt")
        Files.writeString(sentinel, "sentinel-lock-target")
        Files.createSymbolicLink(lockPath, sentinel.fileName)

        assertThrows(IllegalStateException::class.java) { startServers(socketPath) }
        assertTrue(Files.isSymbolicLink(lockPath))
        assertFalse(Files.exists(socketPath, LinkOption.NOFOLLOW_LINKS))

        Files.delete(lockPath)
        Files.createFile(lockPath)
        Files.setPosixFilePermissions(lockPath, WORLD_READABLE_FILE_PERMISSIONS)
        assertThrows(IllegalStateException::class.java) { startServers(socketPath) }
        assertEquals(WORLD_READABLE_FILE_PERMISSIONS, Files.getPosixFilePermissions(lockPath))

        Files.delete(lockPath)
        Files.delete(sentinel)
        Files.delete(parent)
    }

    @Test
    fun `existing FIFO lifecycle lock is rejected before a write-only open can block`() {
        val parent = secureTemporaryDirectory("bbh-fifo-lock-")
        val socketPath = parent.resolve("api.sock")
        val lockPath = lifecycleLockPath(socketPath)
        val mkfifo = ProcessBuilder("/usr/bin/mkfifo", lockPath.toString()).start()
        assertEquals(0, mkfifo.waitFor())
        Files.setPosixFilePermissions(lockPath, OWNER_SOCKET_PERMISSIONS)
        val startFuture = CompletableFuture.supplyAsync<Throwable?> {
            try {
                startServers(socketPath).close()
                null
            } catch (failure: Throwable) {
                failure
            }
        }
        var timedOut = false
        var failure: Throwable? = null

        try {
            try {
                failure = startFuture.get(2, TimeUnit.SECONDS)
            } catch (_: TimeoutException) {
                timedOut = true
            }

            assertFalse(timedOut, "the FIFO must be rejected before a blocking write open")
            assertTrue(failure is IllegalStateException)
            assertTrue(
                Files.readAttributes(lockPath, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS).isOther,
            )
            assertFalse(Files.exists(socketPath, LinkOption.NOFOLLOW_LINKS))
        } finally {
            if (!startFuture.isDone) {
                Files.newByteChannel(
                    lockPath,
                    setOf(StandardOpenOption.READ, StandardOpenOption.WRITE),
                ).use { }
                startFuture.get(10, TimeUnit.SECONDS)
            }
            Files.deleteIfExists(socketPath)
            Files.delete(lockPath)
            Files.delete(parent)
        }
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
            deleteLifecycleLock(socketPath)
            Files.deleteIfExists(parent)
        }
    }

    private fun startServers(
        socketPath: Path,
        hooks: LocalApiServerFileSystemHooks? = null,
    ): LocalApiServers {
        val configuration = LocalApiServerConfiguration(
            host = "127.0.0.1",
            port = 0,
            socketPath = socketPath,
        )
        val dependencies = fakeDependencies()
        return LocalApiServers.start(
            configuration = configuration,
            dependencies = dependencies,
            fileSystemHooks = hooks ?: LocalApiServerFileSystemHooks.NONE,
            spaAssets = paritySpaAssets(),
        )
    }

    private fun paritySpaAssets() = SpaAssets(SpaResourceReader { resource ->
        if (resource == "spa/index.html") PARITY_SHELL.encodeToByteArray() else null
    })

    private fun lifecycleLockPath(socketPath: Path): Path =
        socketPath.resolveSibling(".${socketPath.fileName}.lock")

    private fun deleteLifecycleLock(socketPath: Path) {
        Files.deleteIfExists(lifecycleLockPath(socketPath))
    }

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

    private class TestFileSystemHooks(
        private val actions: Map<LocalApiServerFileSystemEvent, (Path) -> Unit> = emptyMap(),
        private val ownerDecision: (Path, Boolean) -> Boolean = { _, actual -> actual },
    ) : LocalApiServerFileSystemHooks {
        override fun onEvent(event: LocalApiServerFileSystemEvent, socketPath: Path) {
            actions[event]?.invoke(socketPath)
        }

        override fun isCurrentUser(path: Path, actualMatch: Boolean): Boolean =
            ownerDecision(path, actualMatch)
    }

    private companion object {
        const val PARITY_SHELL = "<!doctype html><div id=parity-spa></div>"
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
        val OWNER_READ_ONLY_PERMISSIONS = setOf(PosixFilePermission.OWNER_READ)
        val WORLD_READABLE_FILE_PERMISSIONS = setOf(
            PosixFilePermission.OWNER_READ,
            PosixFilePermission.OWNER_WRITE,
            PosixFilePermission.GROUP_READ,
            PosixFilePermission.OTHERS_READ,
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

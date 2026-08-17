package com.mindtable.bitbuckethelper.adapter.inbound.http

import com.mindtable.bitbuckethelper.application.port.inbound.GetHealthSnapshot
import io.ktor.server.application.Application
import io.ktor.server.cio.CIO
import io.ktor.server.cio.CIOApplicationEngine
import io.ktor.server.cio.unixConnector
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.routing.Route
import java.net.ConnectException
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.channels.SocketChannel
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.PosixFilePermissions
import java.nio.file.attribute.UserPrincipal
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking

data class LocalApiServerDependencies(
    val read: ReadApiV1Dependencies,
    val actionItems: ActionItemApiV1Dependencies,
    val refreshRuns: RefreshRunApiV1Dependencies,
    val configuration: ConfigurationApiV1Dependencies,
    val getHealthSnapshot: GetHealthSnapshot,
)

class LocalApiServers private constructor(
    private val browserServer: EmbeddedServer<CIOApplicationEngine, CIOApplicationEngine.Configuration>,
    private val unixServer: EmbeddedServer<CIOApplicationEngine, CIOApplicationEngine.Configuration>,
    private val socketPath: Path,
    val browserPort: Int,
) : AutoCloseable {
    private val lifecycleMonitor = Any()
    private var closed = false

    override fun close() {
        synchronized(lifecycleMonitor) {
            if (closed) return
            closed = true

            var failed = false
            try {
                browserServer.stop(gracePeriodMillis = STOP_GRACE_MILLIS, timeoutMillis = STOP_TIMEOUT_MILLIS)
            } catch (_: Exception) {
                failed = true
            }
            try {
                unixServer.stop(gracePeriodMillis = STOP_GRACE_MILLIS, timeoutMillis = STOP_TIMEOUT_MILLIS)
            } catch (_: Exception) {
                failed = true
            }
            try {
                removeOwnedSocketIfPresent(socketPath)
            } catch (_: Exception) {
                failed = true
            }
            check(!failed) { STOP_FAILURE_MESSAGE }
        }
    }

    companion object {
        fun start(
            configuration: LocalApiServerConfiguration,
            dependencies: LocalApiServerDependencies,
        ): LocalApiServers {
            prepareSocketTarget(configuration.socketPath)

            val resolvedBrowserPort = AtomicInteger(configuration.port)
            val browserSecurity = BrowserSecurity(resolvedBrowserPort::get)
            val unixServer = embeddedServer(CIO, configure = {
                unixConnector(configuration.socketPath.toString())
            }) {
                installBusinessApi(TransportKind.UNIX, dependencies)
            }
            val browserServer = embeddedServer(
                factory = CIO,
                host = configuration.host,
                port = configuration.port,
            ) {
                installBrowserSecurity(browserSecurity)
                installBusinessApi(TransportKind.BROWSER, dependencies, browserSecurity)
            }

            var unixStartAttempted = false
            var browserStartAttempted = false
            try {
                unixStartAttempted = true
                unixServer.start(wait = false)
                secureBoundSocket(configuration.socketPath)

                browserStartAttempted = true
                browserServer.start(wait = false)
                val port = runBlocking { browserServer.engine.resolvedConnectors().single().port }
                resolvedBrowserPort.set(port)
                return LocalApiServers(
                    browserServer = browserServer,
                    unixServer = unixServer,
                    socketPath = configuration.socketPath,
                    browserPort = port,
                )
            } catch (_: Exception) {
                if (browserStartAttempted) stopAfterFailedStart(browserServer)
                if (unixStartAttempted) stopAfterFailedStart(unixServer)
                runCatching { removeOwnedSocketIfPresent(configuration.socketPath) }
                throw IllegalStateException(START_FAILURE_MESSAGE)
            }
        }

        private fun prepareSocketTarget(socketPath: Path) {
            val parent = socketPath.parent
            val parentAttributes = readAttributes(parent)
            check(parentAttributes.isDirectory && !parentAttributes.isSymbolicLink) {
                PARENT_TYPE_FAILURE_MESSAGE
            }
            check(isOwnedByCurrentUser(parent)) { OWNER_FAILURE_MESSAGE }
            check(Files.getPosixFilePermissions(parent, LinkOption.NOFOLLOW_LINKS) == OWNER_DIRECTORY_PERMISSIONS) {
                PARENT_PERMISSION_FAILURE_MESSAGE
            }

            val target = readAttributesIfPresent(socketPath) ?: return
            check(target.isUnixSocket(socketPath)) { TARGET_TYPE_FAILURE_MESSAGE }
            check(isOwnedByCurrentUser(socketPath)) { OWNER_FAILURE_MESSAGE }
            removeStaleSocket(socketPath, target.fileKey())
        }

        private fun removeStaleSocket(socketPath: Path, expectedFileKey: Any?) {
            try {
                SocketChannel.open(StandardProtocolFamily.UNIX).use { channel ->
                    channel.connect(UnixDomainSocketAddress.of(socketPath))
                }
                throw IllegalStateException(ACTIVE_SOCKET_FAILURE_MESSAGE)
            } catch (_: ConnectException) {
                // A refused connection identifies a bound path with no live listener.
            }

            val current = readAttributes(socketPath)
            check(current.fileKey() == expectedFileKey) { TARGET_CHANGED_FAILURE_MESSAGE }
            check(current.isUnixSocket(socketPath)) { TARGET_TYPE_FAILURE_MESSAGE }
            check(isOwnedByCurrentUser(socketPath)) { OWNER_FAILURE_MESSAGE }
            Files.delete(socketPath)
        }

        private fun secureBoundSocket(socketPath: Path) {
            val attributes = readAttributes(socketPath)
            check(attributes.isUnixSocket(socketPath)) { TARGET_TYPE_FAILURE_MESSAGE }
            check(isOwnedByCurrentUser(socketPath)) { OWNER_FAILURE_MESSAGE }
            Files.setPosixFilePermissions(socketPath, OWNER_SOCKET_PERMISSIONS)
            check(Files.getPosixFilePermissions(socketPath, LinkOption.NOFOLLOW_LINKS) == OWNER_SOCKET_PERMISSIONS) {
                SOCKET_PERMISSION_FAILURE_MESSAGE
            }
        }

        private fun removeOwnedSocketIfPresent(socketPath: Path) {
            val attributes = readAttributesIfPresent(socketPath) ?: return
            check(attributes.isUnixSocket(socketPath)) { TARGET_TYPE_FAILURE_MESSAGE }
            check(isOwnedByCurrentUser(socketPath)) { OWNER_FAILURE_MESSAGE }
            Files.delete(socketPath)
        }

        private fun BasicFileAttributes.isUnixSocket(path: Path): Boolean =
            !isSymbolicLink && !isDirectory && !isRegularFile &&
                ((Files.getAttribute(path, "unix:mode", LinkOption.NOFOLLOW_LINKS) as Int) and FILE_TYPE_MASK) ==
                SOCKET_FILE_TYPE

        private fun readAttributes(path: Path): BasicFileAttributes =
            Files.readAttributes(path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)

        private fun readAttributesIfPresent(path: Path): BasicFileAttributes? = try {
            readAttributes(path)
        } catch (_: NoSuchFileException) {
            null
        }

        private fun isOwnedByCurrentUser(path: Path): Boolean =
            Files.getOwner(path, LinkOption.NOFOLLOW_LINKS).isCurrentUser()

        private fun UserPrincipal.isCurrentUser(): Boolean {
            val currentUser = System.getProperty("user.name")
            return name == currentUser || name.substringAfterLast('\\') == currentUser
        }

        private fun stopAfterFailedStart(
            server: EmbeddedServer<CIOApplicationEngine, CIOApplicationEngine.Configuration>,
        ) {
            runCatching {
                server.stop(gracePeriodMillis = STOP_GRACE_MILLIS, timeoutMillis = STOP_TIMEOUT_MILLIS)
            }
        }

        private val OWNER_DIRECTORY_PERMISSIONS = PosixFilePermissions.fromString("rwx------")
        private val OWNER_SOCKET_PERMISSIONS = PosixFilePermissions.fromString("rw-------")
        private const val FILE_TYPE_MASK = 0xF000
        private const val SOCKET_FILE_TYPE = 0xC000
        private const val STOP_GRACE_MILLIS = 100L
        private const val STOP_TIMEOUT_MILLIS = 2_000L
        private const val PARENT_TYPE_FAILURE_MESSAGE = "The Unix socket parent must be a real directory"
        private const val PARENT_PERMISSION_FAILURE_MESSAGE = "The Unix socket parent must use owner-only permissions"
        private const val SOCKET_PERMISSION_FAILURE_MESSAGE = "The Unix socket must use owner-only permissions"
        private const val TARGET_TYPE_FAILURE_MESSAGE = "The Unix socket target has an unsafe file type"
        private const val OWNER_FAILURE_MESSAGE = "The Unix socket path must be owned by the current user"
        private const val ACTIVE_SOCKET_FAILURE_MESSAGE = "The Unix socket target is already active"
        private const val TARGET_CHANGED_FAILURE_MESSAGE = "The Unix socket target changed during startup"
        private const val START_FAILURE_MESSAGE = "Local API servers could not be started"
        private const val STOP_FAILURE_MESSAGE = "Local API servers could not be stopped cleanly"
    }
}

private fun Application.installBusinessApi(
    transportKind: TransportKind,
    dependencies: LocalApiServerDependencies,
    browserSecurity: BrowserSecurity? = null,
) {
    installApiV1(transportKind) {
        installBusinessRoutes(dependencies)
        if (browserSecurity != null) installBrowserSessionRoute(browserSecurity)
    }
}

private fun Route.installBusinessRoutes(dependencies: LocalApiServerDependencies) {
    installReadRoutes(dependencies.read)
    installActionItemRoutes(dependencies.actionItems)
    installRefreshRunRoutes(dependencies.refreshRuns)
    installConfigurationRoutes(dependencies.configuration)
    installHealthRoutes(dependencies.getHealthSnapshot)
}

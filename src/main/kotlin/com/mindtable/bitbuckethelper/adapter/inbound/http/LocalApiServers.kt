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
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException
import java.nio.channels.SocketChannel
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.NoSuchFileException
import java.nio.file.OpenOption
import java.nio.file.Path
import java.nio.file.SecureDirectoryStream
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFileAttributeView
import java.nio.file.attribute.PosixFileAttributes
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

internal enum class LocalApiServerFileSystemEvent {
    PARENT_IDENTITY_CAPTURED,
    LIFECYCLE_LOCK_ACQUIRED,
    STALE_SOCKET_PROBED,
    BEFORE_STALE_SOCKET_DELETE,
    BEFORE_UNIX_BIND,
    AFTER_UNIX_START_BEFORE_IDENTITY_CAPTURE,
    BOUND_SOCKET_IDENTITY_CAPTURED,
    BEFORE_SOCKET_CHMOD,
    AFTER_SOCKET_CHMOD,
    BEFORE_BOUND_SOCKET_DELETE,
}

internal interface LocalApiServerFileSystemHooks {
    fun onEvent(event: LocalApiServerFileSystemEvent, socketPath: Path) = Unit

    fun isCurrentUser(path: Path, actualMatch: Boolean): Boolean = actualMatch

    companion object {
        val NONE: LocalApiServerFileSystemHooks = object : LocalApiServerFileSystemHooks {}
    }
}

class LocalApiServers private constructor(
    private val browserServer: EmbeddedServer<CIOApplicationEngine, CIOApplicationEngine.Configuration>,
    private val unixServer: EmbeddedServer<CIOApplicationEngine, CIOApplicationEngine.Configuration>,
    private val socketLifecycle: UnixSocketLifecycle,
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

            val unixStopped = try {
                unixServer.stop(gracePeriodMillis = STOP_GRACE_MILLIS, timeoutMillis = STOP_TIMEOUT_MILLIS)
                true
            } catch (_: Exception) {
                failed = true
                false
            }

            if (unixStopped) {
                try {
                    socketLifecycle.deleteCapturedBoundSocket()
                } catch (_: Exception) {
                    failed = true
                }
            }
            try {
                socketLifecycle.release()
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
        ): LocalApiServers = start(
            configuration = configuration,
            dependencies = dependencies,
            fileSystemHooks = LocalApiServerFileSystemHooks.NONE,
        )

        internal fun start(
            configuration: LocalApiServerConfiguration,
            dependencies: LocalApiServerDependencies,
            fileSystemHooks: LocalApiServerFileSystemHooks,
        ): LocalApiServers {
            var socketLifecycle: UnixSocketLifecycle? = null
            var unixServer: EmbeddedServer<CIOApplicationEngine, CIOApplicationEngine.Configuration>? = null
            var browserServer: EmbeddedServer<CIOApplicationEngine, CIOApplicationEngine.Configuration>? = null
            var unixStartAttempted = false
            var browserStartAttempted = false
            var unixStoppedAfterFailure = false

            try {
                socketLifecycle = UnixSocketLifecycle.acquire(configuration.socketPath, fileSystemHooks)
                socketLifecycle.prepareSocketTarget()

                val resolvedBrowserPort = AtomicInteger(configuration.port)
                val browserSecurity = BrowserSecurity(resolvedBrowserPort::get)
                unixServer = embeddedServer(CIO, configure = {
                    unixConnector(configuration.socketPath.toString())
                }) {
                    installBusinessApi(TransportKind.UNIX, dependencies)
                }
                browserServer = embeddedServer(
                    factory = CIO,
                    host = configuration.host,
                    port = configuration.port,
                ) {
                    installBrowserSecurity(browserSecurity)
                    installBusinessApi(TransportKind.BROWSER, dependencies, browserSecurity)
                }

                socketLifecycle.beforeUnixBind()
                unixStartAttempted = true
                unixServer.start(wait = false)
                fileSystemHooks.onEvent(
                    LocalApiServerFileSystemEvent.AFTER_UNIX_START_BEFORE_IDENTITY_CAPTURE,
                    configuration.socketPath,
                )
                socketLifecycle.captureAndHardenBoundSocket()

                browserStartAttempted = true
                browserServer.start(wait = false)
                val port = runBlocking { browserServer.engine.resolvedConnectors().single().port }
                resolvedBrowserPort.set(port)
                return LocalApiServers(
                    browserServer = browserServer,
                    unixServer = unixServer,
                    socketLifecycle = socketLifecycle,
                    browserPort = port,
                )
            } catch (_: Exception) {
                if (browserStartAttempted && browserServer != null) stopAfterFailedStart(browserServer)
                if (unixStartAttempted && unixServer != null) {
                    unixStoppedAfterFailure = stopAfterFailedStart(unixServer)
                }
                if (unixStoppedAfterFailure) {
                    runCatching { socketLifecycle?.deleteCapturedBoundSocket() }
                }
                runCatching { socketLifecycle?.release() }
                throw IllegalStateException(START_FAILURE_MESSAGE)
            }
        }

        private fun stopAfterFailedStart(
            server: EmbeddedServer<CIOApplicationEngine, CIOApplicationEngine.Configuration>,
        ): Boolean = try {
            server.stop(gracePeriodMillis = STOP_GRACE_MILLIS, timeoutMillis = STOP_TIMEOUT_MILLIS)
            true
        } catch (_: Exception) {
            false
        }

        private const val STOP_GRACE_MILLIS = 100L
        private const val STOP_TIMEOUT_MILLIS = 2_000L
        private const val START_FAILURE_MESSAGE = "Local API servers could not be started"
        private const val STOP_FAILURE_MESSAGE = "Local API servers could not be stopped cleanly"
    }
}

private class UnixSocketLifecycle private constructor(
    private val socketPath: Path,
    private val socketName: Path,
    private val parentPath: Path,
    private val parentDirectory: SecureDirectoryStream<Path>,
    private val parentIdentity: FileIdentity,
    private val lockPath: Path,
    private val lockName: Path,
    private val lockIdentity: FileIdentity,
    private val lockChannel: FileChannel,
    private val lifecycleLock: FileLock,
    private val hooks: LocalApiServerFileSystemHooks,
) {
    private var boundSocketIdentity: FileIdentity? = null
    private var released = false

    fun prepareSocketTarget() {
        validateGuard()
        val staleIdentity = readSocketIdentityOrNull() ?: return
        try {
            SocketChannel.open(StandardProtocolFamily.UNIX).use { channel ->
                channel.connect(UnixDomainSocketAddress.of(socketPath))
            }
            throw IllegalStateException(ACTIVE_SOCKET_FAILURE_MESSAGE)
        } catch (_: ConnectException) {
            // A refused connection identifies an existing socket path without a live listener.
        }

        hooks.onEvent(LocalApiServerFileSystemEvent.STALE_SOCKET_PROBED, socketPath)
        validateGuard()
        requireSocketIdentity(staleIdentity)
        hooks.onEvent(LocalApiServerFileSystemEvent.BEFORE_STALE_SOCKET_DELETE, socketPath)
        validateGuard()
        requireSocketIdentity(staleIdentity)
        parentDirectory.deleteFile(socketName)
        check(readSocketIdentityOrNull() == null) { SOCKET_DELETE_FAILURE_MESSAGE }
        validateGuard()
    }

    fun beforeUnixBind() {
        validateGuard()
        hooks.onEvent(LocalApiServerFileSystemEvent.BEFORE_UNIX_BIND, socketPath)
        validateGuard()
        check(readSocketIdentityOrNull() == null) { TARGET_CHANGED_FAILURE_MESSAGE }
    }

    fun captureAndHardenBoundSocket() {
        validateGuard()
        val captured = readSocketIdentity()
        boundSocketIdentity = captured
        hooks.onEvent(LocalApiServerFileSystemEvent.BOUND_SOCKET_IDENTITY_CAPTURED, socketPath)
        validateGuard()
        requireSocketIdentity(captured)

        hooks.onEvent(LocalApiServerFileSystemEvent.BEFORE_SOCKET_CHMOD, socketPath)
        validateGuard()
        requireSocketIdentity(captured)
        socketAttributeView().setPermissions(OWNER_SOCKET_PERMISSIONS)

        hooks.onEvent(LocalApiServerFileSystemEvent.AFTER_SOCKET_CHMOD, socketPath)
        validateGuard()
        val hardened = requireSocketIdentity(captured)
        check(hardened.permissions() == OWNER_SOCKET_PERMISSIONS) { SOCKET_PERMISSION_FAILURE_MESSAGE }
    }

    fun deleteCapturedBoundSocket() {
        val captured = boundSocketIdentity ?: return
        validateGuard()
        requireSocketIdentity(captured)
        hooks.onEvent(LocalApiServerFileSystemEvent.BEFORE_BOUND_SOCKET_DELETE, socketPath)
        validateGuard()
        requireSocketIdentity(captured)
        parentDirectory.deleteFile(socketName)
        boundSocketIdentity = null
        check(readSocketIdentityOrNull() == null) { SOCKET_DELETE_FAILURE_MESSAGE }
        validateGuard()
    }

    fun release() {
        if (released) return
        released = true
        var failed = false
        try {
            lifecycleLock.release()
        } catch (_: Exception) {
            failed = true
        }
        try {
            lockChannel.close()
        } catch (_: Exception) {
            failed = true
        }
        try {
            parentDirectory.close()
        } catch (_: Exception) {
            failed = true
        }
        check(!failed) { LOCK_RELEASE_FAILURE_MESSAGE }
    }

    private fun validateGuard() {
        check(!released) { LOCK_RELEASED_FAILURE_MESSAGE }
        val parentFromHandle = parentAttributeView().readAttributes()
        check(parentFromHandle.isDirectory && !parentFromHandle.isSymbolicLink) {
            PARENT_TYPE_FAILURE_MESSAGE
        }
        requireOwned(parentPath, parentFromHandle.owner())
        check(parentFromHandle.permissions() == OWNER_DIRECTORY_PERMISSIONS) {
            PARENT_PERMISSION_FAILURE_MESSAGE
        }
        check(parentFromHandle.toIdentity() == parentIdentity) { PARENT_CHANGED_FAILURE_MESSAGE }

        val parentFromPath = readPathAttributes(parentPath)
        check(parentFromPath.isDirectory && !parentFromPath.isSymbolicLink) {
            PARENT_TYPE_FAILURE_MESSAGE
        }
        requireOwned(parentPath, parentFromPath.owner())
        check(parentFromPath.permissions() == OWNER_DIRECTORY_PERMISSIONS) {
            PARENT_PERMISSION_FAILURE_MESSAGE
        }
        check(parentFromPath.toIdentity() == parentIdentity) { PARENT_CHANGED_FAILURE_MESSAGE }

        val currentLock = lockAttributeView().readAttributes()
        check(currentLock.isRegularFile && !currentLock.isSymbolicLink) { LOCK_TYPE_FAILURE_MESSAGE }
        requireOwned(lockPath, currentLock.owner())
        check(currentLock.permissions() == OWNER_SOCKET_PERMISSIONS) { LOCK_PERMISSION_FAILURE_MESSAGE }
        check(currentLock.toIdentity() == lockIdentity) { LOCK_CHANGED_FAILURE_MESSAGE }
        check(lifecycleLock.isValid) { LOCK_RELEASED_FAILURE_MESSAGE }
    }

    private fun readSocketIdentityOrNull(): FileIdentity? = try {
        readSocketAttributes().toIdentity()
    } catch (_: NoSuchFileException) {
        null
    }

    private fun readSocketIdentity(): FileIdentity = readSocketAttributes().toIdentity()

    private fun readSocketAttributes(): PosixFileAttributes {
        validateParentIdentityOnly()
        val fromHandle = socketAttributeView().readAttributes()
        check(fromHandle.isOther && !fromHandle.isSymbolicLink) { TARGET_TYPE_FAILURE_MESSAGE }
        requireOwned(socketPath, fromHandle.owner())

        val fromPath = readPathAttributes(socketPath)
        check(fromPath.toIdentity() == fromHandle.toIdentity()) { TARGET_CHANGED_FAILURE_MESSAGE }
        check(fromPath.isOther && !fromPath.isSymbolicLink) { TARGET_TYPE_FAILURE_MESSAGE }
        val mode = Files.getAttribute(socketPath, "unix:mode", LinkOption.NOFOLLOW_LINKS) as Int
        check((mode and FILE_TYPE_MASK) == SOCKET_FILE_TYPE) { TARGET_TYPE_FAILURE_MESSAGE }
        validateParentIdentityOnly()
        return fromHandle
    }

    private fun requireSocketIdentity(expected: FileIdentity): PosixFileAttributes {
        val current = readSocketAttributes()
        check(current.toIdentity() == expected) { TARGET_CHANGED_FAILURE_MESSAGE }
        return current
    }

    private fun validateParentIdentityOnly() {
        val fromHandle = parentAttributeView().readAttributes()
        check(fromHandle.toIdentity() == parentIdentity) { PARENT_CHANGED_FAILURE_MESSAGE }
        val fromPath = readPathAttributes(parentPath)
        check(fromPath.toIdentity() == parentIdentity) { PARENT_CHANGED_FAILURE_MESSAGE }
    }

    private fun requireOwned(path: Path, owner: UserPrincipal) {
        check(hooks.isCurrentUser(path, owner.isCurrentUser())) { OWNER_FAILURE_MESSAGE }
    }

    private fun parentAttributeView(): PosixFileAttributeView =
        checkNotNull(parentDirectory.getFileAttributeView(PosixFileAttributeView::class.java)) {
            ATTRIBUTE_VIEW_FAILURE_MESSAGE
        }

    private fun lockAttributeView(): PosixFileAttributeView =
        checkNotNull(
            parentDirectory.getFileAttributeView(
                lockName,
                PosixFileAttributeView::class.java,
                LinkOption.NOFOLLOW_LINKS,
            ),
        ) { ATTRIBUTE_VIEW_FAILURE_MESSAGE }

    private fun socketAttributeView(): PosixFileAttributeView =
        checkNotNull(
            parentDirectory.getFileAttributeView(
                socketName,
                PosixFileAttributeView::class.java,
                LinkOption.NOFOLLOW_LINKS,
            ),
        ) { ATTRIBUTE_VIEW_FAILURE_MESSAGE }

    companion object {
        fun acquire(socketPath: Path, hooks: LocalApiServerFileSystemHooks): UnixSocketLifecycle {
            val parentPath = checkNotNull(socketPath.parent) { PARENT_TYPE_FAILURE_MESSAGE }
            val initialParent = readPathAttributes(parentPath)
            check(initialParent.isDirectory && !initialParent.isSymbolicLink) {
                PARENT_TYPE_FAILURE_MESSAGE
            }
            requireOwned(hooks, parentPath, initialParent.owner())
            check(initialParent.permissions() == OWNER_DIRECTORY_PERMISSIONS) {
                PARENT_PERMISSION_FAILURE_MESSAGE
            }
            val parentIdentity = initialParent.toIdentity()

            val openedDirectory = Files.newDirectoryStream(parentPath)
            val parentDirectory = openedDirectory as? SecureDirectoryStream<Path>
                ?: run {
                    openedDirectory.close()
                    throw IllegalStateException(SECURE_DIRECTORY_FAILURE_MESSAGE)
                }
            var lockChannel: FileChannel? = null
            var lifecycleLock: FileLock? = null
            try {
                val handleView = checkNotNull(
                    parentDirectory.getFileAttributeView(PosixFileAttributeView::class.java),
                ) { ATTRIBUTE_VIEW_FAILURE_MESSAGE }
                check(handleView.readAttributes().toIdentity() == parentIdentity) {
                    PARENT_CHANGED_FAILURE_MESSAGE
                }
                hooks.onEvent(LocalApiServerFileSystemEvent.PARENT_IDENTITY_CAPTURED, socketPath)
                check(readPathAttributes(parentPath).toIdentity() == parentIdentity) {
                    PARENT_CHANGED_FAILURE_MESSAGE
                }

                val lockPath = lifecycleLockPath(socketPath)
                val lockName = lockPath.fileName
                val lockResources = acquireLifecycleLockFile(
                    parentDirectory = parentDirectory,
                    lockName = lockName,
                    lockPath = lockPath,
                    hooks = hooks,
                )
                lockChannel = lockResources.channel
                lifecycleLock = lockResources.lock

                val result = UnixSocketLifecycle(
                    socketPath = socketPath,
                    socketName = socketPath.fileName,
                    parentPath = parentPath,
                    parentDirectory = parentDirectory,
                    parentIdentity = parentIdentity,
                    lockPath = lockPath,
                    lockName = lockName,
                    lockIdentity = lockResources.identity,
                    lockChannel = lockChannel,
                    lifecycleLock = lifecycleLock,
                    hooks = hooks,
                )
                result.validateGuard()
                hooks.onEvent(LocalApiServerFileSystemEvent.LIFECYCLE_LOCK_ACQUIRED, socketPath)
                result.validateGuard()
                return result
            } catch (failure: Exception) {
                runCatching { lifecycleLock?.release() }
                runCatching { lockChannel?.close() }
                runCatching { parentDirectory.close() }
                throw failure
            }
        }

        private fun requireOwned(
            hooks: LocalApiServerFileSystemHooks,
            path: Path,
            owner: UserPrincipal,
        ) {
            check(hooks.isCurrentUser(path, owner.isCurrentUser())) { OWNER_FAILURE_MESSAGE }
        }

        private fun acquireLifecycleLockFile(
            parentDirectory: SecureDirectoryStream<Path>,
            lockName: Path,
            lockPath: Path,
            hooks: LocalApiServerFileSystemHooks,
        ): LockResources {
            val lockView = checkNotNull(
                parentDirectory.getFileAttributeView(
                    lockName,
                    PosixFileAttributeView::class.java,
                    LinkOption.NOFOLLOW_LINKS,
                ),
            ) { ATTRIBUTE_VIEW_FAILURE_MESSAGE }
            val openedChannel = if (readLockAttributesOrNull(lockView) == null) {
                try {
                    createLockChannel(parentDirectory, lockName, lockPath, lockView, hooks)
                } catch (_: FileAlreadyExistsException) {
                    openExistingLockChannel(parentDirectory, lockName, lockPath, lockView, hooks)
                }
            } else {
                openExistingLockChannel(parentDirectory, lockName, lockPath, lockView, hooks)
            }

            var acquiredLock: FileLock? = null
            try {
                acquiredLock = try {
                    openedChannel.channel.tryLock()
                } catch (_: OverlappingFileLockException) {
                    null
                }
                check(acquiredLock != null) { LOCK_CONTENTION_FAILURE_MESSAGE }
                val afterLock = validateLockAttributes(lockView.readAttributes(), lockPath, hooks)
                check(afterLock.toIdentity() == openedChannel.identity) { LOCK_CHANGED_FAILURE_MESSAGE }
                return LockResources(openedChannel.channel, acquiredLock, openedChannel.identity)
            } catch (failure: Exception) {
                runCatching { acquiredLock?.release() }
                runCatching { openedChannel.channel.close() }
                throw failure
            }
        }

        private fun createLockChannel(
            parentDirectory: SecureDirectoryStream<Path>,
            lockName: Path,
            lockPath: Path,
            lockView: PosixFileAttributeView,
            hooks: LocalApiServerFileSystemHooks,
        ): OpenedLockChannel {
            val channel = openFileChannel(
                parentDirectory = parentDirectory,
                lockName = lockName,
                options = CREATE_LOCK_OPTIONS,
                createWithOwnerOnlyPermissions = true,
            )
            try {
                val created = validateLockAttributes(lockView.readAttributes(), lockPath, hooks)
                return OpenedLockChannel(channel, created.toIdentity())
            } catch (failure: Exception) {
                runCatching { channel.close() }
                throw failure
            }
        }

        private fun openExistingLockChannel(
            parentDirectory: SecureDirectoryStream<Path>,
            lockName: Path,
            lockPath: Path,
            lockView: PosixFileAttributeView,
            hooks: LocalApiServerFileSystemHooks,
        ): OpenedLockChannel {
            val beforeOpen = validateLockAttributes(lockView.readAttributes(), lockPath, hooks)
            val expectedIdentity = beforeOpen.toIdentity()
            val channel = openFileChannel(
                parentDirectory = parentDirectory,
                lockName = lockName,
                options = EXISTING_LOCK_OPTIONS,
                createWithOwnerOnlyPermissions = false,
            )
            try {
                val afterOpen = validateLockAttributes(lockView.readAttributes(), lockPath, hooks)
                check(afterOpen.toIdentity() == expectedIdentity) { LOCK_CHANGED_FAILURE_MESSAGE }
                return OpenedLockChannel(channel, expectedIdentity)
            } catch (failure: Exception) {
                runCatching { channel.close() }
                throw failure
            }
        }

        private fun openFileChannel(
            parentDirectory: SecureDirectoryStream<Path>,
            lockName: Path,
            options: Set<OpenOption>,
            createWithOwnerOnlyPermissions: Boolean,
        ): FileChannel {
            val opened = if (createWithOwnerOnlyPermissions) {
                parentDirectory.newByteChannel(
                    lockName,
                    options,
                    PosixFilePermissions.asFileAttribute(OWNER_SOCKET_PERMISSIONS),
                )
            } else {
                parentDirectory.newByteChannel(lockName, options)
            }
            return opened as? FileChannel
                ?: run {
                    opened.close()
                    throw IllegalStateException(FILE_LOCK_CHANNEL_FAILURE_MESSAGE)
                }
        }

        private fun readLockAttributesOrNull(
            lockView: PosixFileAttributeView,
        ): PosixFileAttributes? = try {
            lockView.readAttributes()
        } catch (_: NoSuchFileException) {
            null
        }

        private fun validateLockAttributes(
            attributes: PosixFileAttributes,
            lockPath: Path,
            hooks: LocalApiServerFileSystemHooks,
        ): PosixFileAttributes {
            check(attributes.isRegularFile && !attributes.isSymbolicLink) { LOCK_TYPE_FAILURE_MESSAGE }
            requireOwned(hooks, lockPath, attributes.owner())
            check(attributes.permissions() == OWNER_SOCKET_PERMISSIONS) { LOCK_PERMISSION_FAILURE_MESSAGE }
            return attributes
        }

        private fun lifecycleLockPath(socketPath: Path): Path =
            socketPath.resolveSibling(".${socketPath.fileName}.lock")

        private val CREATE_LOCK_OPTIONS: Set<OpenOption> = setOf(
            StandardOpenOption.CREATE_NEW,
            StandardOpenOption.READ,
            StandardOpenOption.WRITE,
            LinkOption.NOFOLLOW_LINKS,
        )

        private val EXISTING_LOCK_OPTIONS: Set<OpenOption> = setOf(
            StandardOpenOption.READ,
            StandardOpenOption.WRITE,
            LinkOption.NOFOLLOW_LINKS,
        )
    }
}

private data class OpenedLockChannel(
    val channel: FileChannel,
    val identity: FileIdentity,
)

private data class LockResources(
    val channel: FileChannel,
    val lock: FileLock,
    val identity: FileIdentity,
)

private data class FileIdentity(
    val fileKey: Any,
    val ownerName: String,
)

private fun PosixFileAttributes.toIdentity(): FileIdentity = FileIdentity(
    fileKey = checkNotNull(fileKey()) { FILE_IDENTITY_FAILURE_MESSAGE },
    ownerName = owner().name,
)

private fun readPathAttributes(path: Path): PosixFileAttributes =
    Files.readAttributes(path, PosixFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)

private fun UserPrincipal.isCurrentUser(): Boolean {
    val currentUser = System.getProperty("user.name")
    return name == currentUser || name.substringAfterLast('\\') == currentUser
}

private val OWNER_DIRECTORY_PERMISSIONS = PosixFilePermissions.fromString("rwx------")
private val OWNER_SOCKET_PERMISSIONS = PosixFilePermissions.fromString("rw-------")
private const val FILE_TYPE_MASK = 0xF000
private const val SOCKET_FILE_TYPE = 0xC000
private const val PARENT_TYPE_FAILURE_MESSAGE = "The Unix socket parent must be a real directory"
private const val PARENT_PERMISSION_FAILURE_MESSAGE = "The Unix socket parent must use owner-only permissions"
private const val PARENT_CHANGED_FAILURE_MESSAGE = "The Unix socket parent identity changed"
private const val SOCKET_PERMISSION_FAILURE_MESSAGE = "The Unix socket must use owner-only permissions"
private const val SOCKET_DELETE_FAILURE_MESSAGE = "The captured Unix socket could not be removed safely"
private const val TARGET_TYPE_FAILURE_MESSAGE = "The Unix socket target has an unsafe file type"
private const val TARGET_CHANGED_FAILURE_MESSAGE = "The Unix socket target identity changed"
private const val OWNER_FAILURE_MESSAGE = "The Unix socket lifecycle path must be owned by the current user"
private const val ACTIVE_SOCKET_FAILURE_MESSAGE = "The Unix socket target is already active"
private const val LOCK_TYPE_FAILURE_MESSAGE = "The Unix socket lifecycle lock must be a regular file"
private const val LOCK_PERMISSION_FAILURE_MESSAGE = "The Unix socket lifecycle lock must use owner-only permissions"
private const val LOCK_CHANGED_FAILURE_MESSAGE = "The Unix socket lifecycle lock identity changed"
private const val LOCK_CONTENTION_FAILURE_MESSAGE = "The Unix socket lifecycle is already owned"
private const val LOCK_RELEASED_FAILURE_MESSAGE = "The Unix socket lifecycle lock is not held"
private const val LOCK_RELEASE_FAILURE_MESSAGE = "The Unix socket lifecycle lock could not be released"
private const val FILE_IDENTITY_FAILURE_MESSAGE = "The filesystem does not expose a stable file identity"
private const val FILE_LOCK_CHANNEL_FAILURE_MESSAGE = "The filesystem does not expose a lockable file channel"
private const val SECURE_DIRECTORY_FAILURE_MESSAGE = "The filesystem does not support secure directory operations"
private const val ATTRIBUTE_VIEW_FAILURE_MESSAGE = "The filesystem does not support POSIX identity checks"

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

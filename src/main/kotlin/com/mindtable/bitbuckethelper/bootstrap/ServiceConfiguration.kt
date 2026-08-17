package com.mindtable.bitbuckethelper.bootstrap

import com.typesafe.config.Config
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SecureDirectoryStream
import java.nio.file.attribute.PosixFileAttributes
import java.nio.file.attribute.PosixFilePermissions
import java.time.Duration

class StartupConfigurationException(message: String) : IllegalArgumentException(message)

class BitbucketCredentials(
    val username: String,
    val apiToken: String,
) {
    override fun toString(): String = "BitbucketCredentials(<redacted>)"
}

data class ServiceConfiguration(
    val httpHost: String,
    val httpPort: Int,
    val databasePath: Path,
    val unixSocketPath: Path,
    val notificationExecutablePath: Path,
    val bitbucketRequestTimeout: Duration,
    val credentials: BitbucketCredentials,
)

object ServiceConfigurationLoader {
    fun load(
        config: Config,
        environment: Map<String, String> = System.getenv(),
    ): ServiceConfiguration {
        val username = requiredSecret(environment, "BITBUCKET_USERNAME")
        val token = requiredSecret(environment, "BITBUCKET_APP_PASSWORD")
        val port = environment["BITBUCKET_HELPER_HTTP_PORT"]
            ?.let { parsePort(it) }
            ?: config.getInt("bitbucket-helper.http.port")
        requireConfig(port in 1..65535, "BITBUCKET_HELPER_HTTP_PORT must be between 1 and 65535")
        val databasePath = Path.of(
            environment["BITBUCKET_HELPER_DATABASE_PATH"]
                ?: config.getString("bitbucket-helper.database.path"),
        ).toAbsolutePath().normalize()
        validateDatabaseLocation(databasePath)
        val unixSocketPath = UnixSocketPathLoader.load(config, environment)
        validateSocketLocation(unixSocketPath)
        val notificationExecutablePath = Path.of(
            environment["BITBUCKET_HELPER_NOTIFICATION_EXECUTABLE"]
                ?: config.getString("bitbucket-helper.notification.executable"),
        ).toAbsolutePath().normalize()
        validateNotificationExecutable(notificationExecutablePath)
        return ServiceConfiguration(
            httpHost = "127.0.0.1",
            httpPort = port,
            databasePath = databasePath,
            unixSocketPath = unixSocketPath,
            notificationExecutablePath = notificationExecutablePath,
            bitbucketRequestTimeout = parsePositiveDuration(
                config.getString("bitbucket-helper.bitbucket.request-timeout"),
                "bitbucket-helper.bitbucket.request-timeout",
            ),
            credentials = BitbucketCredentials(username, token),
        )
    }

    private fun requiredSecret(environment: Map<String, String>, name: String): String =
        environment[name]?.takeIf { it.isNotBlank() }
            ?: throw StartupConfigurationException("Required environment variable $name is missing or blank")

    private fun parsePort(raw: String): Int = raw.toIntOrNull()
        ?: throw StartupConfigurationException("BITBUCKET_HELPER_HTTP_PORT must be an integer")

    private fun parseDuration(raw: String, name: String): Duration =
        runCatching { Duration.parse(raw) }
            .getOrElse { throw StartupConfigurationException("$name must be an ISO-8601 duration") }

    private fun parsePositiveDuration(raw: String, name: String): Duration =
        parseDuration(raw, name).also {
            requireConfig(!it.isZero && !it.isNegative, "$name must be positive")
        }

    private fun validateDatabaseLocation(path: Path) {
        val canonicalPath = resolveCanonicalDatabasePath(path)
        if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
            val directEntry = readAttributes(path, "BITBUCKET_HELPER_DATABASE_PATH")
            requireConfig(
                !directEntry.isSymbolicLink,
                "BITBUCKET_HELPER_DATABASE_PATH must identify a non-symbolic-link file",
            )
            val target = readAttributes(canonicalPath, "BITBUCKET_HELPER_DATABASE_PATH")
            requireConfig(
                target.isRegularFile &&
                    !target.isSymbolicLink &&
                    target.owner().isCurrentUser() &&
                    target.permissions() == OWNER_FILE_PERMISSIONS &&
                    Files.isWritable(canonicalPath),
                "BITBUCKET_HELPER_DATABASE_PATH must identify a current-user-owned owner-only writable regular file",
            )
        }
        validateExistingDatabaseSidecars(canonicalPath)
    }

    private fun resolveCanonicalDatabasePath(path: Path): Path {
        var ancestor = path.parent
        while (ancestor != null && !Files.exists(ancestor, LinkOption.NOFOLLOW_LINKS)) {
            ancestor = ancestor.parent
        }
        val resolvedAncestor = ancestor
            ?: throw StartupConfigurationException("BITBUCKET_HELPER_DATABASE_PATH parent must be creatable or writable")
        val canonicalAncestor = validateDatabaseParent(resolvedAncestor)
        return canonicalAncestor.resolve(resolvedAncestor.relativize(path))
    }

    private fun validateExistingDatabaseSidecars(databasePath: Path) {
        listOf("-journal", "-wal", "-shm").forEach { suffix ->
            val sidecar = databasePath.resolveSibling(databasePath.fileName.toString() + suffix)
            if (!Files.exists(sidecar, LinkOption.NOFOLLOW_LINKS)) return@forEach
            val attributes = readAttributes(sidecar, "BITBUCKET_HELPER_DATABASE_PATH")
            requireConfig(
                attributes.isRegularFile &&
                    !attributes.isSymbolicLink &&
                    attributes.owner().isCurrentUser() &&
                    attributes.permissions() == OWNER_FILE_PERMISSIONS &&
                    Files.isWritable(sidecar),
                "BITBUCKET_HELPER_DATABASE_PATH sidecar must be a current-user-owned owner-only writable regular file",
            )
        }
    }

    private fun validateSocketLocation(path: Path) {
        requireConfig(path.isAbsolute && path == path.normalize(), "BITBUCKET_HELPER_UNIX_SOCKET_PATH must be absolute")
        val parent = path.parent
            ?: throw StartupConfigurationException("BITBUCKET_HELPER_UNIX_SOCKET_PATH parent must be a real directory")
        val attributes = readAttributes(parent, "BITBUCKET_HELPER_UNIX_SOCKET_PATH")
        requireConfig(
            attributes.isDirectory && !attributes.isSymbolicLink,
            "BITBUCKET_HELPER_UNIX_SOCKET_PATH parent must be a real directory",
        )
        requireConfig(
            attributes.owner().isCurrentUser(),
            "BITBUCKET_HELPER_UNIX_SOCKET_PATH parent must be owned by the current user",
        )
        requireConfig(
            attributes.permissions() == OWNER_DIRECTORY_PERMISSIONS,
            "BITBUCKET_HELPER_UNIX_SOCKET_PATH parent must use owner-only permissions",
        )
        validateSecureDirectoryAccess(parent)
    }

    private fun validateDatabaseParent(parent: Path?): Path {
        val resolvedParent = parent
            ?: throw StartupConfigurationException("BITBUCKET_HELPER_DATABASE_PATH parent must be creatable or writable")
        val attributes = readAttributes(resolvedParent, "BITBUCKET_HELPER_DATABASE_PATH")
        requireConfig(
            attributes.isDirectory &&
                !attributes.isSymbolicLink &&
                attributes.owner().isCurrentUser() &&
                attributes.permissions() == OWNER_DIRECTORY_PERMISSIONS &&
                Files.isWritable(resolvedParent),
            "BITBUCKET_HELPER_DATABASE_PATH parent must be current-user-owned, owner-only, and creatable or writable",
        )
        val canonicalParent = try {
            resolvedParent.toRealPath()
        } catch (_: Exception) {
            throw StartupConfigurationException("BITBUCKET_HELPER_DATABASE_PATH parent path is unavailable")
        }
        val canonicalAttributes = readAttributes(canonicalParent, "BITBUCKET_HELPER_DATABASE_PATH")
        requireConfig(
            canonicalAttributes.isDirectory &&
                !canonicalAttributes.isSymbolicLink &&
                canonicalAttributes.owner().isCurrentUser() &&
                canonicalAttributes.permissions() == OWNER_DIRECTORY_PERMISSIONS &&
                Files.isWritable(canonicalParent),
            "BITBUCKET_HELPER_DATABASE_PATH parent must be current-user-owned, owner-only, and creatable or writable",
        )
        validateSecureDirectoryAccess(canonicalParent, "BITBUCKET_HELPER_DATABASE_PATH")
        validateReplacementSafeAncestors(canonicalParent, "BITBUCKET_HELPER_DATABASE_PATH")
        return canonicalParent
    }

    private fun validateReplacementSafeAncestors(managedParent: Path, setting: String) {
        var directChildOwnerName = readAttributes(managedParent, setting).owner().name
        var ancestor = managedParent.parent
        while (ancestor != null) {
            val attributes = readAttributes(ancestor, setting)
            requireConfig(
                attributes.isDirectory && !attributes.isSymbolicLink,
                "$setting ancestors must be real directories",
            )
            val mode = try {
                Files.getAttribute(ancestor, "unix:mode", LinkOption.NOFOLLOW_LINKS) as Int
            } catch (_: Exception) {
                throw StartupConfigurationException("$setting ancestor mode is unavailable")
            }
            requireConfig(
                isReplacementSafeAncestor(
                    ownerName = attributes.owner().name,
                    currentUserName = System.getProperty("user.name"),
                    unixMode = mode,
                    directChildOwnerName = directChildOwnerName,
                ),
                "$setting ancestors must not be replaceable by another user",
            )
            directChildOwnerName = attributes.owner().name
            ancestor = ancestor.parent
        }
    }

    private fun readAttributes(path: Path, setting: String): PosixFileAttributes = try {
        Files.readAttributes(path, PosixFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
    } catch (_: Exception) {
        throw StartupConfigurationException("$setting path attributes are unavailable")
    }

    private fun validateSecureDirectoryAccess(
        parent: Path,
        setting: String = "BITBUCKET_HELPER_UNIX_SOCKET_PATH",
    ) {
        try {
            Files.newDirectoryStream(parent).use { directory ->
                requireConfig(
                    directory is SecureDirectoryStream<*>,
                    "$setting parent must support secure directory access",
                )
            }
        } catch (failure: StartupConfigurationException) {
            throw failure
        } catch (_: Exception) {
            throw StartupConfigurationException(
                "$setting parent secure directory access is unavailable",
            )
        }
    }

    private fun validateNotificationExecutable(path: Path) {
        requireConfig(
            path.isAbsolute && path == path.normalize() && Files.isRegularFile(path) && Files.isExecutable(path),
            "BITBUCKET_HELPER_NOTIFICATION_EXECUTABLE must identify an executable regular file",
        )
    }

    private fun requireConfig(condition: Boolean, message: String) {
        if (!condition) throw StartupConfigurationException(message)
    }

    private fun java.nio.file.attribute.UserPrincipal.isCurrentUser(): Boolean {
        val currentUser = System.getProperty("user.name")
        return name == currentUser || name.substringAfterLast('\\') == currentUser
    }

    private val OWNER_DIRECTORY_PERMISSIONS = PosixFilePermissions.fromString("rwx------")
    private val OWNER_FILE_PERMISSIONS = PosixFilePermissions.fromString("rw-------")
}

internal fun isReplacementSafeAncestor(
    ownerName: String,
    currentUserName: String,
    unixMode: Int,
    directChildOwnerName: String,
): Boolean {
    fun trusted(name: String): Boolean {
        val normalized = name.substringAfterLast('\\')
        return normalized == "root" || normalized == currentUserName.substringAfterLast('\\')
    }

    if (!trusted(ownerName)) return false
    val writableByAnotherUser = unixMode and 0x12 != 0
    if (!writableByAnotherUser) return true
    val sticky = unixMode and 0x200 != 0
    return sticky && trusted(directChildOwnerName)
}

object UnixSocketPathLoader {
    fun load(
        config: Config,
        environment: Map<String, String> = System.getenv(),
    ): Path = Path.of(
        environment["BITBUCKET_HELPER_UNIX_SOCKET_PATH"]
            ?: config.getString("bitbucket-helper.unix-socket.path"),
    ).toAbsolutePath().normalize()
}

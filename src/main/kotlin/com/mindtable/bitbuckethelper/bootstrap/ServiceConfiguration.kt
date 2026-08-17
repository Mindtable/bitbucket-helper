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
        if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
            val target = readAttributes(path, "BITBUCKET_HELPER_DATABASE_PATH")
            requireConfig(
                target.isRegularFile && !target.isSymbolicLink && Files.isWritable(path),
                "BITBUCKET_HELPER_DATABASE_PATH must identify a writable regular file",
            )
            validateDatabaseParent(path.parent)
            return
        }

        var ancestor = path.parent
        while (ancestor != null && !Files.exists(ancestor, LinkOption.NOFOLLOW_LINKS)) {
            ancestor = ancestor.parent
        }
        validateDatabaseParent(ancestor)
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

    private fun validateDatabaseParent(parent: Path?) {
        val resolvedParent = parent
            ?: throw StartupConfigurationException("BITBUCKET_HELPER_DATABASE_PATH parent must be creatable or writable")
        val attributes = readAttributes(resolvedParent, "BITBUCKET_HELPER_DATABASE_PATH")
        requireConfig(
            attributes.isDirectory && !attributes.isSymbolicLink && Files.isWritable(resolvedParent),
            "BITBUCKET_HELPER_DATABASE_PATH parent must be creatable or writable",
        )
    }

    private fun readAttributes(path: Path, setting: String): PosixFileAttributes = try {
        Files.readAttributes(path, PosixFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
    } catch (_: Exception) {
        throw StartupConfigurationException("$setting path attributes are unavailable")
    }

    private fun validateSecureDirectoryAccess(parent: Path) {
        try {
            Files.newDirectoryStream(parent).use { directory ->
                requireConfig(
                    directory is SecureDirectoryStream<*>,
                    "BITBUCKET_HELPER_UNIX_SOCKET_PATH parent must support secure directory access",
                )
            }
        } catch (failure: StartupConfigurationException) {
            throw failure
        } catch (_: Exception) {
            throw StartupConfigurationException(
                "BITBUCKET_HELPER_UNIX_SOCKET_PATH parent secure directory access is unavailable",
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

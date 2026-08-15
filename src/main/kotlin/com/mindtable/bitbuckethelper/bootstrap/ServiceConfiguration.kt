package com.mindtable.bitbuckethelper.bootstrap

import com.typesafe.config.Config
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
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
    val refreshInterval: Duration,
    val bitbucketBaseUrl: URI,
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
        val interval = environment["BITBUCKET_HELPER_REFRESH_INTERVAL"]
            ?.let { parseDuration(it, "BITBUCKET_HELPER_REFRESH_INTERVAL") }
            ?: parseDuration(config.getString("bitbucket-helper.refresh.interval"), "bitbucket-helper.refresh.interval")
        requireConfig(!interval.isZero && !interval.isNegative, "BITBUCKET_HELPER_REFRESH_INTERVAL must be positive")
        val databasePath = Path.of(
            environment["BITBUCKET_HELPER_DATABASE_PATH"]
                ?: config.getString("bitbucket-helper.database.path"),
        ).toAbsolutePath().normalize()
        validateDatabaseLocation(databasePath)
        return ServiceConfiguration(
            httpHost = "127.0.0.1",
            httpPort = port,
            databasePath = databasePath,
            refreshInterval = interval,
            bitbucketBaseUrl = URI(config.getString("bitbucket-helper.bitbucket.base-url")),
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
        requireConfig(!Files.isDirectory(path), "BITBUCKET_HELPER_DATABASE_PATH must identify a file")
        var ancestor: Path? = path.parent
        while (ancestor != null && !Files.exists(ancestor)) ancestor = ancestor.parent
        requireConfig(
            ancestor != null && Files.isDirectory(ancestor) && Files.isWritable(ancestor),
            "BITBUCKET_HELPER_DATABASE_PATH parent must be creatable or writable",
        )
    }

    private fun requireConfig(condition: Boolean, message: String) {
        if (!condition) throw StartupConfigurationException(message)
    }
}

package com.mindtable.bitbuckethelper.bootstrap

import com.typesafe.config.Config
import java.nio.file.InvalidPathException
import java.nio.file.Path
import java.util.Locale

enum class ServiceLogLevel {
    TRACE,
    DEBUG,
    INFO,
    WARN,
    ERROR,
}

data class LoggingConfiguration(
    val level: ServiceLogLevel,
    val directory: Path,
)

object LoggingConfigurationLoader {
    private const val LEVEL_ENVIRONMENT = "BITBUCKET_HELPER_LOG_LEVEL"
    private const val DIRECTORY_ENVIRONMENT = "BITBUCKET_HELPER_LOG_DIRECTORY"
    private const val LEVEL_CONFIG = "bitbucket-helper.logging.level"
    private const val DIRECTORY_CONFIG = "bitbucket-helper.logging.directory"

    fun load(
        config: Config,
        environment: Map<String, String> = System.getenv(),
    ): LoggingConfiguration {
        val level = parseLevel(environment[LEVEL_ENVIRONMENT] ?: configured(config, LEVEL_CONFIG, LEVEL_ENVIRONMENT))
        val directory = parseDirectory(
            environment[DIRECTORY_ENVIRONMENT]
                ?: configured(config, DIRECTORY_CONFIG, DIRECTORY_ENVIRONMENT),
        )
        return LoggingConfiguration(level, directory)
    }

    private fun parseLevel(raw: String): ServiceLogLevel {
        val normalized = raw.trim()
        if (normalized.isEmpty()) {
            throw StartupConfigurationException("$LEVEL_ENVIRONMENT must not be blank")
        }
        return try {
            ServiceLogLevel.valueOf(normalized.uppercase(Locale.ROOT))
        } catch (_: IllegalArgumentException) {
            throw StartupConfigurationException(
                "$LEVEL_ENVIRONMENT must be one of TRACE, DEBUG, INFO, WARN, or ERROR",
            )
        }
    }

    private fun parseDirectory(raw: String): Path {
        if (raw.isBlank()) {
            throw StartupConfigurationException("$DIRECTORY_ENVIRONMENT must not be blank")
        }
        return try {
            Path.of(raw).toAbsolutePath().normalize()
        } catch (_: InvalidPathException) {
            throw StartupConfigurationException("$DIRECTORY_ENVIRONMENT must identify a valid path")
        }
    }

    private fun configured(config: Config, path: String, setting: String): String = try {
        config.getString(path)
    } catch (_: Exception) {
        throw StartupConfigurationException("$setting must be configured")
    }
}

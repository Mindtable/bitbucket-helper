package com.mindtable.bitbuckethelper.bootstrap

import com.typesafe.config.ConfigFactory
import java.nio.file.Path
import java.time.Duration
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ServiceConfigurationTest {
    private val defaults = ConfigFactory.parseResources("application.conf").resolve()
    private val credentials = mapOf(
        "BITBUCKET_USERNAME" to "person@example.com",
        "BITBUCKET_APP_PASSWORD" to "sentinel-api-token",
    )

    @Test
    fun `missing username is named without exposing the token`() {
        val error = assertThrows(StartupConfigurationException::class.java) {
            ServiceConfigurationLoader.load(defaults, credentials - "BITBUCKET_USERNAME")
        }
        assertTrue(error.message!!.contains("BITBUCKET_USERNAME"))
        assertFalse(error.message!!.contains("sentinel-api-token"))
    }

    @Test
    fun `blank legacy token variable is rejected without exposing its value`() {
        val error = assertThrows(StartupConfigurationException::class.java) {
            ServiceConfigurationLoader.load(
                defaults,
                credentials + ("BITBUCKET_APP_PASSWORD" to "   "),
            )
        }
        assertTrue(error.message!!.contains("BITBUCKET_APP_PASSWORD"))
        assertFalse(error.message!!.contains("person@example.com"))
    }

    @Test
    fun `credential rendering is always redacted`() {
        val loaded = ServiceConfigurationLoader.load(defaults, credentials)
        val rendered = loaded.credentials.toString()
        assertFalse(rendered.contains("person@example.com"))
        assertFalse(rendered.contains("sentinel-api-token"))
        assertEquals("BitbucketCredentials(<redacted>)", rendered)
    }

    @Test
    fun `defaults and environment overrides are converted once`() {
        val loaded = ServiceConfigurationLoader.load(
            defaults,
            credentials + mapOf(
                "BITBUCKET_HELPER_HTTP_PORT" to "18080",
                "BITBUCKET_HELPER_DATABASE_PATH" to "build/test-state.sqlite",
                "BITBUCKET_HELPER_REFRESH_INTERVAL" to "PT30S",
            ),
        )
        assertEquals("127.0.0.1", loaded.httpHost)
        assertEquals(18080, loaded.httpPort)
        assertEquals(Path.of("build/test-state.sqlite").toAbsolutePath().normalize(), loaded.databasePath)
        assertEquals(Duration.ofSeconds(30), loaded.refreshInterval)
        assertEquals("https://api.bitbucket.org/2.0", loaded.bitbucketBaseUrl.toString())
        assertEquals(Duration.ofSeconds(30), loaded.bitbucketRequestTimeout)
    }

    @Test
    fun `invalid port and nonpositive duration identify only their setting`() {
        val portError = assertThrows(StartupConfigurationException::class.java) {
            ServiceConfigurationLoader.load(defaults, credentials + ("BITBUCKET_HELPER_HTTP_PORT" to "0"))
        }
        val durationError = assertThrows(StartupConfigurationException::class.java) {
            ServiceConfigurationLoader.load(defaults, credentials + ("BITBUCKET_HELPER_REFRESH_INTERVAL" to "PT0S"))
        }
        assertTrue(portError.message!!.contains("BITBUCKET_HELPER_HTTP_PORT"))
        assertTrue(durationError.message!!.contains("BITBUCKET_HELPER_REFRESH_INTERVAL"))
    }
}

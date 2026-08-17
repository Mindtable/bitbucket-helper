package com.mindtable.bitbuckethelper.bootstrap

import com.typesafe.config.ConfigFactory
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class ServiceConfigurationTest {
    @TempDir
    lateinit var directory: Path

    private val defaults = ConfigFactory.parseResources("application.conf").resolve()
    private val credentials: Map<String, String>
        get() {
            val executable = directory.resolve("desktop-notifications")
            java.nio.file.Files.writeString(executable, "test executable")
            check(executable.toFile().setExecutable(true, true))
            return mapOf(
                "BITBUCKET_USERNAME" to "person@example.com",
                "BITBUCKET_APP_PASSWORD" to "sentinel-api-token",
                "BITBUCKET_HELPER_DATABASE_PATH" to directory.resolve("state.sqlite").toString(),
                "BITBUCKET_HELPER_UNIX_SOCKET_PATH" to directory.resolve("service.sock").toString(),
                "BITBUCKET_HELPER_NOTIFICATION_EXECUTABLE" to executable.toString(),
            )
        }

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
            ),
        )
        assertEquals("127.0.0.1", loaded.httpHost)
        assertEquals(18080, loaded.httpPort)
        assertEquals(Path.of("build/test-state.sqlite").toAbsolutePath().normalize(), loaded.databasePath)
        assertEquals(directory.resolve("service.sock").toAbsolutePath().normalize(), loaded.unixSocketPath)
        assertEquals(
            directory.resolve("desktop-notifications").toAbsolutePath().normalize(),
            loaded.notificationExecutablePath,
        )
        assertEquals(java.time.Duration.ofSeconds(30), loaded.bitbucketRequestTimeout)
    }

    @Test
    fun `invalid port identifies only its setting`() {
        val portError = assertThrows(StartupConfigurationException::class.java) {
            ServiceConfigurationLoader.load(defaults, credentials + ("BITBUCKET_HELPER_HTTP_PORT" to "0"))
        }
        assertTrue(portError.message!!.contains("BITBUCKET_HELPER_HTTP_PORT"))
    }

    @Test
    fun `socket locator prefers the environment and does not require credentials or other paths`() {
        val configured = ConfigFactory.parseString(
            "bitbucket-helper.unix-socket.path = \"relative/configured.sock\"",
        ).withFallback(defaults).resolve()
        val override = directory.resolve("../socket-parent/service.sock")

        val located = UnixSocketPathLoader.load(
            configured,
            mapOf("BITBUCKET_HELPER_UNIX_SOCKET_PATH" to override.toString()),
        )

        assertEquals(override.toAbsolutePath().normalize(), located)
    }

    @Test
    fun `socket locator falls back to configuration without validating unrelated settings`() {
        val configuredPath = directory.resolve("configured.sock")
        val config = ConfigFactory.parseString(
            """
            bitbucket-helper.unix-socket.path = "${configuredPath}"
            bitbucket-helper.database.path = "/missing-parent/state.sqlite"
            bitbucket-helper.notification.executable = "/missing/desktop-notifications"
            """.trimIndent(),
        ).withFallback(defaults).resolve()

        assertEquals(configuredPath.toAbsolutePath().normalize(), UnixSocketPathLoader.load(config, emptyMap()))
    }

    @Test
    fun `full configuration rejects every resource path before runtime creation`() {
        val invalidExecutable = directory.resolve("not-executable")
        Files.writeString(invalidExecutable, "not executable")

        val invalidPaths = listOf(
            "BITBUCKET_HELPER_DATABASE_PATH" to directory,
            "BITBUCKET_HELPER_UNIX_SOCKET_PATH" to directory.resolve("missing/socket/service.sock"),
            "BITBUCKET_HELPER_NOTIFICATION_EXECUTABLE" to invalidExecutable,
        )

        invalidPaths.forEach { (setting, path) ->
            var resourceOpened = false
            val error = assertThrows(StartupConfigurationException::class.java) {
                loadAndCreateRuntime(defaults, credentials + (setting to path.toString())) {
                    resourceOpened = true
                }
            }

            assertTrue(error.message!!.contains(setting))
            assertFalse(error.message!!.contains("sentinel-api-token"))
            assertFalse(resourceOpened, "$setting validation happened after runtime creation")
        }
    }

    @Test
    fun `full configuration rejects group or world accessible socket parent before runtime creation`() {
        val permissiveParent = Files.createDirectory(directory.resolve("permissive-socket-parent"))
        Files.setPosixFilePermissions(permissiveParent, PosixFilePermissions.fromString("rwxr-xr-x"))

        assertSocketPathRejectedBeforeRuntime(permissiveParent.resolve("service.sock"))
    }

    @Test
    fun `full configuration rejects symlinked socket parent before runtime creation`() {
        val realParent = Files.createDirectory(directory.resolve("real-socket-parent"))
        Files.setPosixFilePermissions(realParent, PosixFilePermissions.fromString("rwx------"))
        val linkedParent = directory.resolve("linked-socket-parent")
        Files.createSymbolicLink(linkedParent, realParent.fileName)

        assertSocketPathRejectedBeforeRuntime(linkedParent.resolve("service.sock"))
    }

    @Test
    fun `full configuration rejects read only existing database before runtime creation`() {
        val databasePath = directory.resolve("read-only.sqlite")
        Files.writeString(databasePath, "not writable")
        Files.setPosixFilePermissions(databasePath, PosixFilePermissions.fromString("r--------"))

        assertDatabasePathRejectedBeforeRuntime(databasePath)
    }

    @Test
    fun `full configuration rejects symlinked existing database before runtime creation`() {
        val databaseTarget = directory.resolve("database-target.sqlite")
        Files.writeString(databaseTarget, "target")
        val databaseLink = directory.resolve("database-link.sqlite")
        Files.createSymbolicLink(databaseLink, databaseTarget.fileName)

        assertDatabasePathRejectedBeforeRuntime(databaseLink)
    }

    @Test
    fun `full configuration rejects database whose parent cannot create a journal before runtime creation`() {
        val databaseParent = Files.createDirectory(directory.resolve("read-only-database-parent"))
        val databasePath = databaseParent.resolve("state.sqlite")
        Files.writeString(databasePath, "database")
        Files.setPosixFilePermissions(databasePath, PosixFilePermissions.fromString("rw-------"))
        Files.setPosixFilePermissions(databaseParent, PosixFilePermissions.fromString("r-x------"))

        try {
            assertDatabasePathRejectedBeforeRuntime(databasePath)
        } finally {
            Files.setPosixFilePermissions(databaseParent, PosixFilePermissions.fromString("rwx------"))
        }
    }

    @Test
    fun `full configuration rejects missing database under non writable ancestor before runtime creation`() {
        val databaseAncestor = Files.createDirectory(directory.resolve("read-only-database-ancestor"))
        Files.setPosixFilePermissions(databaseAncestor, PosixFilePermissions.fromString("r-x------"))

        try {
            assertDatabasePathRejectedBeforeRuntime(databaseAncestor.resolve("nested/state.sqlite"))
        } finally {
            Files.setPosixFilePermissions(databaseAncestor, PosixFilePermissions.fromString("rwx------"))
        }
    }

    @Test
    fun `runtime factory is not invoked until full configuration validation succeeds`() {
        var resourceOpened = false

        assertThrows(StartupConfigurationException::class.java) {
            loadAndCreateRuntime(
                defaults,
                credentials - "BITBUCKET_USERNAME",
            ) {
                resourceOpened = true
            }
        }

        assertFalse(resourceOpened)
    }

    private fun assertSocketPathRejectedBeforeRuntime(socketPath: Path) {
        var resourceOpened = false

        val error = assertThrows(StartupConfigurationException::class.java) {
            loadAndCreateRuntime(
                defaults,
                credentials + ("BITBUCKET_HELPER_UNIX_SOCKET_PATH" to socketPath.toString()),
            ) {
                resourceOpened = true
            }
        }

        assertTrue(error.message!!.contains("BITBUCKET_HELPER_UNIX_SOCKET_PATH"))
        assertFalse(error.message!!.contains("sentinel-api-token"))
        assertFalse(resourceOpened)
    }

    private fun assertDatabasePathRejectedBeforeRuntime(databasePath: Path) {
        var resourceOpened = false

        val error = assertThrows(StartupConfigurationException::class.java) {
            loadAndCreateRuntime(
                defaults,
                credentials + ("BITBUCKET_HELPER_DATABASE_PATH" to databasePath.toString()),
            ) {
                resourceOpened = true
            }
        }

        assertTrue(error.message!!.contains("BITBUCKET_HELPER_DATABASE_PATH"))
        assertFalse(error.message!!.contains("sentinel-api-token"))
        assertFalse(resourceOpened)
    }
}

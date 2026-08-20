package com.mindtable.bitbuckethelper.bootstrap

import com.typesafe.config.ConfigFactory
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class LoggingConfigurationTest {
    @TempDir
    lateinit var directory: Path

    private val defaults = ConfigFactory.parseResources("application.conf").resolve()

    @Test
    fun `defaults are debug and project local var log`() {
        val loaded = LoggingConfigurationLoader.load(defaults, emptyMap())

        assertEquals(ServiceLogLevel.DEBUG, loaded.level)
        assertEquals(Path.of("./var/log").toAbsolutePath().normalize(), loaded.directory)
    }

    @Test
    fun `environment overrides level and directory with normalized values`() {
        val configuredDirectory = directory.resolve("configured")
        val relativeDirectory = Path.of("")
            .toAbsolutePath()
            .normalize()
            .relativize(configuredDirectory.toAbsolutePath().normalize())

        val loaded = LoggingConfigurationLoader.load(
            defaults,
            mapOf(
                "BITBUCKET_HELPER_LOG_LEVEL" to "wArN",
                "BITBUCKET_HELPER_LOG_DIRECTORY" to "./$relativeDirectory/../configured",
            ),
        )

        assertEquals(ServiceLogLevel.WARN, loaded.level)
        assertEquals(configuredDirectory.toAbsolutePath().normalize(), loaded.directory)
    }

    @Test
    fun `blank level names only its setting`() {
        val error = assertThrows(StartupConfigurationException::class.java) {
            LoggingConfigurationLoader.load(
                defaults,
                mapOf("BITBUCKET_HELPER_LOG_LEVEL" to "  sentinel-level  "),
            )
        }

        assertTrue(error.message!!.contains("BITBUCKET_HELPER_LOG_LEVEL"))
        assertFalse(error.message!!.contains("sentinel-level"))
    }

    @Test
    fun `unsupported level names only its setting`() {
        val error = assertThrows(StartupConfigurationException::class.java) {
            LoggingConfigurationLoader.load(
                defaults,
                mapOf("BITBUCKET_HELPER_LOG_LEVEL" to "verbose-sentinel"),
            )
        }

        assertTrue(error.message!!.contains("BITBUCKET_HELPER_LOG_LEVEL"))
        assertFalse(error.message!!.contains("verbose-sentinel"))
    }

    @Test
    fun `blank directory names only its setting`() {
        val error = assertThrows(StartupConfigurationException::class.java) {
            LoggingConfigurationLoader.load(
                defaults,
                mapOf("BITBUCKET_HELPER_LOG_DIRECTORY" to " "),
            )
        }

        assertTrue(error.message!!.contains("BITBUCKET_HELPER_LOG_DIRECTORY"))
        assertFalse(error.message!!.contains("/sentinel/private"))
    }

    @Test
    fun `missing final directory is created owner only below a secure parent`() {
        val parent = Files.createDirectory(directory.resolve("private"))
        Files.setPosixFilePermissions(parent, PosixFilePermissions.fromString("rwx------"))

        val prepared = SecureLoggingDirectory.prepare(parent.resolve("logs"))

        assertEquals(PosixFilePermissions.fromString("rwx------"), Files.getPosixFilePermissions(prepared))
        assertTrue(Files.isDirectory(prepared, LinkOption.NOFOLLOW_LINKS))
        assertFalse(Files.isSymbolicLink(prepared))
    }

    @Test
    fun `existing final directory must be owner only and writable`() {
        val logs = Files.createDirectory(directory.resolve("logs"))
        Files.setPosixFilePermissions(logs, PosixFilePermissions.fromString("rwx------"))

        val prepared = SecureLoggingDirectory.prepare(logs)

        assertEquals(logs.toAbsolutePath().normalize(), prepared)
        assertEquals(PosixFilePermissions.fromString("rwx------"), Files.getPosixFilePermissions(prepared))
    }

    @Test
    fun `existing symbolic link is rejected without naming its target`() {
        val target = Files.createDirectory(directory.resolve("real-logs"))
        Files.setPosixFilePermissions(target, PosixFilePermissions.fromString("rwx------"))
        val link = directory.resolve("logs-link")
        Files.createSymbolicLink(link, target.fileName)

        val error = assertThrows(StartupConfigurationException::class.java) {
            SecureLoggingDirectory.prepare(link)
        }

        assertTrue(error.message!!.contains("BITBUCKET_HELPER_LOG_DIRECTORY"))
        assertFalse(error.message!!.contains(target.toString()))
        assertTrue(Files.isSymbolicLink(link))
    }

    @Test
    fun `permissive existing directory is rejected`() {
        val logs = Files.createDirectory(directory.resolve("permissive-logs"))
        Files.setPosixFilePermissions(logs, PosixFilePermissions.fromString("rwxr-xr-x"))

        val error = assertThrows(StartupConfigurationException::class.java) {
            SecureLoggingDirectory.prepare(logs)
        }

        assertTrue(error.message!!.contains("BITBUCKET_HELPER_LOG_DIRECTORY"))
    }

    @Test
    fun `non-writable existing directory is rejected`() {
        val logs = Files.createDirectory(directory.resolve("read-only-logs"))
        Files.setPosixFilePermissions(logs, PosixFilePermissions.fromString("r-x------"))

        try {
            val error = assertThrows(StartupConfigurationException::class.java) {
                SecureLoggingDirectory.prepare(logs)
            }

            assertTrue(error.message!!.contains("BITBUCKET_HELPER_LOG_DIRECTORY"))
        } finally {
            Files.setPosixFilePermissions(logs, PosixFilePermissions.fromString("rwx------"))
        }
    }

    @Test
    fun `missing intermediate directory is not created`() {
        val missingParent = directory.resolve("missing-parent")
        val logs = missingParent.resolve("logs")

        val error = assertThrows(StartupConfigurationException::class.java) {
            SecureLoggingDirectory.prepare(logs)
        }

        assertTrue(error.message!!.contains("BITBUCKET_HELPER_LOG_DIRECTORY"))
        assertFalse(Files.exists(missingParent, LinkOption.NOFOLLOW_LINKS))
    }

    @Test
    fun `replacement-unsafe ancestry is rejected`() {
        val unsafeAncestor = Files.createDirectory(directory.resolve("unsafe-ancestor"))
        Files.setAttribute(unsafeAncestor, "unix:mode", 0x1FF)
        val parent = Files.createDirectory(unsafeAncestor.resolve("private"))
        Files.setPosixFilePermissions(parent, PosixFilePermissions.fromString("rwx------"))

        try {
            val error = assertThrows(StartupConfigurationException::class.java) {
                SecureLoggingDirectory.prepare(parent.resolve("logs"))
            }

            assertTrue(error.message!!.contains("BITBUCKET_HELPER_LOG_DIRECTORY"))
        } finally {
            Files.setAttribute(unsafeAncestor, "unix:mode", 0x1ED)
        }
    }

    @Test
    fun `existing regular file is rejected`() {
        val file = directory.resolve("logs")
        Files.writeString(file, "preserve")

        val error = assertThrows(StartupConfigurationException::class.java) {
            SecureLoggingDirectory.prepare(file)
        }

        assertTrue(error.message!!.contains("BITBUCKET_HELPER_LOG_DIRECTORY"))
        assertEquals("preserve", Files.readString(file))
    }
}

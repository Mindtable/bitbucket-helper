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
    @Test
    fun `pre logging configuration fallback exposes only the allowlisted setting code`() {
        val failure = StartupConfigurationException(
            "BITBUCKET_HELPER_LOG_LEVEL contains private invalid-value-sentinel",
        )

        assertEquals("BITBUCKET_HELPER_LOG_LEVEL", serviceConfigurationSettingCode(failure))
        assertFalse(serviceConfigurationSettingCode(failure).contains("invalid-value-sentinel"))
    }
    @TempDir
    lateinit var directory: Path

    private val defaults = ConfigFactory.parseResources("application.conf").resolve()
    private val canonicalDirectory: Path
        get() = directory.toRealPath()

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
                mapOf("BITBUCKET_HELPER_LOG_LEVEL" to "   "),
            )
        }

        assertTrue(error.message!!.contains("BITBUCKET_HELPER_LOG_LEVEL"))
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
        val parent = Files.createDirectory(canonicalDirectory.resolve("private"))
        Files.setPosixFilePermissions(parent, PosixFilePermissions.fromString("rwx------"))

        val prepared = SecureLoggingDirectory.prepare(parent.resolve("logs"))

        assertEquals(PosixFilePermissions.fromString("rwx------"), Files.getPosixFilePermissions(prepared))
        assertTrue(Files.isDirectory(prepared, LinkOption.NOFOLLOW_LINKS))
        assertFalse(Files.isSymbolicLink(prepared))
    }

    @Test
    fun `existing final directory must be owner only and writable`() {
        val logs = Files.createDirectory(canonicalDirectory.resolve("logs"))
        Files.setPosixFilePermissions(logs, PosixFilePermissions.fromString("rwx------"))

        val prepared = SecureLoggingDirectory.prepare(logs)

        assertEquals(logs.toRealPath(), prepared)
        assertEquals(PosixFilePermissions.fromString("rwx------"), Files.getPosixFilePermissions(prepared))
    }

    @Test
    fun `symlinked ancestor is rejected before canonicalization`() {
        val realParent = Files.createDirectory(canonicalDirectory.resolve("real-parent"))
        Files.setPosixFilePermissions(realParent, PosixFilePermissions.fromString("rwx------"))
        val linkedParent = canonicalDirectory.resolve("linked-parent")
        Files.createSymbolicLink(linkedParent, realParent.fileName)

        val error = assertThrows(StartupConfigurationException::class.java) {
            SecureLoggingDirectory.prepare(linkedParent.resolve("logs"))
        }

        assertTrue(error.message!!.contains("BITBUCKET_HELPER_LOG_DIRECTORY"))
        assertFalse(Files.exists(realParent.resolve("logs"), LinkOption.NOFOLLOW_LINKS))
        assertTrue(Files.isSymbolicLink(linkedParent))
    }

    @Test
    fun `parent replacement between validation and creation is rejected without following replacement`() {
        val parent = Files.createDirectory(canonicalDirectory.resolve("parent"))
        Files.setPosixFilePermissions(parent, PosixFilePermissions.fromString("rwx------"))
        val movedParent = canonicalDirectory.resolve("moved-parent")
        var redirectedStagingPath: Path? = null

        SecureLoggingDirectory.beforeStagingDirectoryCreateForTest = { stagingPath ->
            redirectedStagingPath = stagingPath
            Files.move(parent, movedParent)
            Files.createDirectory(parent)
            Files.setPosixFilePermissions(parent, PosixFilePermissions.fromString("rwx------"))
        }
        try {
            val error = assertThrows(StartupConfigurationException::class.java) {
                SecureLoggingDirectory.prepare(parent.resolve("logs"))
            }

            assertTrue(error.message!!.contains("BITBUCKET_HELPER_LOG_DIRECTORY"))
            assertFalse(Files.exists(parent.resolve("logs"), LinkOption.NOFOLLOW_LINKS))
            assertFalse(Files.exists(movedParent.resolve("logs"), LinkOption.NOFOLLOW_LINKS))
            assertTrue(redirectedStagingPath?.let { Files.exists(it, LinkOption.NOFOLLOW_LINKS) } == true)
        } finally {
            SecureLoggingDirectory.beforeStagingDirectoryCreateForTest = null
        }
    }

    @Test
    fun `parent replacement before secure promotion leaves no final directory`() {
        val parent = Files.createDirectory(canonicalDirectory.resolve("promotion-parent"))
        Files.setPosixFilePermissions(parent, PosixFilePermissions.fromString("rwx------"))
        val movedParent = canonicalDirectory.resolve("promotion-moved-parent")

        SecureLoggingDirectory.beforeSecurePromotionForTest = {
            Files.move(parent, movedParent)
            Files.createDirectory(parent)
            Files.setPosixFilePermissions(parent, PosixFilePermissions.fromString("rwx------"))
        }
        try {
            val error = assertThrows(StartupConfigurationException::class.java) {
                SecureLoggingDirectory.prepare(parent.resolve("logs"))
            }

            assertTrue(error.message!!.contains("BITBUCKET_HELPER_LOG_DIRECTORY"))
            assertFalse(Files.exists(parent.resolve("logs"), LinkOption.NOFOLLOW_LINKS))
            assertFalse(Files.exists(movedParent.resolve("logs"), LinkOption.NOFOLLOW_LINKS))
            Files.list(movedParent).use { entries ->
                assertFalse(entries.findAny().isPresent)
            }
        } finally {
            SecureLoggingDirectory.beforeSecurePromotionForTest = null
        }
    }

    @Test
    fun `existing symbolic link is rejected without naming its target`() {
        val target = Files.createDirectory(canonicalDirectory.resolve("real-logs"))
        Files.setPosixFilePermissions(target, PosixFilePermissions.fromString("rwx------"))
        val link = canonicalDirectory.resolve("logs-link")
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
        val logs = Files.createDirectory(canonicalDirectory.resolve("permissive-logs"))
        Files.setPosixFilePermissions(logs, PosixFilePermissions.fromString("rwxr-xr-x"))

        val error = assertThrows(StartupConfigurationException::class.java) {
            SecureLoggingDirectory.prepare(logs)
        }

        assertTrue(error.message!!.contains("BITBUCKET_HELPER_LOG_DIRECTORY"))
    }

    @Test
    fun `non-writable existing directory is rejected`() {
        val logs = Files.createDirectory(canonicalDirectory.resolve("read-only-logs"))
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
        val missingParent = canonicalDirectory.resolve("missing-parent")
        val logs = missingParent.resolve("logs")

        val error = assertThrows(StartupConfigurationException::class.java) {
            SecureLoggingDirectory.prepare(logs)
        }

        assertTrue(error.message!!.contains("BITBUCKET_HELPER_LOG_DIRECTORY"))
        assertFalse(Files.exists(missingParent, LinkOption.NOFOLLOW_LINKS))
    }

    @Test
    fun `replacement-unsafe ancestry is rejected`() {
        val unsafeAncestor = Files.createDirectory(canonicalDirectory.resolve("unsafe-ancestor"))
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
        val file = canonicalDirectory.resolve("logs")
        Files.writeString(file, "preserve")

        val error = assertThrows(StartupConfigurationException::class.java) {
            SecureLoggingDirectory.prepare(file)
        }

        assertTrue(error.message!!.contains("BITBUCKET_HELPER_LOG_DIRECTORY"))
        assertEquals("preserve", Files.readString(file))
    }
}

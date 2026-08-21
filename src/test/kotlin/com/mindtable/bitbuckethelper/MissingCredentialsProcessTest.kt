package com.mindtable.bitbuckethelper

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.util.concurrent.TimeUnit
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class MissingCredentialsProcessTest {
    @Test
    fun `missing username exits before database creation`(@TempDir directory: Path) {
        val token = "sentinel-api-token"

        val result = runFatJar(
            directory = directory,
            environment = mapOf("BITBUCKET_APP_PASSWORD" to token),
        )

        assertTrue(result.finishedWithinTenSeconds)
        assertNotEquals(0, result.exitCode)
        assertTrue(result.output.contains("BITBUCKET_USERNAME"))
        assertFalse(result.output.contains(token))
        assertFalse(Files.exists(directory.resolve(DATABASE_FILE_NAME)))
    }

    @Test
    fun `missing legacy-named token exits before database creation`(@TempDir directory: Path) {
        val username = "person@example.com"

        val result = runFatJar(
            directory = directory,
            environment = mapOf("BITBUCKET_USERNAME" to username),
        )

        assertTrue(result.finishedWithinTenSeconds)
        assertNotEquals(0, result.exitCode)
        assertTrue(result.output.contains("BITBUCKET_APP_PASSWORD"))
        assertFalse(result.output.contains(username))
        assertFalse(Files.exists(directory.resolve(DATABASE_FILE_NAME)))
    }

    private fun runFatJar(
        directory: Path,
        environment: Map<String, String>,
    ): ProcessResult {
        val fatJar = locateSingleFatJar()
        val canonicalDirectory = directory.toRealPath()
        val databasePath = canonicalDirectory.resolve(DATABASE_FILE_NAME)
        val logParent = Files.createDirectory(canonicalDirectory.resolve("log-parent"))
        Files.setPosixFilePermissions(logParent, PosixFilePermissions.fromString("rwx------"))
        val logDirectory = logParent.resolve("logs")
        val outputPath = canonicalDirectory.resolve("process-output.log")
        assertFalse(Files.exists(databasePath))

        val javaExecutable = ProcessHandle.current().info().command().orElseThrow {
            IllegalStateException("Current Java executable is unavailable")
        }
        val processBuilder = ProcessBuilder(
            javaExecutable,
            "-jar",
            fatJar.toAbsolutePath().normalize().toString(),
            "service",
            "run",
        )
            .redirectErrorStream(true)
            .redirectOutput(outputPath.toFile())
        processBuilder.environment().apply {
            remove("BITBUCKET_USERNAME")
            remove("BITBUCKET_APP_PASSWORD")
            putAll(environment)
            put("BITBUCKET_HELPER_DATABASE_PATH", databasePath.toAbsolutePath().normalize().toString())
            put("BITBUCKET_HELPER_LOG_DIRECTORY", logDirectory.toAbsolutePath().normalize().toString())
        }

        val process = processBuilder.start()
        try {
            val finished = process.waitFor(PROCESS_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            if (!finished) {
                stopSpawnedProcess(process)
                val output = readOutput(outputPath)
                fail<Unit>(
                    "Fat JAR did not exit within ten seconds. Captured output:\n" +
                        sanitize(output, environment.values),
                )
            }

            val output = readOutput(outputPath)
            environment.values.forEach { credential ->
                assertFalse(
                    output.contains(credential),
                    "Spawned process output exposed the configured credential",
                )
            }
            return ProcessResult(
                finishedWithinTenSeconds = true,
                exitCode = process.exitValue(),
                output = output,
            )
        } finally {
            if (process.isAlive) stopSpawnedProcess(process)
        }
    }

    private fun stopSpawnedProcess(process: Process) {
        process.destroy()
        if (!process.waitFor(PROCESS_STOP_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            check(process.waitFor(PROCESS_STOP_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                "Spawned process did not terminate"
            }
        }
    }

    private fun readOutput(path: Path): String =
        if (Files.exists(path)) Files.readString(path, UTF_8) else ""

    private fun sanitize(output: String, credentials: Collection<String>): String =
        credentials.fold(output) { sanitized, credential ->
            sanitized.replace(credential, "<redacted>")
        }

    private data class ProcessResult(
        val finishedWithinTenSeconds: Boolean,
        val exitCode: Int,
        val output: String,
    )

    private companion object {
        const val DATABASE_FILE_NAME = "state.sqlite"
        const val PROCESS_TIMEOUT_SECONDS = 10L
        const val PROCESS_STOP_TIMEOUT_SECONDS = 2L
    }
}

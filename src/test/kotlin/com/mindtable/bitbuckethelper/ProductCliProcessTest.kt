package com.mindtable.bitbuckethelper

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.cio.CIO
import io.ktor.server.cio.unixConnector
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.request.httpMethod
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondBytes
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import kotlin.io.path.deleteIfExists
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir

class ProductCliProcessTest {
    @Test
    @Timeout(value = 45, unit = TimeUnit.SECONDS)
    fun `real product CLI preserves read and mutation JSON documents and documented exits`(
        @TempDir directory: Path,
    ) {
        val readDocument = """
            {
              "result" : {
                "configuration" : {
                  "workspaceDisplayName" : "Команда Ω",
                  "repositories" : [ ],
                  "retentionDays" : 30,
                  "workspaceWebUrl" : "https://bitbucket.org/acme-engineering",
                  "workspaceSlug" : "acme-engineering",
                  "bitbucketApiBaseUrl" : "https://api.bitbucket.org/2.0",
                  "workspaceId" : "ws_22222222-2222-2222-2222-222222222222"
                },
                "type" : "workspaceConfigured"
              },
              "requestId" : "req_process_read",
              "apiVersion" : "1"
            }
        """.trimIndent()
        val mutationDocument = """
            { "result" : {
                "repositoryId" : "repo_process-1",
                "type" : "repositoryRemoved"
              },
              "apiVersion" : "1", "requestId" : "req_process_mutation" }
        """.trimIndent()
        val businessDocument = """
            { "requestId" : "req_process_business",
              "result" : { "repositoryId" : "repo_process-1",
                "type" : "repositoryNotConfigured" }, "apiVersion" : "1" }
        """.trimIndent()
        val requestErrorDocument =
            "{ \"error\" : { \"violations\" : [ ], \"message\" : \"Cafe\u0301 Ω\", \"code\" : \"INVALID_REQUEST\" }, \"requestId\" : \"req_process_unicode_4xx\", \"apiVersion\" : \"1\" }"

        val cases = listOf(
            ProcessCase(
                name = "read",
                arguments = listOf("workspace", "show", "--output", "json"),
                expectedRequest = CapturedRequest("GET", "/api/v1/configuration/workspace", null),
                responseDocument = readDocument,
                expectedExit = 0,
            ),
            ProcessCase(
                name = "mutation",
                arguments = listOf("repository", "remove", "repo_process-1", "--output", "json"),
                expectedRequest = CapturedRequest(
                    "DELETE",
                    "/api/v1/configuration/workspace/repositories/repo_process-1",
                    null,
                ),
                responseDocument = mutationDocument,
                expectedExit = 0,
            ),
            ProcessCase(
                name = "typed-business-outcome",
                arguments = listOf("repository", "remove", "repo_process-1", "--output", "json"),
                expectedRequest = CapturedRequest(
                    "DELETE",
                    "/api/v1/configuration/workspace/repositories/repo_process-1",
                    null,
                ),
                responseDocument = businessDocument,
                expectedExit = 3,
            ),
            ProcessCase(
                name = "request-error",
                arguments = listOf("workspace", "show", "--output", "json"),
                expectedRequest = CapturedRequest("GET", "/api/v1/configuration/workspace", null),
                responseDocument = requestErrorDocument,
                responseStatus = HttpStatusCode.BadRequest,
                expectedExit = 4,
            ),
        )

        cases.forEach { case ->
            val caseDirectory = Files.createDirectory(directory.resolve(case.name))
            Files.setPosixFilePermissions(caseDirectory, PosixFilePermissions.fromString("rwx------"))
            val socketDirectory = Files.createTempDirectory("bbh-cli-")
            Files.setPosixFilePermissions(socketDirectory, PosixFilePermissions.fromString("rwx------"))
            UnixFixtureServer.start(
                socketDirectory,
                case.expectedRequest.method,
                case.expectedRequest.path,
                case.responseDocument,
                case.responseStatus,
            ).use { server ->
                val result = runFatJar(caseDirectory, server.socketPath, case.arguments)

                assertTrue(result.finished, "${case.name} process timed out")
                assertEquals(case.expectedExit, result.exitCode, result.combinedOutput())
                assertArrayEquals(
                    (case.responseDocument + "\n").toByteArray(UTF_8),
                    result.standardOut,
                    case.name,
                )
                assertEquals("", result.standardErrText(), case.name)
                assertFalse(result.standardErrText().contains("req_process_"), case.name)
                assertFalse(result.standardErrText().contains("secret"), case.name)
                assertFalse(result.standardErrText().contains(V1TestRig.RAW_ACTIVITY_MARKER), case.name)
                assertFalse(result.standardErrText().contains(V1TestRig.LIVE_MARKDOWN), case.name)
                assertEquals(listOf(case.expectedRequest), server.requests, case.name)
            }
        }
    }

    private fun runFatJar(directory: Path, socketPath: Path, arguments: List<String>): ProcessResult {
        val fatJar = locateSingleFatJar()
        val javaExecutable = ProcessHandle.current().info().command().orElseThrow()
        val standardOut = directory.resolve("stdout.log")
        val standardErr = directory.resolve("stderr.log")
        val process = ProcessBuilder(
            javaExecutable,
            "--enable-native-access=ALL-UNNAMED",
            "-jar",
            fatJar.toAbsolutePath().normalize().toString(),
            *arguments.toTypedArray(),
        )
            .redirectOutput(standardOut.toFile())
            .redirectError(standardErr.toFile())
            .apply {
                environment()["BITBUCKET_HELPER_UNIX_SOCKET_PATH"] = socketPath.toAbsolutePath().normalize().toString()
            }
            .start()
        val finished = process.waitFor(PROCESS_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        if (!finished) {
            process.destroy()
            if (!process.waitFor(PROCESS_STOP_SECONDS, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                process.waitFor(PROCESS_STOP_SECONDS, TimeUnit.SECONDS)
            }
        }
        return ProcessResult(
            finished = finished,
            exitCode = if (finished) process.exitValue() else -1,
            standardOut = Files.readAllBytes(standardOut),
            standardErr = Files.readAllBytes(standardErr),
        )
    }

    private fun locateSingleFatJar(): Path {
        val directory = Path.of(System.getProperty("user.dir"), "build", "libs")
        val jars = Files.list(directory).use { paths ->
            paths.filter { Files.isRegularFile(it) && it.fileName.toString().endsWith("-all.jar") }.toList()
        }
        assertEquals(1, jars.size, "Expected exactly one current *-all.jar")
        return jars.single()
    }

    private data class ProcessCase(
        val name: String,
        val arguments: List<String>,
        val expectedRequest: CapturedRequest,
        val responseDocument: String,
        val responseStatus: HttpStatusCode = HttpStatusCode.OK,
        val expectedExit: Int,
    )

    private data class ProcessResult(
        val finished: Boolean,
        val exitCode: Int,
        val standardOut: ByteArray,
        val standardErr: ByteArray,
    ) {
        fun standardErrText(): String = String(standardErr, UTF_8)
        fun combinedOutput(): String =
            "stdout:\n${String(standardOut, UTF_8)}\nstderr:\n${standardErrText()}"
    }

    private data class CapturedRequest(val method: String, val path: String, val body: String?)

    private class UnixFixtureServer(
        private val directory: Path,
        val socketPath: Path,
        val requests: List<CapturedRequest>,
        private val server: EmbeddedServer<*, *>,
    ) : AutoCloseable {
        override fun close() {
            server.stop(0, 0)
            socketPath.deleteIfExists()
            directory.deleteIfExists()
        }

        companion object {
            fun start(
                directory: Path,
                method: String,
                path: String,
                responseDocument: String,
                responseStatus: HttpStatusCode,
            ): UnixFixtureServer {
                val socketPath = directory.resolve("product.sock")
                val requests = CopyOnWriteArrayList<CapturedRequest>()
                val server = embeddedServer(CIO, configure = { unixConnector(socketPath.toString()) }) {
                    routing {
                        when (method) {
                            "GET" -> get(path) {
                                requests += CapturedRequest(
                                    call.request.httpMethod.value,
                                    path,
                                    call.receiveText().takeIf(String::isNotEmpty),
                                )
                                call.respondBytes(
                                    responseDocument.toByteArray(UTF_8),
                                    ContentType.Application.Json,
                                    responseStatus,
                                )
                            }
                            "DELETE" -> delete(path) {
                                requests += CapturedRequest(
                                    call.request.httpMethod.value,
                                    path,
                                    call.receiveText().takeIf(String::isNotEmpty),
                                )
                                call.respondBytes(
                                    responseDocument.toByteArray(UTF_8),
                                    ContentType.Application.Json,
                                    responseStatus,
                                )
                            }
                            else -> error("unsupported fixture method $method")
                        }
                    }
                }.start(wait = false)
                try {
                    awaitSocketReadiness(socketPath)
                    return UnixFixtureServer(directory, socketPath, requests, server)
                } catch (failure: Throwable) {
                    runCatching { server.stop(0, 0) }.exceptionOrNull()?.let(failure::addSuppressed)
                    runCatching { socketPath.deleteIfExists() }.exceptionOrNull()?.let(failure::addSuppressed)
                    runCatching { directory.deleteIfExists() }.exceptionOrNull()?.let(failure::addSuppressed)
                    throw failure
                }
            }

            private fun awaitSocketReadiness(socketPath: Path) {
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(SOCKET_READY_TIMEOUT_SECONDS)
                while (!Files.exists(socketPath)) {
                    check(System.nanoTime() < deadline) {
                        "Unix fixture socket was not bound within ${SOCKET_READY_TIMEOUT_SECONDS}s"
                    }
                    Thread.sleep(SOCKET_READY_POLL_MILLIS)
                }
            }

            private const val SOCKET_READY_TIMEOUT_SECONDS = 3L
            private const val SOCKET_READY_POLL_MILLIS = 10L
        }
    }

    private companion object {
        const val PROCESS_TIMEOUT_SECONDS = 15L
        const val PROCESS_STOP_SECONDS = 2L
    }
}

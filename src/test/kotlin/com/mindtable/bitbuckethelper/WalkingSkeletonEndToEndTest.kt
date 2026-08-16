package com.mindtable.bitbuckethelper

import com.mindtable.bitbuckethelper.bootstrap.BitbucketCredentials
import com.mindtable.bitbuckethelper.bootstrap.ServiceConfiguration
import com.mindtable.bitbuckethelper.bootstrap.ServiceRuntime
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.net.InetSocketAddress
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets.ISO_8859_1
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.Base64
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir

class WalkingSkeletonEndToEndTest {
    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    fun `immediate Quartz refresh crosses generated client SQLite and Ktor`(
        @TempDir directory: Path,
    ) {
        val token = "sentinel-api-token"
        val username = "person@example.com"
        val authorization = "Basic " + Base64.getEncoder()
            .encodeToString("$username:$token".toByteArray(UTF_8))
        val databasePath = directory.resolve("state.sqlite")
        val fakeBitbucket = FakeBitbucketServer.success(
            requireNotNull(javaClass.getResource("/bitbucket/current-user-success.json")).readText(),
        )

        try {
            val captured = captureDiagnostics {
                runBlocking {
                    withTimeout(Duration.ofSeconds(20).toMillis()) {
                        var runtime: ServiceRuntime? = null
                        try {
                            runtime = ServiceRuntime.create(
                                configuration = ServiceConfiguration(
                                    httpHost = "127.0.0.1",
                                    httpPort = 0,
                                    databasePath = databasePath,
                                    refreshInterval = Duration.ofMinutes(15),
                                    bitbucketBaseUrl = fakeBitbucket.baseUrl,
                                    bitbucketRequestTimeout = Duration.ofSeconds(2),
                                    credentials = BitbucketCredentials(username, token),
                                ),
                                clock = Clock.fixed(
                                    Instant.parse("2026-08-15T10:15:30Z"),
                                    ZoneOffset.UTC,
                                ),
                            )
                            runtime.start()
                            val port = runtime.resolvedHttpPort()
                            HttpClient.newBuilder()
                                .connectTimeout(Duration.ofSeconds(1))
                                .build()
                                .use { client ->
                                    val response = eventuallyWithin(Duration.ofSeconds(5)) {
                                        runCatching {
                                            client.send(
                                                HttpRequest.newBuilder(
                                                    URI("http://127.0.0.1:$port/api/v1/bitbucket/status"),
                                                )
                                                    .timeout(Duration.ofSeconds(1))
                                                    .GET()
                                                    .build(),
                                                HttpResponse.BodyHandlers.ofString(UTF_8),
                                            )
                                        }.getOrNull()?.takeIf {
                                            it.statusCode() == 200 &&
                                                Json.parseToJsonElement(it.body())
                                                    .jsonObject["state"]
                                                    ?.toString() == "\"healthy\""
                                        }
                                    }

                                    WalkingSkeletonObservation(
                                        httpStatus = response.statusCode(),
                                        responseBody = response.body(),
                                        database = readDatabaseSnapshot(databasePath),
                                    )
                                }
                        } finally {
                            runtime?.close()
                        }
                    }
                }
            }

            val thrownDiagnostics = captured.result.exceptionOrNull()
                ?.stackTraceToString()
                .orEmpty()
            val observableDiagnostics = listOf(
                captured.standardOut,
                captured.standardErr,
                thrownDiagnostics,
            ).joinToString("\n")
            assertCredentialAbsent(observableDiagnostics, token, authorization)
            assertNull(captured.result.exceptionOrNull(), "walking skeleton threw a diagnostic")

            val observation = requireNotNull(captured.result.getOrNull())
            assertCredentialAbsent(observation.responseBody, token, authorization)
            assertCredentialAbsent(
                observation.database.everyColumnValue.joinToString("\n"),
                token,
                authorization,
            )
            assertTrue(Files.isRegularFile(databasePath))
            assertCredentialAbsent(
                Files.readString(databasePath, ISO_8859_1),
                token,
                authorization,
            )
            assertEquals(200, observation.httpStatus)
            assertEquals(
                Json.parseToJsonElement(
                    """
                    {
                      "schemaVersion": 1,
                      "state": "healthy",
                      "lastAttemptAt": "2026-08-15T10:15:30Z",
                      "lastSuccessAt": "2026-08-15T10:15:30Z",
                      "account": {
                        "uuid": "{11111111-1111-1111-1111-111111111111}",
                        "displayName": "Ada Lovelace",
                        "nickname": null
                      },
                      "failure": null
                    }
                    """.trimIndent(),
                ),
                Json.parseToJsonElement(observation.responseBody),
            )
            assertEquals("GET", fakeBitbucket.capturedMethod)
            assertTrue(
                authorization == fakeBitbucket.capturedAuthorization,
                "fake Bitbucket server did not receive the exact Basic authorization",
            )
            assertEquals(
                DatabaseSnapshot(
                    singletonId = 1,
                    state = "healthy",
                    accountUuid = "{11111111-1111-1111-1111-111111111111}",
                    displayName = "Ada Lovelace",
                    nickname = null,
                    lastAttemptAt = "2026-08-15T10:15:30Z",
                    lastSuccessAt = "2026-08-15T10:15:30Z",
                    failureCode = null,
                    failureMessage = null,
                    everyColumnValue = observation.database.everyColumnValue,
                ),
                observation.database,
            )
        } finally {
            fakeBitbucket.close()
        }
    }

    private suspend fun <T : Any> eventuallyWithin(
        timeout: Duration,
        condition: suspend () -> T?,
    ): T {
        val deadline = System.nanoTime() + timeout.toNanos()
        while (true) {
            condition()?.let { return it }
            val remainingNanos = deadline - System.nanoTime()
            if (remainingNanos <= 0) {
                throw AssertionError("Condition was not satisfied before the monotonic deadline")
            }
            delay(minOf(25L, (remainingNanos / 1_000_000L).coerceAtLeast(1L)))
        }
    }

    private fun readDatabaseSnapshot(path: Path): DatabaseSnapshot =
        DriverManager.getConnection("jdbc:sqlite:${path.toAbsolutePath().normalize()}").use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery("SELECT * FROM bitbucket_connection_snapshot").use { result ->
                    check(result.next()) { "Expected the singleton connection snapshot" }
                    val metadata = result.metaData
                    val everyColumnValue = (1..metadata.columnCount).mapNotNull(result::getString)
                    DatabaseSnapshot(
                        singletonId = result.getInt("singleton_id"),
                        state = result.getString("state"),
                        accountUuid = result.getString("account_uuid"),
                        displayName = result.getString("display_name"),
                        nickname = result.getString("nickname"),
                        lastAttemptAt = result.getString("last_attempt_at"),
                        lastSuccessAt = result.getString("last_success_at"),
                        failureCode = result.getString("failure_code"),
                        failureMessage = result.getString("failure_message"),
                        everyColumnValue = everyColumnValue,
                    )
                }
            }
        }

    private fun <T> captureDiagnostics(block: () -> T): CapturedExecution<T> {
        val standardOut = ByteArrayOutputStream()
        val standardErr = ByteArrayOutputStream()
        val originalOut = System.out
        val originalErr = System.err
        val replacementOut = PrintStream(standardOut, true, UTF_8)
        val replacementErr = PrintStream(standardErr, true, UTF_8)
        System.setOut(replacementOut)
        System.setErr(replacementErr)
        return try {
            val result = runCatching(block)
            replacementOut.flush()
            replacementErr.flush()
            CapturedExecution(
                result = result,
                standardOut = standardOut.toString(UTF_8),
                standardErr = standardErr.toString(UTF_8),
            )
        } finally {
            System.setOut(originalOut)
            System.setErr(originalErr)
            replacementOut.close()
            replacementErr.close()
        }
    }

    private fun assertCredentialAbsent(text: String, token: String, authorization: String) {
        assertFalse(text.contains(token), "token escaped its request-header boundary")
        assertFalse(text.contains(authorization), "Authorization header escaped the fake server")
    }

    private data class WalkingSkeletonObservation(
        val httpStatus: Int,
        val responseBody: String,
        val database: DatabaseSnapshot,
    )

    private data class DatabaseSnapshot(
        val singletonId: Int,
        val state: String,
        val accountUuid: String?,
        val displayName: String?,
        val nickname: String?,
        val lastAttemptAt: String,
        val lastSuccessAt: String?,
        val failureCode: String?,
        val failureMessage: String?,
        val everyColumnValue: List<String>,
    )

    private data class CapturedExecution<T>(
        val result: Result<T>,
        val standardOut: String,
        val standardErr: String,
    )

    private class FakeBitbucketServer private constructor(
        private val server: HttpServer,
        private val method: AtomicReference<String?>,
        private val authorization: AtomicReference<String?>,
    ) : AutoCloseable {
        val baseUrl: URI = URI("http://127.0.0.1:${server.address.port}/2.0")
        val capturedMethod: String? get() = method.get()
        val capturedAuthorization: String? get() = authorization.get()

        override fun close() = server.stop(0)

        companion object {
            fun success(body: String): FakeBitbucketServer {
                val method = AtomicReference<String?>()
                val authorization = AtomicReference<String?>()
                val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
                server.createContext("/2.0/user") { exchange ->
                    try {
                        method.set(exchange.requestMethod)
                        authorization.set(exchange.requestHeaders.getFirst("Authorization"))
                        exchange.respond(200, body)
                    } finally {
                        exchange.close()
                    }
                }
                server.start()
                return FakeBitbucketServer(server, method, authorization)
            }
        }
    }
}

private fun HttpExchange.respond(status: Int, body: String) {
    val bytes = body.toByteArray(UTF_8)
    responseHeaders.set("Content-Type", "application/json")
    sendResponseHeaders(status, bytes.size.toLong())
    responseBody.use { it.write(bytes) }
}

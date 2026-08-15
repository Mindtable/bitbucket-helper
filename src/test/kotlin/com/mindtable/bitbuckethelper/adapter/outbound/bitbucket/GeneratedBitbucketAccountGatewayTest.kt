package com.mindtable.bitbuckethelper.adapter.outbound.bitbucket

import com.mindtable.bitbuckethelper.application.model.BitbucketAccount
import com.mindtable.bitbuckethelper.application.model.BitbucketAccountResult
import com.mindtable.bitbuckethelper.application.model.ConnectionFailure
import com.mindtable.bitbuckethelper.application.model.ConnectionFailureCode
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.cio.CIO
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.URI
import java.nio.charset.StandardCharsets.UTF_8
import java.time.Duration
import java.util.Base64
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import java.util.logging.Handler
import java.util.logging.LogRecord
import java.util.logging.Logger
import java.util.stream.Stream
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource

class GeneratedBitbucketAccountGatewayTest {
    @Test
    fun `generated client sends current-user GET with precomputed Basic auth and maps the complete fixture`() = runBlocking {
        val capturedMethod = AtomicReference<String>()
        val capturedAuthorization = AtomicReference<String>()
        val fixture = requireNotNull(javaClass.getResource("/bitbucket/current-user-success.json")).readText()
        withServer(handler = { exchange ->
            capturedMethod.set(exchange.requestMethod)
            capturedAuthorization.set(exchange.requestHeaders.getFirst("Authorization"))
            exchange.respond(200, fixture)
        }) { baseUrl ->
            val token = "sentinel-api-token"
            val gateway = GeneratedBitbucketAccountGateway.create(
                baseUrl = baseUrl,
                requestTimeout = Duration.ofSeconds(2),
                username = "person@example.com",
                apiToken = token,
            )

            val result = gateway.use { it.fetchCurrentAccount() }

            assertEquals("GET", capturedMethod.get())
            assertEquals(
                "Basic " + Base64.getEncoder()
                    .encodeToString("person@example.com:$token".toByteArray(UTF_8)),
                capturedAuthorization.get(),
            )
            assertEquals(
                BitbucketAccountResult.Success(
                    BitbucketAccount("{account-uuid}", "Ada Lovelace", null),
                ),
                result,
            )
        }
    }

    @ParameterizedTest(name = "HTTP {0} maps to {1} without exposing the response body")
    @MethodSource("httpFailures")
    fun `non-success status is mapped before its credential-bearing body can escape`(
        status: Int,
        expectedCode: ConnectionFailureCode,
        expectedMessage: String,
    ) = runBlocking {
        val responseBody =
            """{"detail":"sentinel-api-token","Authorization":"Basic c2VudGluZWw6YXBpLXRva2Vu"}"""
        withServer(handler = { exchange ->
            exchange.addCredentialBearingHeaders()
            exchange.respond(status, responseBody)
        }) { baseUrl ->
            val gateway = GeneratedBitbucketAccountGateway.create(
                baseUrl = baseUrl,
                requestTimeout = Duration.ofSeconds(2),
                username = "person@example.com",
                apiToken = "sentinel-api-token",
            )

            val (attempt, diagnostics) = captureDiagnostics {
                gateway.use { currentGateway ->
                    runCatching { currentGateway.fetchCurrentAccount() }
                }
            }

            assertNull(attempt.exceptionOrNull())
            assertEquals(
                BitbucketAccountResult.Failure(ConnectionFailure(expectedCode, expectedMessage)),
                attempt.getOrNull(),
            )
            val observableText = listOf(
                attempt.getOrNull().toString(),
                attempt.exceptionOrNull()?.stackTraceToString().orEmpty(),
                diagnostics,
            ).joinToString("\n")
            assertFalse(observableText.contains("sentinel-api-token"))
            assertFalse(observableText.contains("Authorization"))
        }
    }

    @Test
    fun `malformed credential-bearing success body maps to sanitized unexpected failure`() = runBlocking {
        val responseBody =
            """{"type":"user","display_name":"sentinel-api-token","Authorization":"Basic sentinel-api-token""""
        withServer(handler = { exchange ->
            exchange.addCredentialBearingHeaders()
            exchange.respond(200, responseBody)
        }) { baseUrl ->
            val gateway = GeneratedBitbucketAccountGateway.create(
                baseUrl = baseUrl,
                requestTimeout = Duration.ofSeconds(2),
                username = "person@example.com",
                apiToken = "sentinel-api-token",
            )

            val (attempt, diagnostics) = captureDiagnostics {
                gateway.use { currentGateway ->
                    runCatching { currentGateway.fetchCurrentAccount() }
                }
            }

            assertNull(attempt.exceptionOrNull())
            assertEquals(
                BitbucketAccountResult.Failure(
                    ConnectionFailure(
                        ConnectionFailureCode.UNEXPECTED,
                        "Bitbucket request failed unexpectedly",
                    ),
                ),
                attempt.getOrNull(),
            )
            val observableText = listOf(
                attempt.getOrNull().toString(),
                attempt.exceptionOrNull()?.stackTraceToString().orEmpty(),
                diagnostics,
            ).joinToString("\n")
            assertFalse(observableText.contains("sentinel-api-token"))
            assertFalse(observableText.contains("Authorization"))
        }
    }

    @Test
    fun `request timeout is bounded and maps without waiting for the server handler`() = runBlocking {
        val received = CountDownLatch(1)
        val release = CountDownLatch(1)
        try {
            withServer(handler = { exchange ->
                received.countDown()
                release.await(5, TimeUnit.SECONDS)
                runCatching { exchange.respond(200, "{}") }
            }) { baseUrl ->
                val gateway = GeneratedBitbucketAccountGateway.create(
                    baseUrl = baseUrl,
                    requestTimeout = Duration.ofMillis(150),
                    username = "person@example.com",
                    apiToken = "test-token",
                )

                val result = gateway.use {
                    withTimeout(2_000) { it.fetchCurrentAccount() }
                }

                assertTrue(received.await(1, TimeUnit.SECONDS))
                assertEquals(
                    BitbucketAccountResult.Failure(
                        ConnectionFailure(ConnectionFailureCode.TIMEOUT, "Bitbucket request timed out"),
                    ),
                    result,
                )
            }
        } finally {
            release.countDown()
        }
    }

    @Test
    fun `refused loopback connection maps to a stable network failure`() = runBlocking {
        val closedPort = ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress()).use {
            it.localPort
        }
        val gateway = GeneratedBitbucketAccountGateway.create(
            baseUrl = URI("http://127.0.0.1:$closedPort/2.0"),
            requestTimeout = Duration.ofMillis(500),
            username = "person@example.com",
            apiToken = "test-token",
        )

        val result = gateway.use {
            withTimeout(2_000) { it.fetchCurrentAccount() }
        }

        assertEquals(
            BitbucketAccountResult.Failure(
                ConnectionFailure(ConnectionFailureCode.NETWORK, "Bitbucket is unreachable"),
            ),
            result,
        )
    }

    @Test
    fun `retained engine can be closed idempotently after generated client initialization`() = runBlocking {
        val requests = AtomicInteger()
        val engine = CloseTrackingEngine()
        val fixture = requireNotNull(javaClass.getResource("/bitbucket/current-user-success.json")).readText()
        withServer(handler = { exchange ->
            requests.incrementAndGet()
            exchange.respond(200, fixture)
        }) { baseUrl ->
            val gateway = GeneratedBitbucketAccountGateway.create(
                baseUrl = baseUrl,
                requestTimeout = Duration.ofMillis(500),
                username = "person@example.com",
                apiToken = "test-token",
                engine = engine,
            )
            try {
                assertTrue(gateway.fetchCurrentAccount() is BitbucketAccountResult.Success)

                gateway.close()
                gateway.close()

                assertEquals(1, requests.get())
                assertEquals(1, engine.closeCalls.get())
            } finally {
                gateway.close()
            }
        }
    }

    @Test
    fun `overflowing timeout conversion closes the supplied retained engine exactly once`() {
        val engine = CloseTrackingEngine()

        assertThrows(ArithmeticException::class.java) {
            GeneratedBitbucketAccountGateway.create(
                baseUrl = URI("http://127.0.0.1:1/2.0"),
                requestTimeout = Duration.ofSeconds(Long.MAX_VALUE),
                username = "person@example.com",
                apiToken = "test-token",
                engine = engine,
            )
        }

        assertEquals(1, engine.closeCalls.get())
    }

    private suspend fun withServer(
        handler: (HttpExchange) -> Unit,
        block: suspend (URI) -> Unit,
    ) {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/2.0/user", handler)
        server.start()
        try {
            block(URI("http://127.0.0.1:${server.address.port}/2.0"))
        } finally {
            server.stop(0)
        }
    }

    private suspend fun <T> captureDiagnostics(block: suspend () -> T): Pair<T, String> {
        val output = ByteArrayOutputStream()
        val originalOut = System.out
        val originalErr = System.err
        val printStream = PrintStream(output, true, UTF_8)
        val rootLogger = Logger.getLogger("")
        val handler = object : Handler() {
            override fun publish(record: LogRecord) {
                output.write(record.message.orEmpty().toByteArray(UTF_8))
                record.thrown?.stackTraceToString()?.let { output.write(it.toByteArray(UTF_8)) }
            }

            override fun flush() = Unit

            override fun close() = Unit
        }
        rootLogger.addHandler(handler)
        System.setOut(printStream)
        System.setErr(printStream)
        return try {
            block() to output.toString(UTF_8)
        } finally {
            System.setOut(originalOut)
            System.setErr(originalErr)
            rootLogger.removeHandler(handler)
            printStream.close()
        }
    }

    private fun HttpExchange.respond(status: Int, body: String) {
        val bytes = body.toByteArray(UTF_8)
        responseHeaders.set("Content-Type", "application/json")
        sendResponseHeaders(status, bytes.size.toLong())
        responseBody.use { it.write(bytes) }
    }

    private fun HttpExchange.addCredentialBearingHeaders() {
        responseHeaders.set("X-Sentinel", "sentinel-api-token")
        responseHeaders.set("Authorization", "Basic c2VudGluZWw6YXBpLXRva2Vu")
    }

    companion object {
        @JvmStatic
        fun httpFailures(): Stream<Arguments> = Stream.of(
            Arguments.of(
                401,
                ConnectionFailureCode.AUTHENTICATION,
                "Bitbucket rejected the credentials",
            ),
            Arguments.of(
                403,
                ConnectionFailureCode.AUTHORIZATION,
                "Bitbucket denied the required permission",
            ),
            Arguments.of(
                429,
                ConnectionFailureCode.RATE_LIMITED,
                "Bitbucket rate limit exceeded",
            ),
            Arguments.of(
                500,
                ConnectionFailureCode.UPSTREAM,
                "Bitbucket service failed",
            ),
            Arguments.of(
                418,
                ConnectionFailureCode.UNEXPECTED,
                "Bitbucket request failed unexpectedly",
            ),
        )
    }

    private class CloseTrackingEngine(
        private val delegate: HttpClientEngine = CIO.create(),
    ) : HttpClientEngine by delegate {
        val closeCalls = AtomicInteger()

        override fun close() {
            closeCalls.incrementAndGet()
            delegate.close()
        }
    }
}

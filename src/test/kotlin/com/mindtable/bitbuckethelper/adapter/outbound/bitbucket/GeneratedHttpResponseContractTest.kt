package com.mindtable.bitbuckethelper.adapter.outbound.bitbucket

import com.mindtable.bitbuckethelper.adapter.outbound.bitbucket.generated.api.UsersApi
import com.mindtable.bitbuckethelper.adapter.outbound.bitbucket.generated.infrastructure.BodyProvider
import com.mindtable.bitbuckethelper.adapter.outbound.bitbucket.generated.infrastructure.HttpResponse as GeneratedHttpResponse
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import io.ktor.client.call.HttpClientCall
import io.ktor.client.engine.cio.CIO
import io.ktor.http.Headers
import io.ktor.http.HttpProtocolVersion
import io.ktor.http.HttpStatusCode
import io.ktor.util.date.GMTDate
import io.ktor.util.reflect.TypeInfo
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.InternalAPI
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets.UTF_8
import java.util.Base64
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.EmptyCoroutineContext
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class GeneratedHttpResponseContractTest {
    @Test
    fun `generated response exposes opaque pagination metadata without reading the body`() {
        val headerCopies = AtomicInteger()
        val nextLink = "<https://api.bitbucket.test/opaque-next>; rel=\"next\""
        val generated = GeneratedHttpResponse(
            response = responseWithTrackedHeaders(headerCopies, nextLink),
            provider = unusedBodyProvider(),
        )

        assertEquals(0, headerCopies.get())

        assertEquals(listOf(nextLink), generated.headers["Link"])
        assertEquals(1, headerCopies.get())
        assertEquals(generated.headers, generated.headers)
        assertEquals(1, headerCopies.get())
    }

    @Test
    fun `generated client failures do not expose authentication or response body text`() = runBlocking {
        val credentialSentinel = "credential-sentinel-71f2"
        val bodySentinel = "body-sentinel-8c4a"
        val capturedAuthorization = AtomicReference<String>()
        val malformedBody = """{"detail":"$bodySentinel","credential":"$credentialSentinel""""

        withServer(handler = { exchange ->
            capturedAuthorization.set(exchange.requestHeaders.getFirst("Authorization"))
            exchange.respond(401, malformedBody)
        }) { baseUrl ->
            val engine = CIO.create()
            try {
                val client = UsersApi(baseUrl = baseUrl, httpClientEngine = engine).apply {
                    setUsername("generated-client-test")
                    setPassword(credentialSentinel)
                }

                val response = client.getCurrentUser()
                val bodyFailure = runCatching { response.body() }
                val expectedAuthorization = "Basic " + Base64.getEncoder().encodeToString(
                    "generated-client-test:$credentialSentinel".toByteArray(UTF_8),
                )

                assertTrue(capturedAuthorization.get() == expectedAuthorization)
                assertEquals(401, response.status)
                assertFalse(response.success)
                assertTrue(bodyFailure.isFailure)

                val observableFailure = listOf(
                    response.toString(),
                    response.response.toString(),
                    bodyFailure.toString(),
                    bodyFailure.exceptionOrNull()?.stackTraceToString().orEmpty(),
                ).joinToString("\n")
                assertFalse(observableFailure.contains(credentialSentinel))
                assertFalse(observableFailure.contains(bodySentinel))
            } finally {
                engine.close()
            }
        }
    }

    @OptIn(InternalAPI::class)
    private fun responseWithTrackedHeaders(headerCopies: AtomicInteger, nextLink: String) =
        object : io.ktor.client.statement.HttpResponse() {
            override val call: HttpClientCall
                get() = error("Body access is outside this contract")
            override val status = HttpStatusCode.Unauthorized
            override val version = HttpProtocolVersion.HTTP_1_1
            override val requestTime = GMTDate.START
            override val responseTime = GMTDate.START
            override val rawContent = ByteReadChannel.Empty
            override val coroutineContext = EmptyCoroutineContext
            override val headers: Headers = object : Headers by Headers.Empty {
                override fun entries(): Set<Map.Entry<String, List<String>>> {
                    headerCopies.incrementAndGet()
                    return mapOf("Link" to listOf(nextLink)).entries
                }
            }
        }

    private fun unusedBodyProvider() = object : BodyProvider<String> {
        override suspend fun body(response: io.ktor.client.statement.HttpResponse): String =
            error("Body access is outside this contract")

        override suspend fun <V> typedBody(
            response: io.ktor.client.statement.HttpResponse,
            type: TypeInfo,
        ): V = error("Body access is outside this contract")
    }

    private suspend fun withServer(
        handler: (HttpExchange) -> Unit,
        block: suspend (String) -> Unit,
    ) {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/2.0/user", handler)
        server.start()
        try {
            block("http://127.0.0.1:${server.address.port}/2.0")
        } finally {
            server.stop(0)
        }
    }

    private fun HttpExchange.respond(status: Int, body: String) {
        val bytes = body.toByteArray(UTF_8)
        responseHeaders.set("Content-Type", "application/json")
        sendResponseHeaders(status, bytes.size.toLong())
        responseBody.use { it.write(bytes) }
    }
}

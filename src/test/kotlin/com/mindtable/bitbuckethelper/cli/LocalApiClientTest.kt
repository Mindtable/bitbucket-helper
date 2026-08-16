package com.mindtable.bitbuckethelper.cli

import com.mindtable.bitbuckethelper.generated.api.v1.model.AllConfiguredRepositoriesTarget
import com.mindtable.bitbuckethelper.generated.api.v1.model.ApiVersion
import com.mindtable.bitbuckethelper.generated.api.v1.model.HealthResponse
import com.mindtable.bitbuckethelper.generated.api.v1.model.StartRefreshRunRequest
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.cio.CIO
import io.ktor.server.cio.unixConnector
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.request.httpMethod
import io.ktor.server.request.receiveText
import io.ktor.server.request.uri
import io.ktor.server.response.respondText
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.routing
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.io.path.deleteIfExists
import kotlin.io.path.readBytes
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.SerializationException
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class LocalApiClientTest {
    @Test
    fun `all HTTP methods use Unix socket and preserve the successful response document`() = runBlocking {
        unixHttpServer { call -> call.respondJson(healthFixture) }.use { server ->
            UnixSocketLocalApiClient(server.socketPath).use { client ->
                val request = StartRefreshRunRequest(ApiVersion._1, AllConfiguredRepositoriesTarget())

                val get = client.get("/health", HealthResponse.serializer())
                val post = client.post("/refresh-runs", request, StartRefreshRunRequest.serializer(), HealthResponse.serializer())
                val put = client.put("/configuration/workspace", request, StartRefreshRunRequest.serializer(), HealthResponse.serializer())
                val delete = client.delete("/configuration/workspace/repositories/repository-1", HealthResponse.serializer())

                listOf(get, post, put, delete).forEach { response ->
                    assertEquals(HttpStatusCode.OK, response.status)
                    assertEquals("req_health_fixture", response.value?.requestId)
                    assertNull(response.error)
                    assertArrayEquals(healthFixture, response.body)
                    assertEquals(healthFixture.decodeToString(), response.document)
                }
            }

            assertEquals(
                listOf(
                    CapturedRequest("GET", "/health", null),
                    CapturedRequest(
                        "POST",
                        "/refresh-runs",
                        "{\"apiVersion\":\"1\",\"target\":{\"type\":\"allConfiguredRepositories\"}}",
                    ),
                    CapturedRequest(
                        "PUT",
                        "/configuration/workspace",
                        "{\"apiVersion\":\"1\",\"target\":{\"type\":\"allConfiguredRepositories\"}}",
                    ),
                    CapturedRequest("DELETE", "/configuration/workspace/repositories/repository-1", null),
                ),
                server.requests,
            )
        }
    }

    @Test
    fun `non-success response is decoded as a strict request error without losing its document`() = runBlocking {
        unixHttpServer { call -> call.respondJson(errorFixture, HttpStatusCode.BadRequest) }.use { server ->
            UnixSocketLocalApiClient(server.socketPath).use { client ->
                val response = client.get("/health", HealthResponse.serializer())

                assertEquals(HttpStatusCode.BadRequest, response.status)
                assertNull(response.value)
                assertEquals("INVALID_REQUEST", response.error?.error?.code?.toString())
                assertArrayEquals(errorFixture, response.body)
                assertEquals(errorFixture.decodeToString(), response.document)
            }
        }
    }

    @Test
    fun `malformed successful response fails strict generated DTO decoding`() = runBlocking {
        unixHttpServer { call -> call.respondJson("{\"apiVersion\":\"1\"}".encodeToByteArray()) }.use { server ->
            UnixSocketLocalApiClient(server.socketPath).use { client ->
                assertSuspendFails<SerializationException> {
                    client.get("/health", HealthResponse.serializer())
                }
            }
        }
    }

    @Test
    fun `response body is bounded before decoding`() = runBlocking {
        unixHttpServer { call -> call.respondJson("x".repeat(33).encodeToByteArray()) }.use { server ->
            UnixSocketLocalApiClient(server.socketPath, LocalApiClientConfig(maxResponseBytes = 32)).use { client ->
                assertSuspendFails<LocalApiResponseTooLargeException> {
                    client.get("/health", HealthResponse.serializer())
                }
            }
        }
    }

    @Test
    fun `request timeout defaults to ten seconds and is injectable`() = runBlocking {
        assertEquals(10.seconds, LocalApiClientConfig().requestTimeout)
        unixHttpServer { _ -> delay(100) }.use { server ->
            UnixSocketLocalApiClient(server.socketPath, LocalApiClientConfig(requestTimeout = 10.milliseconds)).use { client ->
                assertSuspendFails<HttpRequestTimeoutException> {
                    client.get("/health", HealthResponse.serializer())
                }
            }
        }
    }

    @Test
    fun `connection to stopped Unix listener fails without a TCP fallback`() = runBlocking {
        val server = unixHttpServer { call -> call.respondJson(healthFixture) }
        val socketPath = server.socketPath
        server.close()

        UnixSocketLocalApiClient(socketPath).use { client ->
            assertSuspendFails<IOException> {
                client.get("/health", HealthResponse.serializer())
            }
        }
    }

    @Test
    fun `missing Unix socket fails without a TCP fallback`() = runBlocking {
        val directory = Files.createTempDirectory("local-api-client-missing")
        val missingSocket = directory.resolve("missing.sock")
        try {
            UnixSocketLocalApiClient(missingSocket).use { client ->
                assertSuspendFails<IOException> {
                    client.get("/health", HealthResponse.serializer())
                }
            }
        } finally {
            directory.deleteIfExists()
        }
    }

    @Test
    fun `request cancellation propagates to the caller`() = runBlocking {
        unixHttpServer { _ -> delay(Long.MAX_VALUE) }.use { server ->
            UnixSocketLocalApiClient(server.socketPath).use { client ->
                val request = async { client.get("/health", HealthResponse.serializer()) }
                delay(20)
                request.cancelAndJoin()

                assertEquals(true, request.isCancelled)
            }
        }
    }

    @Test
    fun `close is idempotent`() {
        val directory = Files.createTempDirectory("local-api-client-close")
        try {
            val client = UnixSocketLocalApiClient(directory.resolve("service.sock"))

            client.close()
            client.close()
        } finally {
            directory.deleteIfExists()
        }
    }

    private fun unixHttpServer(
        handler: suspend (ApplicationCall) -> Unit,
    ): UnixHttpServer {
        val directory = Files.createTempDirectory("local-api-client")
        val socketPath = directory.resolve("service.sock")
        val requests = CopyOnWriteArrayList<CapturedRequest>()
        val server = embeddedServer(CIO, configure = { unixConnector(socketPath.toString()) }) {
            routing {
                get("/health") { call.recordAndHandle(requests, handler) }
                post("/refresh-runs") { call.recordAndHandle(requests, handler) }
                put("/configuration/workspace") { call.recordAndHandle(requests, handler) }
                delete("/configuration/workspace/repositories/{repositoryId}") { call.recordAndHandle(requests, handler) }
            }
        }.start(wait = false)
        return UnixHttpServer(directory, socketPath, requests, server)
    }

    private suspend fun ApplicationCall.recordAndHandle(
        requests: MutableList<CapturedRequest>,
        handler: suspend (ApplicationCall) -> Unit,
    ) {
        requests += CapturedRequest(
            this.request.httpMethod.value,
            this.request.uri,
            receiveText().takeIf { it.isNotEmpty() },
        )
        handler(this)
    }

    private suspend inline fun <reified T : Throwable> assertSuspendFails(noinline block: suspend () -> Unit) {
        val failure = runCatching { block() }.exceptionOrNull()
        assertNotNull(failure, "Expected ${T::class.simpleName}")
        assertInstanceOf(T::class.java, failure)
    }

    private suspend fun ApplicationCall.respondJson(
        body: ByteArray,
        status: HttpStatusCode = HttpStatusCode.OK,
    ) {
        respondText(body.decodeToString(), ContentType.Application.Json, status)
    }

    private data class CapturedRequest(val method: String, val uri: String, val body: String?)

    private class UnixHttpServer(
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
    }

    private companion object {
        val healthFixture: ByteArray = resource("cli/v1/health-degraded.json")
        val errorFixture: ByteArray = resource("cli/v1/request-error.json")

        fun resource(name: String): ByteArray =
            requireNotNull(LocalApiClientTest::class.java.classLoader.getResource(name)) { "Missing fixture: $name" }
                .readBytes()
    }
}

package com.mindtable.bitbuckethelper.adapter.outbound.bitbucket

import com.mindtable.bitbuckethelper.application.model.GatewayFailure
import com.mindtable.bitbuckethelper.application.model.GatewayFailureCategory
import com.mindtable.bitbuckethelper.application.model.GatewayRepositoryObservation
import com.mindtable.bitbuckethelper.application.model.GatewayResult
import com.mindtable.bitbuckethelper.application.model.GatewayUserObservation
import com.mindtable.bitbuckethelper.application.model.GatewayWorkspaceObservation
import com.mindtable.bitbuckethelper.domain.shared.RepositoryId
import com.mindtable.bitbuckethelper.domain.shared.WorkspaceId
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.net.URI
import java.nio.charset.StandardCharsets.UTF_8
import java.time.Duration
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource

class GeneratedBitbucketGatewayIdentityTest {
    @Test
    fun `maps all identities from complete fixtures through the normalized configured path`() = runBlocking {
        val receivedPaths = mutableListOf<String>()
        withServer(handler = { exchange ->
            receivedPaths += exchange.requestURI.path
            when (exchange.requestURI.path) {
                "/configured/2.0/user" -> exchange.respond(200, fixture("current-user.json"))
                "/configured/2.0/workspaces/acme-engineering" -> exchange.respond(200, fixture("workspace.json"))
                "/configured/2.0/repositories/acme-engineering/release-tools" ->
                    exchange.respond(200, fixture("repository.json"))
                else -> exchange.respond(404, "{}")
            }
        }) { serverBaseUrl ->
            gateway().use {
                val apiBaseUrl = URI("${serverBaseUrl}/configured/2.0/")

                assertEquals(
                    GatewayResult.Success(
                        GatewayUserObservation(
                            stableId = "{11111111-1111-1111-1111-111111111111}",
                            displayName = "Ada Lovelace",
                            nickname = null,
                        ),
                    ),
                    it.currentUser(apiBaseUrl),
                )
                assertEquals(
                    GatewayResult.Success(
                        GatewayWorkspaceObservation(
                            id = WorkspaceId("ws_22222222-2222-2222-2222-222222222222"),
                            slug = "acme-engineering",
                            displayName = "Acme Engineering",
                            webUrl = URI("https://bitbucket.org/acme-engineering"),
                        ),
                    ),
                    it.resolveWorkspace(apiBaseUrl, "acme-engineering"),
                )
                assertEquals(
                    GatewayResult.Success(
                        GatewayRepositoryObservation(
                            id = RepositoryId("repo_33333333-3333-3333-3333-333333333333"),
                            workspaceId = WorkspaceId("ws_22222222-2222-2222-2222-222222222222"),
                            slug = "release-tools",
                            displayName = "Release Tools",
                            webUrl = URI("https://bitbucket.org/acme-engineering/release-tools"),
                        ),
                    ),
                    it.resolveRepository(apiBaseUrl, "acme-engineering", "release-tools"),
                )
            }
        }

        assertEquals(
            listOf(
                "/configured/2.0/user",
                "/configured/2.0/workspaces/acme-engineering",
                "/configured/2.0/repositories/acme-engineering/release-tools",
            ),
            receivedPaths,
        )
    }

    @ParameterizedTest(name = "{0} valid JSON maps its required field or UUID defect safely")
    @MethodSource("invalidIdentityPayloads")
    fun `missing required fields and invalid UUIDs are mapper failures`(
        @Suppress("UNUSED_PARAMETER") label: String,
        endpoint: IdentityEndpoint,
        responseBody: String,
    ) = runBlocking {
        withServer(handler = { exchange -> exchange.respond(200, responseBody) }) { apiBaseUrl ->
            gateway().use { gateway ->
                assertEquals(malformedFailure(), gateway.invoke(endpoint, apiBaseUrl))
            }
        }
    }

    @Test
    fun `non-hierarchical workspace web link is a malformed response`() = runBlocking {
        val responseBody =
            """{"type":"workspace","uuid":"{22222222-2222-2222-2222-222222222222}","name":"Acme Engineering","slug":"acme-engineering","links":{"html":{"href":"mailto:maintainer@example.test"}}}"""
        withServer(handler = { exchange -> exchange.respond(200, responseBody) }) { apiBaseUrl ->
            gateway().use { gateway ->
                assertEquals(malformedFailure(), gateway.resolveWorkspace(apiBaseUrl, "acme-engineering"))
            }
        }
    }

    @ParameterizedTest(name = "{0} rejects an HTTP upstream web link")
    @MethodSource("httpIdentityWebLinks")
    fun `identity web links require https without leaking rejected links`(
        @Suppress("UNUSED_PARAMETER") label: String,
        endpoint: IdentityEndpoint,
        responseBody: String,
        rejectedUrl: String,
    ) = runBlocking {
        withServer(handler = { exchange -> exchange.respond(200, responseBody) }) { apiBaseUrl ->
            gateway().use { gateway ->
                val result = gateway.invoke(endpoint, apiBaseUrl)
                assertEquals(malformedFailure(), result)
                assertFalse(result.toString().contains(rejectedUrl))
            }
        }
    }

    @ParameterizedTest(name = "HTTP {0} maps to the safe typed result")
    @MethodSource("httpOutcomes")
    fun `http outcomes never expose upstream response content`(
        status: Int,
        headers: Map<String, String>,
        expected: GatewayResult<Nothing>,
    ) = runBlocking {
        withServer(handler = { exchange -> exchange.respond(status, "{\"detail\":\"upstream\"}", headers) }) { apiBaseUrl ->
            gateway().use { gateway ->
                assertEquals(expected, gateway.currentUser(apiBaseUrl))
            }
        }
    }

    @Test
    fun `malformed JSON maps to a safe malformed response failure`() = runBlocking {
        withServer(handler = { exchange -> exchange.respond(200, "{\"type\":\"user\"") }) { apiBaseUrl ->
            gateway().use { gateway ->
                assertEquals(malformedFailure(), gateway.currentUser(apiBaseUrl))
            }
        }
    }

    @Test
    fun `request timeout maps to a retryable timeout failure`() = runBlocking {
        val received = CountDownLatch(1)
        val release = CountDownLatch(1)
        try {
            withServer(handler = { exchange ->
                received.countDown()
                release.await(5, TimeUnit.SECONDS)
                runCatching { exchange.respond(200, fixture("current-user.json")) }
            }) { apiBaseUrl ->
                gateway(timeout = Duration.ofMillis(100)).use { gateway ->
                    assertEquals(
                        GatewayResult.Failure(
                            GatewayFailure(GatewayFailureCategory.TIMEOUT, retryable = true, retryAt = null),
                        ),
                        withTimeout(2_000) { gateway.currentUser(apiBaseUrl) },
                    )
                }
                assertTrue(received.await(1, TimeUnit.SECONDS))
            }
        } finally {
            release.countDown()
        }
    }

    @Test
    fun `cancellation propagates after the fake server receives the request`() = runBlocking {
        val received = CountDownLatch(1)
        val release = CountDownLatch(1)
        try {
            withServer(handler = { exchange ->
                received.countDown()
                release.await(5, TimeUnit.SECONDS)
                runCatching { exchange.respond(200, fixture("current-user.json")) }
            }) { apiBaseUrl ->
                gateway(timeout = Duration.ofSeconds(5)).use { gateway ->
                    val request = async(Dispatchers.Default) { gateway.currentUser(apiBaseUrl) }
                    assertTrue(received.await(1, TimeUnit.SECONDS))
                    request.cancel()
                    val cancellation = runCatching { request.await() }.exceptionOrNull()
                    assertTrue(cancellation is CancellationException)
                }
            }
        } finally {
            release.countDown()
        }
    }

    @Test
    fun `credentials are sent only as constructor-provided Basic authentication`() = runBlocking {
        val authorization = AtomicReference<String>()
        withServer(handler = { exchange ->
            authorization.set(exchange.requestHeaders.getFirst("Authorization"))
            exchange.respond(200, fixture("current-user.json"))
        }) { apiBaseUrl ->
            gateway().use { gateway ->
                gateway.currentUser(apiBaseUrl)
            }
        }

        assertEquals("Basic aWRlbnRpdHktdXNlcjppZGVudGl0eS1wYXNzd29yZA==", authorization.get())
    }

    @Test
    fun `configured API URL with user info is rejected before a request`() = runBlocking {
        val requestReceived = AtomicBoolean()
        withServer(handler = { exchange ->
            requestReceived.set(true)
            exchange.respond(200, fixture("current-user.json"))
        }) { serverBaseUrl ->
            val apiBaseUrl = URI("http://url-user:url-password@${serverBaseUrl.authority}/configured/2.0")

            gateway().use { gateway ->
                assertEquals(malformedFailure(), gateway.currentUser(apiBaseUrl))
            }
        }

        assertTrue(!requestReceived.get())
    }

    private fun gateway(timeout: Duration = Duration.ofSeconds(2)): GeneratedBitbucketGateway =
        GeneratedBitbucketGateway.create(
            requestTimeout = timeout,
            username = "identity-user",
            appPassword = "identity-password",
        )

    private suspend fun GeneratedBitbucketGateway.invoke(
        endpoint: IdentityEndpoint,
        apiBaseUrl: URI,
    ): GatewayResult<*> = when (endpoint) {
        IdentityEndpoint.CURRENT_USER -> currentUser(apiBaseUrl)
        IdentityEndpoint.WORKSPACE -> resolveWorkspace(apiBaseUrl, "acme-engineering")
        IdentityEndpoint.REPOSITORY -> resolveRepository(apiBaseUrl, "acme-engineering", "release-tools")
    }

    private fun fixture(name: String): String =
        requireNotNull(javaClass.getResource("/bitbucket/v1/$name")).readText()

    private fun malformedFailure(): GatewayResult.Failure =
        GatewayResult.Failure(
            GatewayFailure(GatewayFailureCategory.MALFORMED_RESPONSE, retryable = false, retryAt = null),
        )

    private suspend fun withServer(
        handler: (HttpExchange) -> Unit,
        block: suspend (URI) -> Unit,
    ) {
        val executor = Executors.newCachedThreadPool()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.executor = executor
        server.createContext("/") { exchange ->
            try {
                handler(exchange)
            } finally {
                exchange.close()
            }
        }
        server.start()
        try {
            block(URI("http://127.0.0.1:${server.address.port}"))
        } finally {
            server.stop(0)
            executor.shutdownNow()
        }
    }

    private fun HttpExchange.respond(
        status: Int,
        body: String,
        headers: Map<String, String> = emptyMap(),
    ) {
        headers.forEach { (name, value) -> responseHeaders.add(name, value) }
        responseHeaders.add("Content-Type", "application/json")
        val bytes = body.toByteArray(UTF_8)
        sendResponseHeaders(status, bytes.size.toLong())
        responseBody.use { it.write(bytes) }
    }

    enum class IdentityEndpoint {
        CURRENT_USER,
        WORKSPACE,
        REPOSITORY,
    }

    private companion object {
        @JvmStatic
        fun invalidIdentityPayloads(): List<Arguments> = listOf(
            Arguments.of("current user missing display name", IdentityEndpoint.CURRENT_USER, """{"type":"user","uuid":"{11111111-1111-1111-1111-111111111111}"}"""),
            Arguments.of("current user invalid UUID", IdentityEndpoint.CURRENT_USER, """{"type":"user","uuid":"not-a-uuid","display_name":"Ada Lovelace"}"""),
            Arguments.of("workspace missing slug", IdentityEndpoint.WORKSPACE, """{"type":"workspace","uuid":"{22222222-2222-2222-2222-222222222222}","name":"Acme Engineering","links":{"html":{"href":"https://bitbucket.org/acme-engineering"}}}"""),
            Arguments.of("workspace invalid UUID", IdentityEndpoint.WORKSPACE, """{"type":"workspace","uuid":"not-a-uuid","name":"Acme Engineering","slug":"acme-engineering","links":{"html":{"href":"https://bitbucket.org/acme-engineering"}}}"""),
            Arguments.of("repository missing owner", IdentityEndpoint.REPOSITORY, """{"type":"repository","uuid":"{33333333-3333-3333-3333-333333333333}","full_name":"acme-engineering/release-tools","name":"Release Tools","links":{"html":{"href":"https://bitbucket.org/acme-engineering/release-tools"}}}"""),
            Arguments.of("repository invalid UUID", IdentityEndpoint.REPOSITORY, """{"type":"repository","uuid":"not-a-uuid","full_name":"acme-engineering/release-tools","name":"Release Tools","owner":{"type":"workspace","uuid":"{22222222-2222-2222-2222-222222222222}"},"links":{"html":{"href":"https://bitbucket.org/acme-engineering/release-tools"}}}"""),
        )

        @JvmStatic
        fun httpIdentityWebLinks(): List<Arguments> {
            val workspaceUrl = "http://bitbucket.org/private-workspace-sentinel"
            val repositoryUrl = "http://bitbucket.org/private-repository-sentinel"
            return listOf(
                Arguments.of(
                    "workspace",
                    IdentityEndpoint.WORKSPACE,
                    """{"type":"workspace","uuid":"{22222222-2222-2222-2222-222222222222}","name":"Acme Engineering","slug":"acme-engineering","links":{"html":{"href":"$workspaceUrl"}}}""",
                    workspaceUrl,
                ),
                Arguments.of(
                    "repository",
                    IdentityEndpoint.REPOSITORY,
                    """{"type":"repository","uuid":"{33333333-3333-3333-3333-333333333333}","full_name":"acme-engineering/release-tools","name":"Release Tools","owner":{"type":"workspace","uuid":"{22222222-2222-2222-2222-222222222222}"},"links":{"html":{"href":"$repositoryUrl"}}}""",
                    repositoryUrl,
                ),
            )
        }

        @JvmStatic
        fun httpOutcomes(): List<Arguments> = listOf(
            Arguments.of(401, emptyMap<String, String>(), GatewayResult.Failure(GatewayFailure(GatewayFailureCategory.AUTHENTICATION, retryable = false, retryAt = null))),
            Arguments.of(403, emptyMap<String, String>(), GatewayResult.Failure(GatewayFailure(GatewayFailureCategory.AUTHORIZATION, retryable = false, retryAt = null))),
            Arguments.of(404, emptyMap<String, String>(), GatewayResult.NotFound),
            Arguments.of(429, mapOf("Retry-After" to "Sun, 06 Nov 1994 08:49:37 GMT"), GatewayResult.Failure(GatewayFailure(GatewayFailureCategory.RATE_LIMITED, retryable = true, retryAt = Instant.parse("1994-11-06T08:49:37Z")))),
            Arguments.of(429, mapOf("retry-after" to "not-retryable-metadata"), GatewayResult.Failure(GatewayFailure(GatewayFailureCategory.RATE_LIMITED, retryable = true, retryAt = null))),
            Arguments.of(429, mapOf("X-RateLimit-Reset" to "99999999999999999999"), GatewayResult.Failure(GatewayFailure(GatewayFailureCategory.RATE_LIMITED, retryable = true, retryAt = null))),
            Arguments.of(503, emptyMap<String, String>(), GatewayResult.Failure(GatewayFailure(GatewayFailureCategory.UPSTREAM, retryable = true, retryAt = null))),
            Arguments.of(
                418,
                emptyMap<String, String>(),
                GatewayResult.Failure(
                    GatewayFailure(GatewayFailureCategory.MALFORMED_RESPONSE, retryable = false, retryAt = null),
                ),
            ),
        )
    }
}

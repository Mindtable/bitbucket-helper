package com.mindtable.bitbuckethelper.adapter.outbound.bitbucket

import com.mindtable.bitbuckethelper.application.model.GatewayFailure
import com.mindtable.bitbuckethelper.application.model.GatewayFailureCategory
import com.mindtable.bitbuckethelper.application.model.GatewayPullRequestSummary
import com.mindtable.bitbuckethelper.application.model.GatewayRepositoryAddress
import com.mindtable.bitbuckethelper.application.model.GatewayResult
import com.mindtable.bitbuckethelper.domain.shared.RepositoryId
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets.UTF_8
import java.time.Duration
import java.time.Instant
import java.util.concurrent.Executors
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class GeneratedBitbucketGatewayPaginationTest {
    @Test
    fun `lists every authored open pull request across opaque same-origin pages`() = runBlocking {
        val requests = mutableListOf<URI>()
        withServer(handler = { exchange ->
            requests += exchange.requestURI
            when (exchange.requestURI.queryParameters()["page"]) {
                null -> exchange.respond(200, fixture("pull-requests-page-1.json"))
                "2" -> exchange.respond(200, fixture("pull-requests-page-2.json"))
                else -> exchange.respond(404, "{}")
            }
        }) { apiBaseUrl ->
            gateway().use { gateway ->
                assertEquals(
                    GatewayResult.Success(
                        listOf(
                            summary(41, "Draft release notes", draft = true, "abc123", "2026-08-01T10:15:30Z"),
                            summary(42, "Stabilize deployment", draft = false, "def456", "2026-08-02T11:20:40Z"),
                        ),
                    ),
                    gateway.listAuthoredOpenPullRequests(repository(apiBaseUrl), currentUserStableId),
                )
            }
        }

        assertEquals(2, requests.size)
        assertEquals("/configured/2.0/repositories/acme-engineering/release-tools/pullrequests", requests[0].path)
        assertEquals(
            mapOf("state" to "OPEN", "q" to "author.uuid=\"$currentUserStableId\""),
            requests[0].queryParameters(),
        )
        assertEquals("/configured/2.0/repositories/acme-engineering/release-tools/pullrequests", requests[1].path)
        assertEquals(mapOf("page" to "2"), requests[1].queryParameters())
    }

    @Test
    fun `rejects a cross-origin opaque next link without making a second request`() = runBlocking {
        val requests = mutableListOf<URI>()
        withServer(handler = { exchange ->
            requests += exchange.requestURI
            exchange.respond(200, pageWithNext("https://attacker.example/2.0/repositories/acme-engineering/release-tools/pullrequests?page=2"))
        }) { apiBaseUrl ->
            gateway().use { gateway ->
                assertEquals(unsafePaginationFailure(), gateway.listAuthoredOpenPullRequests(repository(apiBaseUrl), currentUserStableId))
            }
        }

        assertEquals(1, requests.size)
    }

    @Test
    fun `detects a repeated opaque next link before requesting it again`() = runBlocking {
        val requests = mutableListOf<URI>()
        withServer(handler = { exchange ->
            requests += exchange.requestURI
            exchange.respond(200, pageWithNext(exchange.requestURI.toString()))
        }) { apiBaseUrl ->
            gateway().use { gateway ->
                assertEquals(unsafePaginationFailure(), gateway.listAuthoredOpenPullRequests(repository(apiBaseUrl), currentUserStableId))
            }
        }

        assertEquals(1, requests.size)
    }

    @Test
    fun `stops before a one-hundred-first page instead of returning a partial collection`() = runBlocking {
        val requestCount = intArrayOf(0)
        withServer(handler = { exchange ->
            requestCount[0] += 1
            val nextPage = requestCount[0] + 1
            exchange.respond(
                200,
                pageWithNext("/configured/2.0/repositories/acme-engineering/release-tools/pullrequests?page=$nextPage"),
            )
        }) { apiBaseUrl ->
            gateway().use { gateway ->
                assertEquals(unsafePaginationFailure(), gateway.listAuthoredOpenPullRequests(repository(apiBaseUrl), currentUserStableId))
            }
        }

        assertEquals(100, requestCount[0])
    }

    @Test
    fun `rejects a malformed opaque next link without making a second request`() = runBlocking {
        val requests = mutableListOf<URI>()
        withServer(handler = { exchange ->
            requests += exchange.requestURI
            exchange.respond(200, pageWithNext("http://[invalid"))
        }) { apiBaseUrl ->
            gateway().use { gateway ->
                assertEquals(unsafePaginationFailure(), gateway.listAuthoredOpenPullRequests(repository(apiBaseUrl), currentUserStableId))
            }
        }

        assertEquals(1, requests.size)
    }

    @Test
    fun `rejects encoded dot-segment next paths before a second request`() = runBlocking {
        for (encodedDotSegment in listOf("%2e%2e", ".%2e", "%2e.")) {
            val requests = mutableListOf<URI>()
            withServer(handler = { exchange ->
                requests += exchange.requestURI
                exchange.respond(200, pageWithNext("/configured/2.0/$encodedDotSegment/admin"))
            }) { apiBaseUrl ->
                gateway().use { gateway ->
                    assertEquals(
                        unsafePaginationFailure(),
                        gateway.listAuthoredOpenPullRequests(repository(apiBaseUrl), currentUserStableId),
                    )
                }
            }

            assertEquals(1, requests.size, "encoded segment: $encodedDotSegment")
        }
    }

    @Test
    fun `does not treat a page without values as an authoritative empty result`() = runBlocking {
        val requests = mutableListOf<URI>()
        withServer(handler = { exchange ->
            requests += exchange.requestURI
            exchange.respond(200, "{\"pagelen\":10}")
        }) { apiBaseUrl ->
            gateway().use { gateway ->
                assertEquals(
                    malformedResponseFailure(),
                    gateway.listAuthoredOpenPullRequests(repository(apiBaseUrl), currentUserStableId),
                )
            }
        }

        assertEquals(1, requests.size)
    }

    @Test
    fun `returns the typed upstream failure when a later page cannot be fetched`() = runBlocking {
        val requests = mutableListOf<URI>()
        withServer(handler = { exchange ->
            requests += exchange.requestURI
            when (exchange.requestURI.queryParameters()["page"]) {
                null -> exchange.respond(200, fixture("pull-requests-page-1.json"))
                "2" -> exchange.respond(503, "{\"detail\":\"upstream failure\"}")
                else -> exchange.respond(404, "{}")
            }
        }) { apiBaseUrl ->
            gateway().use { gateway ->
                assertEquals(
                    GatewayResult.Failure(GatewayFailure(GatewayFailureCategory.UPSTREAM, retryable = true, retryAt = null)),
                    gateway.listAuthoredOpenPullRequests(repository(apiBaseUrl), currentUserStableId),
                )
            }
        }

        assertEquals(2, requests.size)
    }

    private fun gateway(): GeneratedBitbucketGateway = GeneratedBitbucketGateway.create(
        requestTimeout = Duration.ofSeconds(2),
        username = "pagination-user",
        appPassword = "pagination-password",
    )

    private fun repository(apiBaseUrl: URI): GatewayRepositoryAddress = GatewayRepositoryAddress(
        id = RepositoryId("repo_33333333-3333-3333-3333-333333333333"),
        apiBaseUrl = URI("${apiBaseUrl}/configured/2.0"),
        workspaceSlug = "acme-engineering",
        repositorySlug = "release-tools",
    )

    private fun summary(
        number: Long,
        title: String,
        draft: Boolean,
        headCommit: String,
        createdAt: String,
    ): GatewayPullRequestSummary = GatewayPullRequestSummary(
        repositoryId = RepositoryId("repo_33333333-3333-3333-3333-333333333333"),
        upstreamNumber = number,
        title = title,
        authorStableId = currentUserStableId,
        authorDisplayName = "Ada Lovelace",
        draft = draft,
        headCommit = headCommit,
        webUrl = URI("https://bitbucket.org/acme-engineering/release-tools/pull-requests/$number"),
        createdAt = Instant.parse(createdAt),
        updatedAt = Instant.parse(createdAt),
    )

    private fun pageWithNext(next: String): String =
        """{"values":[],"next":${jsonString(next)}}"""

    private fun jsonString(value: String): String =
        "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

    private fun fixture(name: String): String =
        requireNotNull(javaClass.getResource("/bitbucket/v1/$name")).readText()

    private fun unsafePaginationFailure(): GatewayResult.Failure =
        GatewayResult.Failure(GatewayFailure(GatewayFailureCategory.UNSAFE_PAGINATION, retryable = false, retryAt = null))

    private fun malformedResponseFailure(): GatewayResult.Failure =
        GatewayResult.Failure(GatewayFailure(GatewayFailureCategory.MALFORMED_RESPONSE, retryable = false, retryAt = null))

    private fun URI.queryParameters(): Map<String, String> =
        rawQuery.orEmpty().split('&').filter(String::isNotEmpty).associate { item ->
            val (key, value) = item.split('=', limit = 2).let { it[0] to it.getOrElse(1) { "" } }
            URLDecoder.decode(key, UTF_8) to URLDecoder.decode(value, UTF_8)
        }

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

    private fun HttpExchange.respond(status: Int, body: String) {
        responseHeaders.add("Content-Type", "application/json")
        val bytes = body.toByteArray(UTF_8)
        sendResponseHeaders(status, bytes.size.toLong())
        responseBody.use { it.write(bytes) }
    }

    private companion object {
        const val currentUserStableId = "{11111111-1111-1111-1111-111111111111}"
    }
}

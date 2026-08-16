package com.mindtable.bitbuckethelper.adapter.outbound.bitbucket

import com.mindtable.bitbuckethelper.application.model.GatewayActivityKind
import com.mindtable.bitbuckethelper.application.model.GatewayActivityObservation
import com.mindtable.bitbuckethelper.application.model.GatewayBuildObservation
import com.mindtable.bitbuckethelper.application.model.GatewayBuildStatus
import com.mindtable.bitbuckethelper.application.model.GatewayFailure
import com.mindtable.bitbuckethelper.application.model.GatewayFailureCategory
import com.mindtable.bitbuckethelper.application.model.GatewayLiveActivityContent
import com.mindtable.bitbuckethelper.application.model.GatewayPullRequestDetail
import com.mindtable.bitbuckethelper.application.model.GatewayRepositoryAddress
import com.mindtable.bitbuckethelper.application.model.GatewayResult
import com.mindtable.bitbuckethelper.application.model.GatewayTaskObservation
import com.mindtable.bitbuckethelper.application.model.GatewayUserObservation
import com.mindtable.bitbuckethelper.domain.shared.ActivityVersion
import com.mindtable.bitbuckethelper.domain.shared.RepositoryId
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.net.URI
import java.nio.charset.StandardCharsets.UTF_8
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource

class GeneratedBitbucketGatewayTest {
    @Test
    fun `maps pull request participants and readiness inputs into normalized detail`() = runBlocking {
        withServer(handler = { exchange -> exchange.respond(200, fixture("pull-request-detail.json")) }) { apiBaseUrl ->
            gateway().use { gateway ->
                assertEquals(
                    GatewayResult.Success(
                        GatewayPullRequestDetail(
                            repositoryId = repositoryId,
                            upstreamNumber = 42,
                            title = "Translate complete state",
                            authorStableId = authorId,
                            authorDisplayName = "Ada Lovelace",
                            draft = false,
                            headCommit = "abc123def456",
                            webUrl = URI("https://bitbucket.org/acme-engineering/release-tools/pull-requests/42"),
                            createdAt = Instant.parse("2026-08-01T10:15:30Z"),
                            updatedAt = Instant.parse("2026-08-15T12:30:45Z"),
                            approvalCount = 1,
                            approvedByStableIds = setOf(graceId),
                            hasChangesRequested = true,
                            unresolvedCommentCount = 2,
                            destinationBranchIsCurrent = true,
                            hasMergeConflicts = false,
                        ),
                    ),
                    gateway.getPullRequest(repository(apiBaseUrl), 42),
                )
            }
        }
    }

    @Test
    fun `maps every page of reviewers builds tasks and activity without carrying content`() = runBlocking {
        val requests = mutableListOf<String>()
        withServer(handler = { exchange ->
            requests += exchange.requestURI.path
            val secondPage = exchange.requestURI.rawQuery == "page=2"
            val body = when {
                exchange.requestURI.path.endsWith("effective-default-reviewers") && !secondPage ->
                    fixture("default-reviewers.json")
                exchange.requestURI.path.endsWith("effective-default-reviewers") -> reviewerPageTwo
                exchange.requestURI.path.endsWith("/statuses") && !secondPage -> fixture("statuses.json")
                exchange.requestURI.path.endsWith("/statuses") -> statusPageTwo
                exchange.requestURI.path.endsWith("/tasks") && !secondPage -> fixture("tasks.json")
                exchange.requestURI.path.endsWith("/tasks") -> taskPageTwo
                exchange.requestURI.path.endsWith("/activity") && !secondPage -> fixture("activity.json")
                exchange.requestURI.path.endsWith("/activity") -> activityPageTwo
                else -> null
            }
            if (body == null) exchange.respond(404, "{}") else exchange.respond(200, body)
        }) { apiBaseUrl ->
            gateway().use { gateway ->
                val repository = repository(apiBaseUrl)
                assertEquals(
                    GatewayResult.Success(
                        listOf(
                            GatewayUserObservation(graceId, "Grace Hopper", "grace"),
                            GatewayUserObservation(margaretId, "Margaret Hamilton", null),
                        ),
                    ),
                    gateway.getEffectiveDefaultReviewers(repository, 42),
                )
                assertEquals(
                    GatewayResult.Success(
                        listOf(
                            GatewayBuildObservation("unit", GatewayBuildStatus.SUCCESSFUL, Instant.parse("2026-08-15T12:31:00Z")),
                            GatewayBuildObservation("lint", GatewayBuildStatus.FAILED, Instant.parse("2026-08-15T12:32:00Z")),
                            GatewayBuildObservation("deploy", GatewayBuildStatus.STOPPED, Instant.parse("2026-08-15T12:33:00Z")),
                            GatewayBuildObservation("integration", GatewayBuildStatus.IN_PROGRESS, Instant.parse("2026-08-15T12:34:00Z")),
                            GatewayBuildObservation("future", GatewayBuildStatus.UNKNOWN, Instant.parse("2026-08-15T12:35:00Z")),
                        ),
                    ),
                    gateway.listBuilds(repository, 42),
                )
                assertEquals(
                    GatewayResult.Success(
                        listOf(
                            GatewayTaskObservation("701", resolved = false, Instant.parse("2026-08-15T12:34:00Z")),
                            GatewayTaskObservation("702", resolved = true, Instant.parse("2026-08-15T12:35:00Z")),
                        ),
                    ),
                    gateway.listTasks(repository, 42),
                )
                val activity = gateway.listActivity(repository, 42)
                assertEquals(GatewayResult.Success(expectedActivity), activity)
                assertFalse(activity.toString().contains("activity body must not escape"))
                assertFalse(activity.toString().contains("reply body must not escape"))
                assertFalse(activity.toString().contains("deleted body must not escape"))
            }
        }

        assertEquals(8, requests.size)
        assertEquals(2, requests.count { it.endsWith("effective-default-reviewers") })
        assertEquals(2, requests.count { it.endsWith("/statuses") })
        assertEquals(2, requests.count { it.endsWith("/tasks") })
        assertEquals(2, requests.count { it.endsWith("/activity") })
    }

    @Test
    fun `returns raw markdown only from a successful narrowly scoped live content read`() = runBlocking {
        val capturedRequests = mutableListOf<String>()
        withServer(handler = { exchange ->
            capturedRequests += exchange.requestMethod + " " + exchange.requestURI.path
            exchange.respond(200, fixture("comment.json"))
        }) { apiBaseUrl ->
            gateway().use { gateway ->
                assertEquals(
                    GatewayResult.Success(
                        GatewayLiveActivityContent(
                            activityVersion = ActivityVersion("av_comment-501-1786797300000-d0-r0"),
                            markdown = liveMarkdown,
                            fetchedAt = fetchedAt,
                        ),
                    ),
                    gateway.getLiveActivityContent(repository(apiBaseUrl), 42, "501"),
                )
            }
        }

        assertEquals(listOf("GET /configured/2.0/repositories/acme-engineering/release-tools/pullrequests/42/comments/501"), capturedRequests)
        assertFalse(capturedRequests.joinToString().contains(liveMarkdown))
    }

    @Test
    fun `live reply content carries the same reply version emitted by activity`() = runBlocking {
        val reply = """{"type":"pullrequest_comment","id":502,"created_on":"2026-08-15T10:05:00Z","updated_on":"2026-08-15T12:36:00Z","content":{"raw":"reply live body"},"user":{"type":"user","uuid":"$margaretId","display_name":"Margaret Hamilton"},"parent":{"type":"pullrequest_comment","id":501},"deleted":false,"resolution":{"type":"comment_resolution","created_on":"2026-08-15T12:36:30Z"}}"""
        withServer(handler = { exchange -> exchange.respond(200, reply) }) { apiBaseUrl ->
            gateway().use { gateway ->
                val result = gateway.getLiveActivityContent(repository(apiBaseUrl), 42, "502")
                assertTrue(result is GatewayResult.Success)
                assertEquals(expectedActivity[1].activityVersion, (result as GatewayResult.Success).value.activityVersion)
            }
        }
    }

    @ParameterizedTest(name = "HTTP {0} maps to a safe result")
    @MethodSource("httpOutcomes")
    fun `remaining operations map HTTP failures without upstream content`(
        status: Int,
        expected: GatewayResult<Nothing>,
    ) = runBlocking {
        val bodySentinel = "upstream-body-sentinel-91c2"
        withServer(handler = { exchange ->
            exchange.respond(status, "{\"detail\":\"$bodySentinel\"}", mapOf("Retry-After" to "30"))
        }) { apiBaseUrl ->
            gateway().use { gateway ->
                val result = gateway.getPullRequest(repository(apiBaseUrl), 42)
                assertEquals(expected, result)
                assertFalse(result.toString().contains(bodySentinel))
            }
        }
    }

    @ParameterizedTest(name = "{0} rejects cross-origin pagination")
    @MethodSource("collectionOperations")
    fun `every collection rejects cross origin pagination without returning a partial result`(
        @Suppress("UNUSED_PARAMETER") label: String,
        operation: CollectionOperation,
    ) = runBlocking {
        var requestCount = 0
        withServer(handler = { exchange ->
            requestCount += 1
            exchange.respond(200, """{"values":[],"next":"https://attacker.example/2.0/stolen"}""")
        }) { apiBaseUrl ->
            gateway().use { gateway ->
                assertEquals(unsafePaginationFailure(), operation.invoke(gateway, repository(apiBaseUrl)))
            }
        }
        assertEquals(1, requestCount)
    }

    @ParameterizedTest(name = "{0} requires complete JSON")
    @MethodSource("malformedOperations")
    fun `invalid JSON and missing required fields map to malformed response`(
        @Suppress("UNUSED_PARAMETER") label: String,
        operation: RemainingOperation,
        responseBody: String,
    ) = runBlocking {
        withServer(handler = { exchange -> exchange.respond(200, responseBody) }) { apiBaseUrl ->
            gateway().use { gateway ->
                assertEquals(malformedFailure(), operation.invoke(gateway, repository(apiBaseUrl)))
            }
        }
    }

    @Test
    fun `timeout maps safely and cancellation remains cancellation`() = runBlocking {
        val received = CountDownLatch(1)
        val release = CountDownLatch(1)
        try {
            withServer(handler = { exchange ->
                received.countDown()
                release.await(5, TimeUnit.SECONDS)
                runCatching { exchange.respond(200, fixture("activity.json")) }
            }) { apiBaseUrl ->
                gateway(Duration.ofMillis(100)).use { gateway ->
                    assertEquals(timeoutFailure(), gateway.listActivity(repository(apiBaseUrl), 42))
                }
                gateway(Duration.ofSeconds(5)).use { gateway ->
                    val pending = async(Dispatchers.Default) { gateway.listActivity(repository(apiBaseUrl), 42) }
                    assertTrue(received.await(1, TimeUnit.SECONDS))
                    pending.cancel()
                    assertTrue(runCatching { pending.await() }.exceptionOrNull() is CancellationException)
                }
            }
        } finally {
            release.countDown()
        }
    }

    @Test
    fun `live content failure cannot expose markdown through result exception or diagnostic text`() = runBlocking {
        withServer(handler = { exchange ->
            exchange.respond(200, """{"type":"pullrequest_comment","id":501,"content":{"raw":"$liveMarkdown"}}""")
        }) { apiBaseUrl ->
            gateway().use { gateway ->
                val result = gateway.getLiveActivityContent(repository(apiBaseUrl), 42, "501")
                assertEquals(malformedFailure(), result)
                val observable = listOf(result.toString(), result::class.qualifiedName.orEmpty()).joinToString("\n")
                assertFalse(observable.contains(liveMarkdown))
            }
        }
    }

    private fun gateway(timeout: Duration = Duration.ofSeconds(2)): GeneratedBitbucketGateway =
        GeneratedBitbucketGateway.create(
            requestTimeout = timeout,
            username = "detail-user",
            appPassword = "detail-password",
            engine = io.ktor.client.engine.cio.CIO.create(),
            clock = Clock.fixed(fetchedAt, ZoneOffset.UTC),
        )

    private fun repository(apiBaseUrl: URI): GatewayRepositoryAddress = GatewayRepositoryAddress(
        id = repositoryId,
        apiBaseUrl = URI("$apiBaseUrl/configured/2.0"),
        workspaceSlug = "acme-engineering",
        repositorySlug = "release-tools",
    )

    private fun fixture(name: String): String = requireNotNull(javaClass.getResource("/bitbucket/v1/$name")).readText()

    private suspend fun withServer(handler: (HttpExchange) -> Unit, block: suspend (URI) -> Unit) {
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

    private fun HttpExchange.respond(status: Int, body: String, headers: Map<String, String> = emptyMap()) {
        headers.forEach { (name, value) -> responseHeaders.add(name, value) }
        responseHeaders.add("Content-Type", "application/json")
        val bytes = body.toByteArray(UTF_8)
        sendResponseHeaders(status, bytes.size.toLong())
        responseBody.use { it.write(bytes) }
    }

    private fun malformedFailure() = GatewayResult.Failure(
        GatewayFailure(GatewayFailureCategory.MALFORMED_RESPONSE, retryable = false, retryAt = null),
    )

    private fun unsafePaginationFailure() = GatewayResult.Failure(
        GatewayFailure(GatewayFailureCategory.UNSAFE_PAGINATION, retryable = false, retryAt = null),
    )

    private fun timeoutFailure() = GatewayResult.Failure(
        GatewayFailure(GatewayFailureCategory.TIMEOUT, retryable = true, retryAt = null),
    )

    fun interface CollectionOperation {
        suspend fun invoke(gateway: GeneratedBitbucketGateway, repository: GatewayRepositoryAddress): GatewayResult<*>
    }

    fun interface RemainingOperation {
        suspend fun invoke(gateway: GeneratedBitbucketGateway, repository: GatewayRepositoryAddress): GatewayResult<*>
    }

    private companion object {
        val repositoryId = RepositoryId("repo_33333333-3333-3333-3333-333333333333")
        const val authorId = "{11111111-1111-1111-1111-111111111111}"
        const val graceId = "{44444444-4444-4444-4444-444444444444}"
        const val margaretId = "{55555555-5555-5555-5555-555555555555}"
        const val liveMarkdown = "**live-markdown-sentinel-14d9**"
        val fetchedAt: Instant = Instant.parse("2026-08-16T08:00:00Z")

        val reviewerPageTwo = """{"values":[{"type":"default_reviewer","reviewer_type":"project","user":{"type":"user","uuid":"$margaretId","display_name":"Margaret Hamilton"}}]}"""
        val statusPageTwo = """{"values":[{"type":"build","key":"integration","state":"INPROGRESS","updated_on":"2026-08-15T12:34:00Z"},{"type":"build","key":"future","state":"PAUSED_BY_VENDOR","updated_on":"2026-08-15T12:35:00Z"}]}"""
        val taskPageTwo = """{"values":[{"id":702,"state":"RESOLVED","created_on":"2026-08-15T09:05:00Z","updated_on":"2026-08-15T12:35:00Z","content":{"raw":"resolved task body"},"creator":{"type":"user","uuid":"$margaretId","display_name":"Margaret Hamilton"}}]}"""
        val activityPageTwo = """{"values":[{"comment":{"type":"pullrequest_comment","id":503,"created_on":"2026-08-15T10:10:00Z","updated_on":"2026-08-15T12:37:00Z","content":{"raw":"deleted body must not escape"},"user":{"type":"user","uuid":"$graceId","display_name":"Grace Hopper"},"deleted":true,"links":{"html":{"href":"https://bitbucket.org/acme-engineering/release-tools/pull-requests/42#comment-503"}}}},{"changes_request":{"date":"2026-08-15T12:38:00Z","user":{"type":"user","uuid":"$margaretId","display_name":"Margaret Hamilton"},"links":{"html":{"href":"https://bitbucket.org/acme-engineering/release-tools/pull-requests/42#changes-request"}}}}]}"""

        val expectedActivity = listOf(
            GatewayActivityObservation(GatewayActivityKind.COMMENT, "501", graceId, "Grace Hopper", Instant.parse("2026-08-15T12:35:00Z"), ActivityVersion("av_comment-501-1786797300000-d0-r0"), resolved = false, deleted = false, URI("https://bitbucket.org/acme-engineering/release-tools/pull-requests/42#comment-501")),
            GatewayActivityObservation(GatewayActivityKind.REPLY, "502", margaretId, "Margaret Hamilton", Instant.parse("2026-08-15T12:36:00Z"), ActivityVersion("av_reply-502-1786797360000-d0-r1"), resolved = true, deleted = false, URI("https://bitbucket.org/acme-engineering/release-tools/pull-requests/42#comment-502")),
            GatewayActivityObservation(GatewayActivityKind.COMMENT, "503", graceId, "Grace Hopper", Instant.parse("2026-08-15T12:37:00Z"), ActivityVersion("av_comment-503-1786797420000-d1-r0"), resolved = false, deleted = true, URI("https://bitbucket.org/acme-engineering/release-tools/pull-requests/42#comment-503")),
            GatewayActivityObservation(GatewayActivityKind.CHANGES_REQUESTED, "changes-request-55555555-5555-5555-5555-555555555555-1786797480000", margaretId, "Margaret Hamilton", Instant.parse("2026-08-15T12:38:00Z"), ActivityVersion("av_changes-request-55555555-5555-5555-5555-555555555555-1786797480000"), resolved = false, deleted = false, URI("https://bitbucket.org/acme-engineering/release-tools/pull-requests/42#changes-request")),
        )

        @JvmStatic
        fun httpOutcomes(): List<Arguments> = listOf(
            Arguments.of(401, GatewayResult.Failure(GatewayFailure(GatewayFailureCategory.AUTHENTICATION, false, null))),
            Arguments.of(403, GatewayResult.Failure(GatewayFailure(GatewayFailureCategory.AUTHORIZATION, false, null))),
            Arguments.of(404, GatewayResult.NotFound),
            Arguments.of(429, GatewayResult.Failure(GatewayFailure(GatewayFailureCategory.RATE_LIMITED, true, fetchedAt.plusSeconds(30)))),
            Arguments.of(503, GatewayResult.Failure(GatewayFailure(GatewayFailureCategory.UPSTREAM, true, null))),
        )

        @JvmStatic
        fun collectionOperations(): List<Arguments> = listOf(
            Arguments.of("reviewers", CollectionOperation { gateway, repository -> gateway.getEffectiveDefaultReviewers(repository, 42) }),
            Arguments.of("builds", CollectionOperation { gateway, repository -> gateway.listBuilds(repository, 42) }),
            Arguments.of("tasks", CollectionOperation { gateway, repository -> gateway.listTasks(repository, 42) }),
            Arguments.of("activity", CollectionOperation { gateway, repository -> gateway.listActivity(repository, 42) }),
        )

        @JvmStatic
        fun malformedOperations(): List<Arguments> = listOf(
            Arguments.of("detail invalid JSON", RemainingOperation { gateway, repository -> gateway.getPullRequest(repository, 42) }, "{"),
            Arguments.of("detail missing author", RemainingOperation { gateway, repository -> gateway.getPullRequest(repository, 42) }, """{"type":"pullrequest","id":42,"state":"OPEN"}"""),
            Arguments.of("reviewers missing values", RemainingOperation { gateway, repository -> gateway.getEffectiveDefaultReviewers(repository, 42) }, """{"pagelen":10}"""),
            Arguments.of("build missing key", RemainingOperation { gateway, repository -> gateway.listBuilds(repository, 42) }, """{"values":[{"state":"SUCCESSFUL","updated_on":"2026-08-15T12:31:00Z"}]}"""),
            Arguments.of("task missing id", RemainingOperation { gateway, repository -> gateway.listTasks(repository, 42) }, """{"values":[{"state":"UNRESOLVED","updated_on":"2026-08-15T12:34:00Z"}]}"""),
            Arguments.of("activity missing actor", RemainingOperation { gateway, repository -> gateway.listActivity(repository, 42) }, """{"values":[{"comment":{"id":501,"updated_on":"2026-08-15T12:35:00Z","links":{"html":{"href":"https://bitbucket.org/comment"}}}}]}"""),
            Arguments.of("live comment missing updated time", RemainingOperation { gateway, repository -> gateway.getLiveActivityContent(repository, 42, "501") }, """{"type":"pullrequest_comment","id":501,"content":{"raw":"safe only on success"}}"""),
        )
    }
}

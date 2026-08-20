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
import com.mindtable.bitbuckethelper.application.service.ObservationAssembler
import com.mindtable.bitbuckethelper.domain.shared.ActivityVersion
import com.mindtable.bitbuckethelper.domain.shared.RepositoryId
import com.mindtable.bitbuckethelper.observability.BackendEventRecorder
import com.mindtable.bitbuckethelper.observability.BackendLogEvent
import com.mindtable.bitbuckethelper.observability.BackendLogLevel
import com.mindtable.bitbuckethelper.observability.MonotonicTimeSource
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.HttpClientEngineBase
import io.ktor.client.engine.HttpClientEngineConfig
import io.ktor.client.plugins.HttpTimeoutCapability
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import java.net.InetSocketAddress
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets.UTF_8
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource

class GeneratedBitbucketGatewayTest {
    @Test
    fun `malformed generated pull-request page records no Jackson diagnostic`() = runBlocking {
        val events = mutableListOf<BackendLogEvent>()
        val bodySentinel = "PRIVATE-BODY-MALFORMED-GENERATED"
        withServer(handler = { exchange ->
            exchange.respond(200, "{\"values\":[{\"type\":\"pullrequest\",\"id\":\"$bodySentinel\"}]}")
        }) { apiBaseUrl ->
            gateway(
                recorder = BackendEventRecorder(events::add),
                timeSource = sequenceTimeSource(0L, 7_000_000L),
            ).use { gateway ->
                assertEquals(
                    malformedFailure(),
                    gateway.listAuthoredOpenPullRequests(repository(apiBaseUrl), authorId),
                )
            }
        }

        val event = events.single() as BackendLogEvent.BitbucketRequestFailed
        assertEquals("pull_requests", event.operation)
        assertEquals("malformed_response", event.category)
        assertEquals(200, event.status)
        assertNull(event.unexpectedFailure)
        assertFalse(event.toString().contains(bodySentinel))
    }

    @Test
    fun `malformed opaque pull-request page records no Jackson diagnostic`() = runBlocking {
        val events = mutableListOf<BackendLogEvent>()
        val bodySentinel = "PRIVATE-BODY-MALFORMED-OPAQUE"
        withServer(handler = { exchange ->
            if (exchange.requestURI.rawQuery == "page=2") {
                exchange.respond(200, "{\"values\":[{\"type\":\"pullrequest\",\"id\":\"$bodySentinel\"}]}")
            } else {
                exchange.respond(200, pullRequestPageWithNext())
            }
        }) { apiBaseUrl ->
            gateway(
                recorder = BackendEventRecorder(events::add),
                timeSource = sequenceTimeSource(0L, 7_000_000L),
            ).use { gateway ->
                assertEquals(
                    malformedFailure(),
                    gateway.listAuthoredOpenPullRequests(repository(apiBaseUrl), authorId),
                )
            }
        }

        val event = events.single() as BackendLogEvent.BitbucketRequestFailed
        assertEquals("pull_requests", event.operation)
        assertEquals("malformed_response", event.category)
        assertEquals(200, event.status)
        assertNull(event.unexpectedFailure)
        assertFalse(event.toString().contains(bodySentinel))
    }

    @Test
    fun `multi-request success followed by timeout does not retain the earlier status`() = runBlocking {
        val events = mutableListOf<BackendLogEvent>()
        val secondRequest = CountDownLatch(1)
        withServer(handler = { exchange ->
            if (exchange.requestURI.rawQuery == "page=2") {
                secondRequest.countDown()
                Thread.sleep(500)
            } else {
                exchange.respond(200, pullRequestPageWithNext())
            }
        }) { apiBaseUrl ->
            gateway(
                timeout = Duration.ofMillis(150),
                recorder = BackendEventRecorder(events::add),
                timeSource = sequenceTimeSource(0L, 7_000_000L),
            ).use { gateway ->
                assertEquals(
                    timeoutFailure(),
                    gateway.listAuthoredOpenPullRequests(repository(apiBaseUrl), authorId),
                )
            }
        }

        assertTrue(secondRequest.await(1, TimeUnit.SECONDS))
        val event = events.single() as BackendLogEvent.BitbucketRequestFailed
        assertEquals("timeout", event.category)
        assertEquals(true, event.retryable)
        assertNull(event.status)
        assertEquals(7L, event.durationMilliseconds)
    }

    @Test
    fun `multi-request success followed by network failure does not retain the earlier status`() = runBlocking {
        val events = mutableListOf<BackendLogEvent>()
        withStoppableServer(handler = { exchange, server ->
            exchange.respond(200, pullRequestPageWithNext())
            server.stop(0)
        }) { apiBaseUrl ->
            gateway(
                timeout = Duration.ofSeconds(2),
                recorder = BackendEventRecorder(events::add),
                timeSource = sequenceTimeSource(0L, 7_000_000L),
            ).use { gateway ->
                assertEquals(
                    networkFailure(),
                    gateway.listAuthoredOpenPullRequests(repository(apiBaseUrl), authorId),
                )
            }
        }

        val event = events.single() as BackendLogEvent.BitbucketRequestFailed
        assertEquals("network", event.category)
        assertEquals(true, event.retryable)
        assertNull(event.status)
        assertEquals(7L, event.durationMilliseconds)
    }

    @ParameterizedTest(name = "HTTP {0} records {1}")
    @MethodSource("observedHttpFailures")
    fun `records one safe typed event for every mapped HTTP failure`(
        status: Int,
        category: String,
        retryable: Boolean,
    ) = runBlocking {
        val events = mutableListOf<BackendLogEvent>()
        val wireSentinel = "PRIVATE-WIRE-$status"
        withServer(handler = { exchange ->
            exchange.respond(status, "{\"detail\":\"$wireSentinel\"}", mapOf("Retry-After" to "5"))
        }) { apiBaseUrl ->
            gateway(
                recorder = BackendEventRecorder(events::add),
                timeSource = sequenceTimeSource(0L, 7_000_000L),
            ).use { gateway ->
                val result = gateway.getPullRequest(repository(apiBaseUrl), 42)
                if (status == 404) {
                    assertEquals(GatewayResult.NotFound, result)
                } else {
                    val failure = result as GatewayResult.Failure
                    assertEquals(category.uppercase(), failure.failure.category.name)
                    assertEquals(retryable, failure.failure.retryable)
                    if (status == 429) assertEquals(fetchedAt.plusSeconds(5), failure.failure.retryAt)
                }
            }
        }

        val event = events.single() as BackendLogEvent.BitbucketRequestFailed
        assertEquals("pull_request_detail", event.operation)
        assertEquals(category, event.category)
        assertEquals(retryable, event.retryable)
        assertEquals(status, event.status)
        assertEquals(7L, event.durationMilliseconds)
        assertEquals(BackendLogLevel.WARN, event.level)
        assertFalse(event.toString().contains(wireSentinel))
    }

    @Test
    fun `unsafe pagination records one nonretryable warning without the next URL`() = runBlocking {
        val events = mutableListOf<BackendLogEvent>()
        val urlSentinel = "https://attacker.invalid/private-next"
        withServer(handler = { exchange ->
            exchange.respond(200, "{\"values\":[],\"next\":\"$urlSentinel\"}")
        }) { apiBaseUrl ->
            gateway(
                recorder = BackendEventRecorder(events::add),
                timeSource = sequenceTimeSource(0L, 7_000_000L),
            ).use { gateway ->
                assertEquals(
                    unsafePaginationFailure(),
                    gateway.listAuthoredOpenPullRequests(repository(apiBaseUrl), authorId),
                )
            }
        }

        val event = events.single() as BackendLogEvent.BitbucketRequestFailed
        assertEquals("pull_requests", event.operation)
        assertEquals("unsafe_pagination", event.category)
        assertEquals(false, event.retryable)
        assertEquals(200, event.status)
        assertEquals(7L, event.durationMilliseconds)
        assertFalse(event.toString().contains(urlSentinel))
    }

    @Test
    fun `ordinary unknown engine failure is typed malformed with a sanitized diagnostic carrier`() = runBlocking {
        val events = mutableListOf<BackendLogEvent>()
        val failure = IllegalStateException("PRIVATE-URL header-secret body-secret")
        gateway(
            engine = ThrowingEngine(failure),
            recorder = BackendEventRecorder(events::add),
            timeSource = sequenceTimeSource(0L, 7_000_000L),
        ).use { gateway ->
            assertEquals(malformedFailure(), gateway.currentUser(URI("http://private.invalid/2.0")))
        }

        val event = events.single() as BackendLogEvent.BitbucketRequestFailed
        assertEquals("current_user", event.operation)
        assertEquals("malformed_response", event.category)
        assertNull(event.status)
        assertEquals(failure.javaClass, event.unexpectedFailure?.javaClass)
        assertFalse(event.toString().contains("PRIVATE-URL"))
        assertFalse(event.toString().contains("header-secret"))
        assertFalse(event.toString().contains("body-secret"))
    }

    @Test
    fun `unknown engine Error is rethrown identically and recorded as unexpected`() = runBlocking {
        val events = mutableListOf<BackendLogEvent>()
        val failure = AssertionError("PRIVATE-URL header-secret body-secret")
        val observed = runCatching {
            gateway(
                engine = ThrowingEngine(failure),
                recorder = BackendEventRecorder(events::add),
                timeSource = sequenceTimeSource(0L, 7_000_000L),
            ).use { gateway -> gateway.currentUser(URI("http://private.invalid/2.0")) }
        }.exceptionOrNull()

        val event = events.single() as BackendLogEvent.BitbucketRequestFailed
        assertTrue(observed is AssertionError, "observed=$observed events=$events")
        assertEquals("current_user", event.operation)
        assertEquals("unexpected", event.category)
        assertNull(event.status)
        assertTrue(event.unexpectedFailure is AssertionError)
        assertFalse(event.toString().contains("PRIVATE-URL"))
        assertFalse(event.toString().contains("header-secret"))
        assertFalse(event.toString().contains("body-secret"))
    }

    @Test
    fun `throwing recorder cannot replace a successful Bitbucket result`() = runBlocking {
        var attempts = 0
        val recorder = BackendEventRecorder {
            attempts += 1
            throw IllegalStateException("recorder-private")
        }
        withServer(handler = { exchange -> exchange.respond(200, fixture("current-user.json")) }) { apiBaseUrl ->
            gateway(
                recorder = recorder,
                timeSource = sequenceTimeSource(0L, 7_000_000L),
            ).use { gateway ->
                assertTrue(gateway.currentUser(URI("$apiBaseUrl/configured/2.0")) is GatewayResult.Success)
            }
        }
        assertEquals(1, attempts)
    }

    @Test
    fun `Bitbucket cancellation rethrows without a terminal event`() = runBlocking {
        val events = mutableListOf<BackendLogEvent>()
        val received = CountDownLatch(1)
        val release = CountDownLatch(1)
        try {
            withServer(handler = { exchange ->
                received.countDown()
                release.await(5, TimeUnit.SECONDS)
                runCatching { exchange.respond(200, fixture("activity.json")) }
            }) { apiBaseUrl ->
                gateway(
                    timeout = Duration.ofSeconds(5),
                    recorder = BackendEventRecorder(events::add),
                    timeSource = sequenceTimeSource(0L),
                ).use { gateway ->
                    val pending = async(Dispatchers.Default) { gateway.listActivity(repository(apiBaseUrl), 42) }
                    assertTrue(received.await(1, TimeUnit.SECONDS))
                    pending.cancel()
                    assertTrue(runCatching { pending.await() }.exceptionOrNull() is CancellationException)
                }
            }
        } finally {
            release.countDown()
        }
        assertTrue(events.isEmpty())
    }

    @Test
    fun `records one safe debug event for a successful current-user request`() = runBlocking {
        val events = mutableListOf<BackendLogEvent>()
        withServer(handler = { exchange -> exchange.respond(200, fixture("current-user.json")) }) { apiBaseUrl ->
            gateway(
                recorder = BackendEventRecorder(events::add),
                timeSource = sequenceTimeSource(0L, 7_000_000L),
            ).use { gateway ->
                assertTrue(gateway.currentUser(URI("$apiBaseUrl/configured/2.0")) is GatewayResult.Success)
            }
        }

        assertEquals(
            listOf(
                BackendLogEvent.BitbucketRequestCompleted(
                    operation = "current_user",
                    repositoryId = null,
                    status = 200,
                    durationMilliseconds = 7,
                ),
            ),
            events,
        )
        assertEquals("DEBUG", events.single().level.name)
    }

    @Test
    fun `records one safe warning for a mapped HTTP failure`() = runBlocking {
        val events = mutableListOf<BackendLogEvent>()
        withServer(handler = { exchange ->
            exchange.respond(401, "{\"detail\":\"wire-body-secret\"}", mapOf("X-Wire-Header" to "header-secret"))
        }) { apiBaseUrl ->
            gateway(
                recorder = BackendEventRecorder(events::add),
                timeSource = sequenceTimeSource(0L, 7_000_000L),
            ).use { gateway ->
                gateway.getPullRequest(repository(apiBaseUrl), 42)
            }
        }

        val event = events.single() as BackendLogEvent.BitbucketRequestFailed
        assertEquals("pull_request_detail", event.operation)
        assertEquals(repositoryId.value, event.repositoryId)
        assertEquals("authentication", event.category)
        assertEquals(false, event.retryable)
        assertEquals(401, event.status)
        assertEquals(7L, event.durationMilliseconds)
        assertFalse(events.toString().contains("wire-body-secret"))
        assertFalse(events.toString().contains("X-Wire-Header"))
    }

    @Test
    fun `maps authoritative branch freshness and paginated conflict absence into seven of seven readiness`() = runBlocking {
        val requests = mutableListOf<Pair<URI, String?>>()
        withServer(handler = { exchange ->
            requests += exchange.requestURI to exchange.requestHeaders.getFirst("Authorization")
            val body = when {
                exchange.requestURI.path.endsWith("/pullrequests/42") ->
                    fixture("pull-request-detail.json").replace("\"unresolved_comment_count\": 2", "\"unresolved_comment_count\": 0")
                exchange.requestURI.path.endsWith("/refs/branches") -> branchPage("main", "fedcba654321")
                exchange.requestURI.path.endsWith("/merge-base/fedcba654321..abc123def456") ->
                    mergeBase("fedcba654321")
                exchange.requestURI.path.endsWith("/file-conflicts/fedcba654321..abc123def456") &&
                    exchange.requestURI.rawQuery == null -> fixture("file-conflicts-page-1.json")
                exchange.requestURI.path.endsWith("/file-conflicts/fedcba654321..abc123def456") &&
                    exchange.requestURI.rawQuery == "page=2" -> fixture("file-conflicts-page-2.json")
                else -> null
            }
            if (body == null) exchange.respond(404, "{}") else exchange.respond(200, body)
        }) { apiBaseUrl ->
            gateway().use { gateway ->
                val result = gateway.getPullRequest(repository(apiBaseUrl), 42)
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
                            unresolvedCommentCount = 0,
                            destinationBranchIsCurrent = true,
                            hasMergeConflicts = false,
                        ),
                    ),
                    result,
                )
                val detail = (result as GatewayResult.Success).value
                val readiness = ObservationAssembler().assemble(
                    detail = detail,
                    reviewers = listOf(GatewayUserObservation(graceId, "Grace Hopper", "grace")),
                    builds = listOf(
                        GatewayBuildObservation("ci", GatewayBuildStatus.SUCCESSFUL, fetchedAt),
                    ),
                    tasks = emptyList(),
                    observedAt = fetchedAt,
                ).readiness
                assertEquals(7, readiness.total)
                assertEquals(7, readiness.passedCount)
            }
        }

        assertEquals(5, requests.size)
        assertEquals(
            listOf(
                "/configured/2.0/repositories/acme-engineering/release-tools/pullrequests/42",
                "/configured/2.0/repositories/acme-engineering/release-tools/refs/branches",
                "/configured/2.0/repositories/acme-engineering/release-tools/merge-base/fedcba654321..abc123def456",
                "/configured/2.0/repositories/acme-engineering/release-tools/file-conflicts/fedcba654321..abc123def456",
                "/configured/2.0/repositories/acme-engineering/release-tools/file-conflicts/fedcba654321..abc123def456?page=2",
            ),
            requests.map { (uri, _) ->
                uri.path + if (uri.queryParameters()["page"] == "2") "?page=2" else ""
            },
        )
        assertEquals(mapOf("q" to "name = \"main\""), requests[1].first.queryParameters())
        val expectedAuthorization = "Basic " + java.util.Base64.getEncoder()
            .encodeToString("detail-user:detail-password".toByteArray(UTF_8))
        assertTrue(requests.all { (_, authorization) -> authorization == expectedAuthorization })
    }

    @Test
    fun `preserves a nonzero authoritative unresolved comment count`() = runBlocking {
        withServer(handler = { exchange ->
            val body = when {
                exchange.requestURI.path.endsWith("/pullrequests/42") -> fixture("pull-request-detail.json")
                exchange.requestURI.path.endsWith("/refs/branches") -> branchPage("main", "fedcba654321")
                exchange.requestURI.path.contains("/merge-base/") -> mergeBase("fedcba654321")
                exchange.requestURI.path.contains("/file-conflicts/") -> """{"values":[]}"""
                else -> null
            }
            if (body == null) exchange.respond(404, "{}") else exchange.respond(200, body)
        }) { apiBaseUrl ->
            gateway().use { gateway ->
                val result = gateway.getPullRequest(repository(apiBaseUrl), 42)
                assertTrue(result is GatewayResult.Success)
                assertEquals(2, (result as GatewayResult.Success).value.unresolvedCommentCount)
            }
        }
    }

    @Test
    fun `does not trust undocumented pull request readiness hints over authoritative operations`() = runBlocking {
        val responseBody = fixture("pull-request-detail.json")
            .replace("\"name\": \"main\"", "\"name\": \"main\", \"is_current\": true")
            .replace("\"comment_count\": 3,", "\"comment_count\": 3, \"has_conflicts\": false,")
        withServer(handler = { exchange ->
            val body = when {
                exchange.requestURI.path.endsWith("/pullrequests/42") -> responseBody
                exchange.requestURI.path.endsWith("/refs/branches") -> branchPage("main", "deadbeef1234")
                exchange.requestURI.path.contains("/merge-base/") -> mergeBase("a11ce0000001")
                exchange.requestURI.path.contains("/file-conflicts/") ->
                    """{"values":[{"type":"file_conflict","path":"private-sentinel.txt","scenario":"content","message":"private-conflict-sentinel"}]}"""
                else -> null
            }
            if (body == null) exchange.respond(404, "{}") else exchange.respond(200, body)
        }) { apiBaseUrl ->
            gateway().use { gateway ->
                val result = gateway.getPullRequest(repository(apiBaseUrl), 42)
                assertTrue(result is GatewayResult.Success)
                val detail = (result as GatewayResult.Success).value
                assertEquals(false, detail.destinationBranchIsCurrent)
                assertEquals(true, detail.hasMergeConflicts)
                assertFalse(result.toString().contains("private-sentinel"))
            }
        }
    }

    @Test
    fun `missing branch target and malformed conflict pages remain unavailable instead of inferred`() = runBlocking {
        val invalidResponses = listOf(
            "branch" to """{"type":"branch","name":"main"}""",
            "merge-base" to """{"type":"commit"}""",
            "conflicts" to """{"pagelen":10}""",
        )
        invalidResponses.forEach { (invalidEndpoint, invalidBody) ->
            withServer(handler = { exchange ->
                val body = when {
                    exchange.requestURI.path.endsWith("/pullrequests/42") -> fixture("pull-request-detail.json")
                    exchange.requestURI.path.endsWith("/refs/branches") ->
                        if (invalidEndpoint == "branch") """{"values":[$invalidBody]}"""
                        else branchPage("main", "fedcba654321")
                    exchange.requestURI.path.contains("/merge-base/") ->
                        if (invalidEndpoint == "merge-base") invalidBody else mergeBase("fedcba654321")
                    exchange.requestURI.path.contains("/file-conflicts/") ->
                        if (invalidEndpoint == "conflicts") invalidBody else """{"values":[]}"""
                    else -> null
                }
                if (body == null) exchange.respond(404, "{}") else exchange.respond(200, body)
            }) { apiBaseUrl ->
                gateway().use { gateway ->
                    assertEquals(malformedFailure(), gateway.getPullRequest(repository(apiBaseUrl), 42))
                }
            }
        }
    }

    @Test
    fun `readiness dependency HTTP failures stay typed without exposing upstream content`() = runBlocking {
        val privateSentinel = "readiness-private-sentinel-7d31"
        for (failedEndpoint in listOf("branch", "merge-base", "conflicts")) {
            withServer(handler = { exchange ->
                when {
                    exchange.requestURI.path.endsWith("/pullrequests/42") ->
                        exchange.respond(200, fixture("pull-request-detail.json"))
                    exchange.requestURI.path.endsWith("/refs/branches") && failedEndpoint != "branch" ->
                        exchange.respond(200, branchPage("main", "fedcba654321"))
                    exchange.requestURI.path.contains("/merge-base/") && failedEndpoint != "merge-base" ->
                        exchange.respond(200, mergeBase("fedcba654321"))
                    else -> exchange.respond(503, """{"detail":"$privateSentinel"}""")
                }
            }) { apiBaseUrl ->
                gateway().use { gateway ->
                    val result = gateway.getPullRequest(repository(apiBaseUrl), 42)
                    assertEquals(
                        GatewayResult.Failure(
                            GatewayFailure(GatewayFailureCategory.UPSTREAM, retryable = true, retryAt = null),
                        ),
                        result,
                    )
                    assertFalse(result.toString().contains(privateSentinel))
                }
            }
        }
    }

    @Test
    fun `file conflict pagination rejects cross origin next links without returning partial readiness`() = runBlocking {
        var requestCount = 0
        withServer(handler = { exchange ->
            requestCount += 1
            val body = when {
                exchange.requestURI.path.endsWith("/pullrequests/42") -> fixture("pull-request-detail.json")
                exchange.requestURI.path.endsWith("/refs/branches") -> branchPage("main", "fedcba654321")
                exchange.requestURI.path.contains("/merge-base/") -> mergeBase("fedcba654321")
                exchange.requestURI.path.contains("/file-conflicts/") ->
                    """{"values":[],"next":"https://attacker.example/2.0/private"}"""
                else -> null
            }
            if (body == null) exchange.respond(404, "{}") else exchange.respond(200, body)
        }) { apiBaseUrl ->
            gateway().use { gateway ->
                assertEquals(
                    unsafePaginationFailure(),
                    gateway.getPullRequest(repository(apiBaseUrl), 42),
                )
            }
        }
        assertEquals(4, requestCount)
    }

    @Test
    fun `slash branch is resolved by exact-name filtered paginated list`() = runBlocking {
        val requests = mutableListOf<URI>()
        val detail = fixture("pull-request-detail.json")
            .replace("\"name\": \"main\"", "\"name\": \"feature/foo\"")
        withServer(handler = { exchange ->
            requests += exchange.requestURI
            val body = when {
                exchange.requestURI.path.endsWith("/pullrequests/42") -> detail
                exchange.requestURI.path.endsWith("/refs/branches") &&
                    exchange.requestURI.queryParameters()["page"] == null -> branchPage(
                        "feature/foo-old",
                        "deadbeef1234",
                        "/configured/2.0/repositories/acme-engineering/release-tools/refs/branches?page=2",
                    )
                exchange.requestURI.path.endsWith("/refs/branches") ->
                    branchPage("feature/foo", "fedcba654321")
                exchange.requestURI.path.contains("/merge-base/") -> mergeBase("fedcba654321")
                exchange.requestURI.path.contains("/file-conflicts/") -> """{"values":[]}"""
                else -> null
            }
            if (body == null) exchange.respond(404, "{}") else exchange.respond(200, body)
        }) { apiBaseUrl ->
            gateway().use { gateway ->
                val result = gateway.getPullRequest(repository(apiBaseUrl), 42)
                assertTrue(result is GatewayResult.Success)
                val value = (result as GatewayResult.Success).value
                assertEquals(true, value.destinationBranchIsCurrent)
                assertEquals(false, value.hasMergeConflicts)
            }
        }

        assertEquals(5, requests.size)
        assertEquals("/configured/2.0/repositories/acme-engineering/release-tools/refs/branches", requests[1].path)
        assertEquals(mapOf("q" to "name = \"feature/foo\""), requests[1].queryParameters())
        assertEquals(mapOf("page" to "2"), requests[2].queryParameters())
        assertFalse(requests.any { it.path.contains("/refs/branches/feature/foo") })
    }

    @Test
    fun `live destination equality is stale when merge base is older than destination`() = runBlocking {
        val requests = mutableListOf<URI>()
        withServer(handler = { exchange ->
            requests += exchange.requestURI
            val body = when {
                exchange.requestURI.path.endsWith("/pullrequests/42") -> fixture("pull-request-detail.json")
                exchange.requestURI.path.endsWith("/refs/branches") -> branchPage("main", "fedcba654321")
                exchange.requestURI.path.contains("/merge-base/") -> mergeBase("a11ce0000001")
                exchange.requestURI.path.contains("/file-conflicts/") -> """{"values":[]}"""
                else -> null
            }
            if (body == null) exchange.respond(404, "{}") else exchange.respond(200, body)
        }) { apiBaseUrl ->
            gateway().use { gateway ->
                val result = gateway.getPullRequest(repository(apiBaseUrl), 42)
                assertTrue(result is GatewayResult.Success)
                assertEquals(false, (result as GatewayResult.Success).value.destinationBranchIsCurrent)
            }
        }
        assertTrue(requests.any { it.path.endsWith("/merge-base/fedcba654321..abc123def456") })
        assertTrue(requests.any { it.path.endsWith("/file-conflicts/fedcba654321..abc123def456") })
    }

    @Test
    fun `source containing live destination is stale when pull request observed an older destination`() = runBlocking {
        val requests = mutableListOf<URI>()
        val detailWithOlderObservedDestination = fixture("pull-request-detail.json")
            .replace("\"hash\": \"fedcba654321\"", "\"hash\": \"a11ce0000001\"")
        withServer(handler = { exchange ->
            requests += exchange.requestURI
            val body = when {
                exchange.requestURI.path.endsWith("/pullrequests/42") -> detailWithOlderObservedDestination
                exchange.requestURI.path.endsWith("/refs/branches") -> branchPage("main", "fedcba654321")
                exchange.requestURI.path.contains("/merge-base/") -> mergeBase("fedcba654321")
                exchange.requestURI.path.contains("/file-conflicts/") -> """{"values":[]}"""
                else -> null
            }
            if (body == null) exchange.respond(404, "{}") else exchange.respond(200, body)
        }) { apiBaseUrl ->
            gateway().use { gateway ->
                val result = gateway.getPullRequest(repository(apiBaseUrl), 42)
                assertTrue(result is GatewayResult.Success)
                assertEquals(false, (result as GatewayResult.Success).value.destinationBranchIsCurrent)
            }
        }
        assertTrue(requests.any { it.path.endsWith("/merge-base/fedcba654321..abc123def456") })
        assertTrue(requests.any { it.path.endsWith("/file-conflicts/fedcba654321..abc123def456") })
    }

    @Test
    fun `branch lookup requires exactly one exact-name match`() = runBlocking {
        val branchCollections = listOf(
            """{"values":[{"type":"branch","name":"main-old","target":{"type":"commit","hash":"deadbeef1234"}}]}""",
            """{"values":[
                {"type":"branch","name":"main","target":{"type":"commit","hash":"fedcba654321"}},
                {"type":"branch","name":"main","target":{"type":"commit","hash":"deadbeef1234"}}
            ]}""".trimIndent(),
        )
        for (branches in branchCollections) {
            withServer(handler = { exchange ->
                val body = when {
                    exchange.requestURI.path.endsWith("/pullrequests/42") -> fixture("pull-request-detail.json")
                    exchange.requestURI.path.endsWith("/refs/branches") -> branches
                    else -> null
                }
                if (body == null) exchange.respond(404, "{}") else exchange.respond(200, body)
            }) { apiBaseUrl ->
                gateway().use { gateway ->
                    assertEquals(malformedFailure(), gateway.getPullRequest(repository(apiBaseUrl), 42))
                }
            }
        }
    }

    @Test
    fun `total comment count never substitutes for missing unresolved count`() = runBlocking {
        val responseBody = fixture("pull-request-detail.json")
            .replace("  \"unresolved_comment_count\": 2,\n", "")
        withServer(handler = { exchange -> exchange.respond(200, responseBody) }) { apiBaseUrl ->
            gateway().use { gateway ->
                assertEquals(malformedFailure(), gateway.getPullRequest(repository(apiBaseUrl), 42))
            }
        }
    }

    @ParameterizedTest(name = "PR web URL with {0} is rejected for detail and summary")
    @MethodSource("unsafePullRequestWebUrls")
    fun `pull request links reject unsafe URI components`(
        @Suppress("UNUSED_PARAMETER") label: String,
        unsafeUrl: String,
    ) = runBlocking {
        withServer(handler = { exchange ->
            val body = if (exchange.requestURI.path.endsWith("/pullrequests/42")) {
                fixture("pull-request-detail.json").replace(detailWebUrl, unsafeUrl)
            } else {
                fixture("pull-requests-page-1.json").replace(summaryWebUrl, unsafeUrl)
            }
            exchange.respond(200, body)
        }) { apiBaseUrl ->
            gateway().use { gateway ->
                val repository = repository(apiBaseUrl)
                assertEquals(malformedFailure(), gateway.getPullRequest(repository, 42))
                assertEquals(malformedFailure(), gateway.listAuthoredOpenPullRequests(repository, authorId))
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
    fun `activity mapping keeps the thread root identity for replies`() = runBlocking {
        withServer(handler = { exchange ->
            val body = if (exchange.requestURI.rawQuery == "page=2") activityPageTwo else fixture("activity.json")
            exchange.respond(200, body)
        }) { apiBaseUrl ->
            gateway().use { gateway ->
                val result = gateway.listActivity(repository(apiBaseUrl), 42)
                val activity = (result as GatewayResult.Success).value

                assertEquals("501", activity.single { it.sourceKind == GatewayActivityKind.REPLY }.sourceId)
                assertEquals(
                    ActivityVersion("av_reply-502-1786797360000-d0-r1"),
                    activity.single { it.sourceKind == GatewayActivityKind.REPLY }.activityVersion,
                )
            }
        }
    }

    @Test
    fun `non-canonical activity links are malformed without leaking the rejected URL`() = runBlocking {
        val rejectedCommentUrl = "http://bitbucket.org/private-comment-sentinel"
        val rejectedChangesUrl = "http://bitbucket.org/private-changes-sentinel"
        val uppercaseCommentUrl = "HTTPS://bitbucket.org/private-uppercase-comment-sentinel#comment-501"
        val mixedCaseChangesUrl = "hTtPs://bitbucket.org/private-mixed-changes-sentinel"
        val payloads = listOf(
            fixture("activity.json")
                .replace(
                    "https://bitbucket.org/acme-engineering/release-tools/pull-requests/42#comment-501",
                    rejectedCommentUrl,
                )
                .replace(",\n  \"next\": \"/configured/2.0/repositories/acme-engineering/release-tools/pullrequests/42/activity?page=2\"", ""),
            activityPageTwo.replace(
                "https://bitbucket.org/acme-engineering/release-tools/pull-requests/42#changes-request",
                rejectedChangesUrl,
            ),
            fixture("activity.json")
                .replace(
                    "https://bitbucket.org/acme-engineering/release-tools/pull-requests/42#comment-501",
                    uppercaseCommentUrl,
                )
                .replace(",\n  \"next\": \"/configured/2.0/repositories/acme-engineering/release-tools/pullrequests/42/activity?page=2\"", ""),
            activityPageTwo.replace(
                "https://bitbucket.org/acme-engineering/release-tools/pull-requests/42#changes-request",
                mixedCaseChangesUrl,
            ),
        )

        payloads.forEach { body ->
            withServer(handler = { exchange -> exchange.respond(200, body) }) { apiBaseUrl ->
                gateway().use { gateway ->
                    val result = gateway.listActivity(repository(apiBaseUrl), 42)
                    assertEquals(malformedFailure(), result)
                    assertFalse(result.toString().contains(rejectedCommentUrl))
                    assertFalse(result.toString().contains(rejectedChangesUrl))
                    assertFalse(result.toString().contains(uppercaseCommentUrl))
                    assertFalse(result.toString().contains(mixedCaseChangesUrl))
                }
            }
        }
    }

    @Test
    fun `returns raw markdown only from a successful narrowly scoped live content read`() = runBlocking {
        val capturedRequests = mutableListOf<String>()
        withServer(handler = { exchange ->
            capturedRequests += exchange.requestMethod + " " + exchange.requestURI.path
            exchange.respond(200, fixture("comment.json"))
        }) { apiBaseUrl ->
            gateway().use { gateway ->
                val result = gateway.getLiveActivityContent(repository(apiBaseUrl), 42, "501")
                assertEquals(
                    GatewayResult.Success(
                        GatewayLiveActivityContent(
                            activityVersion = ActivityVersion("av_comment-501-1786797300000-d0-r0"),
                            markdown = liveMarkdown,
                            fetchedAt = fetchedAt,
                        ),
                    ),
                    result,
                )
                assertTrue(result is GatewayResult.Success)
                assertEquals(liveMarkdown, (result as GatewayResult.Success).value.markdown)
                assertFalse(result.toString().contains(liveMarkdown))
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

    @Test
    fun `generated clients reject cross origin redirects without contacting the target`() = runBlocking {
        val redirectedRequests = AtomicInteger()
        val redirectSentinel = "cross-origin-redirect-sentinel-31b8"
        withServer(handler = { exchange ->
            redirectedRequests.incrementAndGet()
            val body = if (exchange.requestURI.path.endsWith("/user")) {
                fixture("current-user.json")
            } else {
                fixture("pull-request-detail.json")
            }
            exchange.respond(200, body)
        }) { redirectTarget ->
            withServer(handler = { exchange ->
                val targetPath = if (exchange.requestURI.path.endsWith("/user")) "user" else "pull-request"
                exchange.respond(
                    302,
                    "",
                    mapOf("Location" to "$redirectTarget/redirected/$targetPath?marker=$redirectSentinel"),
                )
            }) { apiBaseUrl ->
                gateway().use { gateway ->
                    val pullRequestResult = gateway.getPullRequest(repository(apiBaseUrl), 42)
                    val identityResult = gateway.currentUser(URI("$apiBaseUrl/configured/2.0"))
                    assertSafeRedirectFailure(pullRequestResult, redirectTarget.toString(), redirectSentinel)
                    assertSafeRedirectFailure(identityResult, redirectTarget.toString(), redirectSentinel)
                }
            }
        }

        assertEquals(0, redirectedRequests.get())
    }

    @Test
    fun `generated pull request client rejects redirects outside configured API path`() = runBlocking {
        val redirectedRequests = AtomicInteger()
        val redirectSentinel = "out-of-scope-redirect-sentinel-54d2"
        withServer(handler = { exchange ->
            if (exchange.requestURI.path.startsWith("/configured/2.0/")) {
                exchange.respond(
                    302,
                    "",
                    mapOf("Location" to "/outside-configured-scope/pull-request?marker=$redirectSentinel"),
                )
            } else {
                redirectedRequests.incrementAndGet()
                exchange.respond(200, fixture("pull-request-detail.json"))
            }
        }) { apiBaseUrl ->
            gateway().use { gateway ->
                assertSafeRedirectFailure(
                    gateway.getPullRequest(repository(apiBaseUrl), 42),
                    "$apiBaseUrl/outside-configured-scope/pull-request",
                    redirectSentinel,
                )
            }
        }

        assertEquals(0, redirectedRequests.get())
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
        val requestNumber = AtomicInteger()
        val timeoutReceived = CountDownLatch(1)
        val timeoutRelease = CountDownLatch(1)
        val cancellationReceived = CountDownLatch(1)
        val cancellationRelease = CountDownLatch(1)
        try {
            withServer(handler = { exchange ->
                if (requestNumber.incrementAndGet() == 1) {
                    timeoutReceived.countDown()
                    timeoutRelease.await(5, TimeUnit.SECONDS)
                } else {
                    cancellationReceived.countDown()
                    cancellationRelease.await(5, TimeUnit.SECONDS)
                }
                runCatching { exchange.respond(200, fixture("activity.json")) }
            }) { apiBaseUrl ->
                gateway(Duration.ofMillis(100)).use { gateway ->
                    assertEquals(timeoutFailure(), gateway.listActivity(repository(apiBaseUrl), 42))
                }
                assertTrue(timeoutReceived.await(1, TimeUnit.SECONDS))
                gateway(Duration.ofSeconds(5)).use { gateway ->
                    val pending = async(Dispatchers.Default) { gateway.listActivity(repository(apiBaseUrl), 42) }
                    assertTrue(cancellationReceived.await(1, TimeUnit.SECONDS))
                    pending.cancel()
                    assertTrue(runCatching { pending.await() }.exceptionOrNull() is CancellationException)
                }
            }
        } finally {
            timeoutRelease.countDown()
            cancellationRelease.countDown()
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

    private fun gateway(
        timeout: Duration = Duration.ofSeconds(2),
        recorder: BackendEventRecorder = BackendEventRecorder.NONE,
        timeSource: MonotonicTimeSource = MonotonicTimeSource.SYSTEM,
        engine: HttpClientEngine = io.ktor.client.engine.cio.CIO.create(),
    ): GeneratedBitbucketGateway =
        GeneratedBitbucketGateway.create(
            requestTimeout = timeout,
            username = "detail-user",
            appPassword = "detail-password",
            engine = engine,
            clock = Clock.fixed(fetchedAt, ZoneOffset.UTC),
            recorder = recorder,
            timeSource = timeSource,
        )

    private fun sequenceTimeSource(vararg values: Long): MonotonicTimeSource {
        val iterator = values.iterator()
        return MonotonicTimeSource { check(iterator.hasNext()) { "time source exhausted" }; iterator.nextLong() }
    }

    private fun repository(apiBaseUrl: URI): GatewayRepositoryAddress = GatewayRepositoryAddress(
        id = repositoryId,
        apiBaseUrl = URI("$apiBaseUrl/configured/2.0"),
        workspaceSlug = "acme-engineering",
        repositorySlug = "release-tools",
    )

    private fun fixture(name: String): String = requireNotNull(javaClass.getResource("/bitbucket/v1/$name")).readText()

    private fun branchPage(name: String, target: String, next: String? = null): String =
        """{"values":[{"type":"branch","name":"$name","target":{"type":"commit","hash":"$target"}}]""" +
            (next?.let { ",\"next\":\"$it\"}" } ?: "}")

    private fun pullRequestPageWithNext(): String =
        """{"values":[],"next":"/configured/2.0/repositories/acme-engineering/release-tools/pullrequests?page=2"}"""

    private fun mergeBase(hash: String): String = """{"type":"commit","hash":"$hash"}"""

    private fun URI.queryParameters(): Map<String, String> =
        rawQuery.orEmpty().split('&').filter(String::isNotEmpty).associate { item ->
            val (key, value) = item.split('=', limit = 2).let { it[0] to it.getOrElse(1) { "" } }
            URLDecoder.decode(key, UTF_8) to URLDecoder.decode(value, UTF_8)
        }

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

    private suspend fun withStoppableServer(
        handler: (HttpExchange, HttpServer) -> Unit,
        block: suspend (URI) -> Unit,
    ) {
        val executor = Executors.newCachedThreadPool()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.executor = executor
        server.createContext("/") { exchange ->
            try {
                handler(exchange, server)
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

    private fun networkFailure() = GatewayResult.Failure(
        GatewayFailure(GatewayFailureCategory.NETWORK, retryable = true, retryAt = null),
    )

    @OptIn(io.ktor.utils.io.InternalAPI::class)
    private class ThrowingEngine(private val failure: Throwable) : HttpClientEngineBase("throwing-test-engine") {
        override val config = HttpClientEngineConfig()
        override val supportedCapabilities = setOf(HttpTimeoutCapability)

        override suspend fun execute(data: HttpRequestData): HttpResponseData = throw failure
    }

    private fun assertSafeRedirectFailure(result: GatewayResult<*>, location: String, sentinel: String) {
        assertEquals(malformedFailure(), result)
        val diagnostics = result.toString()
        assertFalse(diagnostics.contains(location))
        assertFalse(diagnostics.contains("marker=$sentinel"))
        assertFalse(diagnostics.contains(sentinel))
    }

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
        val activityPageTwo = """{"values":[{"comment":{"type":"pullrequest_comment","id":503,"created_on":"2026-08-15T10:10:00Z","updated_on":"2026-08-15T12:37:00Z","content":{"raw":"deleted body must not escape"},"user":{"type":"user","uuid":"$graceId","display_name":"Grace Hopper"},"deleted":true,"links":{"html":{"href":"https://bitbucket.org/acme-engineering/release-tools/pull-requests/42#comment-503"}}}},{"changes_request":{"date":"2026-08-15T12:38:00Z","user":{"type":"user","uuid":"$margaretId","display_name":"Margaret Hamilton"}},"pull_request":{"type":"pullrequest","id":42,"links":{"html":{"href":"https://bitbucket.org/acme-engineering/release-tools/pull-requests/42#changes-request"}}}}]}"""

        const val detailWebUrl = "https://bitbucket.org/acme-engineering/release-tools/pull-requests/42"
        const val summaryWebUrl = "https://bitbucket.org/acme-engineering/release-tools/pull-requests/41"

        val expectedActivity = listOf(
            GatewayActivityObservation(GatewayActivityKind.COMMENT, "501", graceId, "Grace Hopper", Instant.parse("2026-08-15T12:35:00Z"), ActivityVersion("av_comment-501-1786797300000-d0-r0"), resolved = false, deleted = false, URI("https://bitbucket.org/acme-engineering/release-tools/pull-requests/42#comment-501")),
            GatewayActivityObservation(GatewayActivityKind.REPLY, "501", margaretId, "Margaret Hamilton", Instant.parse("2026-08-15T12:36:00Z"), ActivityVersion("av_reply-502-1786797360000-d0-r1"), resolved = true, deleted = false, URI("https://bitbucket.org/acme-engineering/release-tools/pull-requests/42#comment-502")),
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
        fun observedHttpFailures(): List<Arguments> = listOf(
            Arguments.of(401, "authentication", false),
            Arguments.of(403, "authorization", false),
            Arguments.of(404, "not_found", false),
            Arguments.of(429, "rate_limited", true),
            Arguments.of(503, "upstream", true),
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

        @JvmStatic
        fun unsafePullRequestWebUrls(): List<Arguments> = listOf(
            Arguments.of("non-HTTPS scheme", "http://bitbucket.org/acme-engineering/release-tools/pull-requests/42"),
            Arguments.of("uppercase HTTPS scheme", "HTTPS://bitbucket.org/acme-engineering/release-tools/pull-requests/42"),
            Arguments.of("mixed-case HTTPS scheme", "hTtPs://bitbucket.org/acme-engineering/release-tools/pull-requests/42"),
            Arguments.of("userinfo", "https://user:password@bitbucket.org/acme-engineering/release-tools/pull-requests/42"),
            Arguments.of("query", "$detailWebUrl?token=unsafe"),
            Arguments.of("fragment", "$detailWebUrl#unsafe"),
        )
    }
}

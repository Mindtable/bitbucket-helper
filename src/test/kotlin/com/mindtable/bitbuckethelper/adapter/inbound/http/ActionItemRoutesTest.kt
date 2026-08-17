package com.mindtable.bitbuckethelper.adapter.inbound.http

import com.mindtable.bitbuckethelper.application.model.AcknowledgeActionItemCommand
import com.mindtable.bitbuckethelper.application.model.AcknowledgeActionItemResult
import com.mindtable.bitbuckethelper.application.model.ActionItemProjection
import com.mindtable.bitbuckethelper.application.model.ActionItemState
import com.mindtable.bitbuckethelper.application.model.ActorProjection
import com.mindtable.bitbuckethelper.application.model.GetLiveActivityContentCommand
import com.mindtable.bitbuckethelper.application.model.LiveActivityContentResult
import com.mindtable.bitbuckethelper.application.model.LiveContentUnavailableReason
import com.mindtable.bitbuckethelper.domain.shared.ActionItemId
import com.mindtable.bitbuckethelper.domain.shared.ActivityVersion
import com.mindtable.bitbuckethelper.domain.shared.PullRequestId
import com.mindtable.bitbuckethelper.domain.shared.RepositoryId
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import java.net.URI
import java.time.Instant
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ActionItemRoutesTest {
    @Test
    fun `live content maps every result and every unavailable reason under typed 200`() = testApplication {
        val unavailableReasons = LiveContentUnavailableReason.entries
        val results = ArrayDeque<LiveActivityContentResult>().apply {
            add(
                LiveActivityContentResult.ContentAvailable(
                    ACTION_ITEM_ID,
                    REQUESTED_VERSION,
                    RAW_MARKDOWN,
                    Instant.parse("2026-08-17T10:00:00Z"),
                ),
            )
            add(LiveActivityContentResult.StaleActivityVersion(ACTION_ITEM_ID, REQUESTED_VERSION, actionItem()))
            add(
                LiveActivityContentResult.NewerActivityObserved(
                    ACTION_ITEM_ID,
                    REQUESTED_VERSION,
                    ActivityVersion("av_observed-02"),
                    RepositoryId("repo_zeta-02"),
                ),
            )
            unavailableReasons.forEachIndexed { index, reason ->
                add(
                    LiveActivityContentResult.ContentUnavailable(
                        ACTION_ITEM_ID,
                        REQUESTED_VERSION,
                        reason,
                        retryable = index == 0,
                        retryAt = if (index == 0) Instant.parse("2026-08-17T10:05:00Z") else null,
                    ),
                )
            }
            add(LiveActivityContentResult.ActionItemNotFound(ACTION_ITEM_ID, REQUESTED_VERSION))
        }
        val fake = FakeActionItemDependencies(liveResults = results)
        application { installActionItemApi(fake) }

        val available = client.get(contentUrl()).assertEnvelope("contentAvailable")
        assertEquals(RAW_MARKDOWN, available.string("markdown"))
        assertEquals("2026-08-17T10:00:00Z", available.string("fetchedAt"))

        val staleText = client.get(contentUrl()).bodyAsText()
        val stale = staleText.result("staleActivityVersion")
        assertEquals("ai_current-02", stale.objectValue("current").string("actionItemId"))
        assertEquals(JsonNull, stale.objectValue("current")["acknowledgedAt"])
        assertFalse(staleText.contains(RAW_MARKDOWN))

        val newerText = client.get(contentUrl()).bodyAsText()
        val newer = newerText.result("newerActivityObserved")
        assertEquals("av_observed-02", newer.string("observedVersion"))
        assertEquals("repo_zeta-02", newer.string("repositoryId"))
        assertFalse(newerText.contains(RAW_MARKDOWN))

        val expectedReasons = listOf(
            "authentication",
            "authorization",
            "rateLimited",
            "timeout",
            "network",
            "upstream",
            "malformedUpstream",
            "deleted",
        )
        expectedReasons.forEachIndexed { index, expectedReason ->
            val text = client.get(contentUrl()).bodyAsText()
            val unavailable = text.result("contentUnavailable")
            assertEquals(expectedReason, unavailable.string("reason"))
            assertEquals(index == 0, unavailable.boolean("retryable"))
            assertEquals(
                if (index == 0) "2026-08-17T10:05:00Z" else null,
                unavailable["retryAt"]?.jsonPrimitive?.contentOrNull,
            )
            assertFalse(text.contains(RAW_MARKDOWN))
        }

        val missingText = client.get(contentUrl()).bodyAsText()
        val missing = missingText.result("actionItemNotFound")
        assertEquals("ai_target-01", missing.string("actionItemId"))
        assertEquals("av_requested-01", missing.string("requestedVersion"))
        assertFalse(missingText.contains(RAW_MARKDOWN))

        assertEquals(12, fake.liveCommands.size)
        assertTrue(fake.liveCommands.all { it == GetLiveActivityContentCommand(ACTION_ITEM_ID, REQUESTED_VERSION) })
    }

    @Test
    fun `acknowledgment maps every exact-version business result under 200`() = testApplication {
        val fake = FakeActionItemDependencies(
            acknowledgmentResults = ArrayDeque(
                listOf(
                    AcknowledgeActionItemResult.Acknowledged(
                        ACTION_ITEM_ID,
                        REQUESTED_VERSION,
                        Instant.parse("2026-08-17T10:01:00Z"),
                    ),
                    AcknowledgeActionItemResult.AlreadyAcknowledged(ACTION_ITEM_ID, REQUESTED_VERSION),
                    AcknowledgeActionItemResult.StaleActivityVersion(
                        ACTION_ITEM_ID,
                        REQUESTED_VERSION,
                        actionItem(),
                    ),
                    AcknowledgeActionItemResult.AcknowledgmentRejected(ACTION_ITEM_ID, REQUESTED_VERSION),
                    AcknowledgeActionItemResult.ActionItemNotFound(ACTION_ITEM_ID, REQUESTED_VERSION),
                ),
            ),
        )
        application { installActionItemApi(fake) }

        val bodies = buildList {
            repeat(5) {
                add(
                    client.put("/api/v1/action-items/ai_target-01/acknowledgment") {
                        contentType(ContentType.Application.Json)
                        setBody("""{"apiVersion":"1","activityVersion":"av_requested-01"}""")
                    }.bodyAsText(),
                )
            }
        }

        assertEquals("2026-08-17T10:01:00Z", bodies[0].result("acknowledged").string("acknowledgedAt"))
        bodies[1].result("alreadyAcknowledged")
        val stale = bodies[2].result("staleActivityVersion")
        assertTrue(stale.boolean("hasNewerActivity"))
        assertEquals("av_current-02", stale.objectValue("current").string("activityVersion"))
        bodies[3].result("acknowledgmentRejected")
        bodies[4].result("actionItemNotFound")
        assertTrue(bodies.none { it.contains(RAW_MARKDOWN) })
        assertEquals(
            List(5) { AcknowledgeActionItemCommand(ACTION_ITEM_ID, REQUESTED_VERSION) },
            fake.acknowledgmentCommands,
        )
    }

    @Test
    fun `action item syntax errors are safe 4xx and invoke no use case`() = testApplication {
        val fake = FakeActionItemDependencies()
        application { installActionItemApi(fake) }

        val requests = listOf(
            client.get("/api/v1/action-items/ai_target-01/content") to "activityVersion",
            client.get("/api/v1/action-items/ai_target-01/content?activityVersion=wrong") to "activityVersion",
            client.get("/api/v1/action-items/wrong/content?activityVersion=av_requested-01") to "actionItemId",
            client.put("/api/v1/action-items/wrong/acknowledgment") {
                contentType(ContentType.Application.Json)
                setBody("""{"apiVersion":"1","activityVersion":"av_requested-01"}""")
            } to "actionItemId",
        )

        requests.forEach { (response, field) -> response.assertRequestError(HttpStatusCode.BadRequest, field) }
        assertTrue(fake.liveCommands.isEmpty())
        assertTrue(fake.acknowledgmentCommands.isEmpty())
    }

    @Test
    fun `acknowledgment rejects malformed bodies versions and media types without leaking content`() =
        testApplication {
            val fake = FakeActionItemDependencies()
            application { installActionItemApi(fake) }

            val malformed = client.put("/api/v1/action-items/ai_target-01/acknowledgment") {
                contentType(ContentType.Application.Json)
                setBody("""{"apiVersion":"1","activityVersion":$RAW_MARKDOWN""")
            }
            val unsupportedVersion = client.put("/api/v1/action-items/ai_target-01/acknowledgment") {
                contentType(ContentType.Application.Json)
                setBody("""{"apiVersion":"2","activityVersion":"av_requested-01"}""")
            }
            val noMediaType = client.put("/api/v1/action-items/ai_target-01/acknowledgment") {
                setBody("""{"apiVersion":"1","activityVersion":"av_requested-01"}""")
            }

            malformed.assertRequestError(HttpStatusCode.BadRequest)
            unsupportedVersion.assertRequestError(HttpStatusCode.BadRequest)
            noMediaType.assertRequestError(HttpStatusCode.UnsupportedMediaType)
            assertFalse(malformed.bodyAsText().contains(RAW_MARKDOWN))
            assertTrue(fake.acknowledgmentCommands.isEmpty())
        }

    @Test
    fun `downstream failure is sanitized and never exposes live markdown`() = testApplication {
        val fake = FakeActionItemDependencies(liveFailure = IllegalStateException(RAW_MARKDOWN))
        application { installActionItemApi(fake) }

        val response = client.get(contentUrl())

        assertEquals(HttpStatusCode.InternalServerError, response.status)
        assertEquals("INTERNAL_SERVER_ERROR", response.bodyAsText().json().objectValue("error").string("code"))
        assertFalse(response.bodyAsText().contains(RAW_MARKDOWN))
        assertEquals(1, fake.liveCommands.size)
    }

    private fun io.ktor.server.application.Application.installActionItemApi(fake: FakeActionItemDependencies) {
        installApiV1(TransportKind.UNIX) {
            installActionItemRoutes(
                ActionItemApiV1Dependencies(
                    getLiveActivityContent = fake.getLiveActivityContent,
                    acknowledgeActionItem = fake.acknowledgeActionItem,
                ),
            )
        }
    }

    private fun contentUrl() =
        "/api/v1/action-items/ai_target-01/content?activityVersion=av_requested-01"

    private suspend fun HttpResponse.assertEnvelope(type: String): JsonObject {
        assertEquals(HttpStatusCode.OK, status)
        assertEquals("no-store", headers[HttpHeaders.CacheControl])
        assertTrue(contentType()?.match(ContentType.Application.Json) == true)
        return bodyAsText().result(type)
    }

    private suspend fun HttpResponse.assertRequestError(status: HttpStatusCode, field: String? = null) {
        assertEquals(status, this.status)
        val body = bodyAsText()
        val root = body.json()
        assertEquals("1", root.string("apiVersion"))
        assertTrue(root.string("requestId").matches(Regex("^req_[A-Za-z0-9_-]+$")))
        if (field != null) {
            val violations = root.objectValue("error").getValue("violations").toString()
            assertTrue(violations.contains("\"field\":\"$field\""), violations)
        }
        assertFalse(body.contains(RAW_MARKDOWN))
    }

    private fun String.result(expectedType: String): JsonObject {
        val root = json()
        assertEquals("1", root.string("apiVersion"))
        assertTrue(root.string("requestId").matches(Regex("^req_[A-Za-z0-9_-]+$")))
        val result = root.objectValue("result")
        assertEquals(expectedType, result.string("type"))
        return result
    }

    private fun String.json(): JsonObject = Json.parseToJsonElement(this).jsonObject
    private fun JsonObject.objectValue(name: String): JsonObject = getValue(name).jsonObject
    private fun JsonObject.string(name: String): String = getValue(name).jsonPrimitive.content
    private fun JsonObject.boolean(name: String): Boolean = getValue(name).jsonPrimitive.content.toBooleanStrict()

    private class FakeActionItemDependencies(
        private val liveResults: ArrayDeque<LiveActivityContentResult> = ArrayDeque(),
        private val acknowledgmentResults: ArrayDeque<AcknowledgeActionItemResult> = ArrayDeque(),
        private val liveFailure: Throwable? = null,
    ) {
        val liveCommands = mutableListOf<GetLiveActivityContentCommand>()
        val acknowledgmentCommands = mutableListOf<AcknowledgeActionItemCommand>()

        val getLiveActivityContent = com.mindtable.bitbuckethelper.application.port.inbound.GetLiveActivityContent {
            liveCommands += it
            liveFailure?.let { failure -> throw failure }
            liveResults.removeFirst()
        }
        val acknowledgeActionItem = com.mindtable.bitbuckethelper.application.port.inbound.AcknowledgeActionItem {
            acknowledgmentCommands += it
            acknowledgmentResults.removeFirst()
        }
    }

    companion object {
        private const val RAW_MARKDOWN = "sentinel-private-live-markdown"
        private val ACTION_ITEM_ID = ActionItemId("ai_target-01")
        private val REQUESTED_VERSION = ActivityVersion("av_requested-01")

        private fun actionItem() = ActionItemProjection(
            id = ActionItemId("ai_current-02"),
            pullRequestId = PullRequestId("pr_current-02"),
            repositoryId = RepositoryId("repo_current-02"),
            repositoryDisplayName = "Current Repository",
            pullRequestNumber = 42,
            pullRequestTitle = "Current title",
            activityVersion = ActivityVersion("av_current-02"),
            kind = "comment",
            actor = ActorProjection("actor-1", "Reviewer"),
            activityAt = Instant.parse("2026-08-17T09:59:00Z"),
            state = ActionItemState.OPEN,
            acknowledgedAt = null,
            webUrl = URI("https://bitbucket.example/workspaces/team/repos/repo/pull-requests/42"),
        )
    }
}

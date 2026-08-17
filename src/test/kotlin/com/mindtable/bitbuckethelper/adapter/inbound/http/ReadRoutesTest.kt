package com.mindtable.bitbuckethelper.adapter.inbound.http

import com.mindtable.bitbuckethelper.application.model.ActionItemProjection
import com.mindtable.bitbuckethelper.application.model.ActionItemState
import com.mindtable.bitbuckethelper.application.model.ActorProjection
import com.mindtable.bitbuckethelper.application.model.BuildProjection
import com.mindtable.bitbuckethelper.application.model.BuildState
import com.mindtable.bitbuckethelper.application.model.ConfiguredRepositoryProjection
import com.mindtable.bitbuckethelper.application.model.DashboardPolling
import com.mindtable.bitbuckethelper.application.model.DashboardResult
import com.mindtable.bitbuckethelper.application.model.DashboardSnapshot
import com.mindtable.bitbuckethelper.application.model.Freshness
import com.mindtable.bitbuckethelper.application.model.GetDashboardSnapshotQuery
import com.mindtable.bitbuckethelper.application.model.GetInboxResult
import com.mindtable.bitbuckethelper.application.model.GetPullRequestQuery
import com.mindtable.bitbuckethelper.application.model.GetPullRequestResult
import com.mindtable.bitbuckethelper.application.model.GetSynchronizationStatusResult
import com.mindtable.bitbuckethelper.application.model.InboxProjection
import com.mindtable.bitbuckethelper.application.model.ListPullRequestsResult
import com.mindtable.bitbuckethelper.application.model.PartialFailureMetadata
import com.mindtable.bitbuckethelper.application.model.PullRequestCardProjection
import com.mindtable.bitbuckethelper.application.model.PullRequestDetailProjection
import com.mindtable.bitbuckethelper.application.model.ReadinessCheckProjection
import com.mindtable.bitbuckethelper.application.model.ReadinessProjection
import com.mindtable.bitbuckethelper.application.model.ReadinessSummaryProjection
import com.mindtable.bitbuckethelper.application.model.RepositoryGroupProjection
import com.mindtable.bitbuckethelper.application.model.SynchronizationActivity
import com.mindtable.bitbuckethelper.application.model.SynchronizationAttemptOutcome
import com.mindtable.bitbuckethelper.application.model.SynchronizationFailure
import com.mindtable.bitbuckethelper.application.model.SynchronizationFailureCategory
import com.mindtable.bitbuckethelper.application.model.SynchronizationProblem
import com.mindtable.bitbuckethelper.application.model.SynchronizationProjection
import com.mindtable.bitbuckethelper.application.model.WorkspaceConfigurationProjection
import com.mindtable.bitbuckethelper.application.port.inbound.GetDashboardSnapshot
import com.mindtable.bitbuckethelper.application.port.inbound.GetInbox
import com.mindtable.bitbuckethelper.application.port.inbound.GetPullRequest
import com.mindtable.bitbuckethelper.application.port.inbound.GetSynchronizationStatus
import com.mindtable.bitbuckethelper.application.port.inbound.ListPullRequests
import com.mindtable.bitbuckethelper.domain.shared.ActionItemId
import com.mindtable.bitbuckethelper.domain.shared.ActivityVersion
import com.mindtable.bitbuckethelper.domain.shared.DashboardRevision
import com.mindtable.bitbuckethelper.domain.shared.PullRequestId
import com.mindtable.bitbuckethelper.domain.shared.RepositoryId
import com.mindtable.bitbuckethelper.domain.shared.RepositoryRevision
import com.mindtable.bitbuckethelper.domain.shared.WorkspaceId
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.application.Application
import io.ktor.server.testing.testApplication
import java.net.URI
import java.time.Duration
import java.time.Instant
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ReadRoutesTest {
    private val json = Json
    private val requestIdPattern = Regex("^req_[A-Za-z0-9_-]+$")

    @Test
    fun `dashboard maps every business result with revisions polling nulls and stored order`() = testApplication {
        val dependencies = FakeApiV1Dependencies(
            dashboardResults = ArrayDeque(
                listOf(
                    DashboardResult.SnapshotChanged(dashboardSnapshot()),
                    DashboardResult.SnapshotUnchanged(
                        revision = DashboardRevision("dr_unchanged-02"),
                        serverTime = Instant.parse("2026-08-17T10:30:00Z"),
                        polling = DashboardPolling.Idle,
                    ),
                    DashboardResult.WorkspaceNotConfigured,
                ),
            ),
        )
        application { installReadApi(dependencies) }

        val changedResponse = client.get("/api/v1/dashboard?afterRevision=dr_previous-01")
        val changedText = changedResponse.bodyAsText()
        val changed = assertEnvelope(changedResponse, changedText, "snapshotChanged")
        val snapshot = changed.result().objectValue("snapshot")

        assertEquals("dr_current-02", snapshot.string("dashboardRevision"))
        assertEquals("2026-08-17T10:20:00Z", snapshot.string("generatedAt"))
        assertEquals("active", snapshot.objectValue("polling").string("type"))
        assertEquals(1750L, snapshot.objectValue("polling").long("afterMilliseconds"))
        assertEquals(
            listOf("repo_zeta", "repo_alpha"),
            snapshot.objectValue("workspace").array("repositories").strings("repositoryId"),
        )
        assertEquals(
            listOf("repo_zeta", "repo_alpha"),
            snapshot.array("repositoryGroups").strings("repositoryId"),
        )
        val firstCard = snapshot.array("repositoryGroups")[0].jsonObject
            .array("pullRequests")[0].jsonObject
        assertEquals(
            "rrev_zeta-02",
            snapshot.array("repositoryGroups")[0].jsonObject.string("repositoryRevision"),
        )
        assertEquals(
            listOf("ai_second", "ai_first"),
            firstCard.array("actionItems").strings("actionItemId"),
        )
        assertEquals("av_second", firstCard.array("actionItems")[0].jsonObject.string("activityVersion"))
        assertEquals(JsonNull, firstCard.array("actionItems")[0].jsonObject["acknowledgedAt"])
        assertEquals(
            JsonNull,
            firstCard.objectValue("readiness").array("checks")[0].jsonObject["safeReason"],
        )
        assertFalse(changedText.contains(RAW_ACTIVITY_MARKDOWN))

        val unchangedResponse = client.get("/api/v1/dashboard")
        val unchangedText = unchangedResponse.bodyAsText()
        val unchanged = assertEnvelope(unchangedResponse, unchangedText, "snapshotUnchanged").result()
        assertEquals("dr_unchanged-02", unchanged.string("dashboardRevision"))
        assertEquals("2026-08-17T10:30:00Z", unchanged.string("serverTime"))
        assertEquals("idle", unchanged.objectValue("polling").string("type"))
        assertFalse(unchangedText.contains(RAW_ACTIVITY_MARKDOWN))

        val unconfiguredResponse = client.get("/api/v1/dashboard")
        val unconfiguredText = unconfiguredResponse.bodyAsText()
        val unconfigured = assertEnvelope(
            unconfiguredResponse,
            unconfiguredText,
            "workspaceNotConfigured",
        ).result()
        assertEquals("bitbucket-helper workspace configure", unconfigured.string("setupCommand"))
        assertFalse(unconfiguredText.contains(RAW_ACTIVITY_MARKDOWN))

        assertEquals(
            listOf(
                GetDashboardSnapshotQuery(DashboardRevision("dr_previous-01")),
                GetDashboardSnapshotQuery(null),
                GetDashboardSnapshotQuery(null),
            ),
            dependencies.dashboardQueries,
        )
    }

    @Test
    fun `pull request list maps both results without reordering stored projections`() = testApplication {
        val dependencies = FakeApiV1Dependencies(
            pullRequestListResults = ArrayDeque(
                listOf(
                    ListPullRequestsResult.Available(repositoryGroups()),
                    ListPullRequestsResult.WorkspaceNotConfigured,
                ),
            ),
        )
        application { installReadApi(dependencies) }

        val availableResponse = client.get("/api/v1/pull-requests")
        val availableText = availableResponse.bodyAsText()
        val available = assertEnvelope(availableResponse, availableText, "available").result()
        assertEquals(
            listOf("repo_zeta", "repo_alpha"),
            available.array("repositoryGroups").strings("repositoryId"),
        )
        assertEquals(
            listOf("ai_second", "ai_first"),
            available.array("repositoryGroups")[0].jsonObject.array("pullRequests")[0].jsonObject
                .array("actionItems").strings("actionItemId"),
        )
        assertFalse(availableText.contains(RAW_ACTIVITY_MARKDOWN))

        val unconfiguredResponse = client.get("/api/v1/pull-requests")
        val unconfiguredText = unconfiguredResponse.bodyAsText()
        assertEquals(
            "bitbucket-helper workspace configure",
            assertEnvelope(unconfiguredResponse, unconfiguredText, "workspaceNotConfigured")
                .result().string("setupCommand"),
        )
        assertFalse(unconfiguredText.contains(RAW_ACTIVITY_MARKDOWN))
        assertEquals(2, dependencies.pullRequestListCalls)
    }

    @Test
    fun `pull request detail maps found missing and unconfigured as typed 200 results`() = testApplication {
        val dependencies = FakeApiV1Dependencies(
            pullRequestResults = ArrayDeque(
                listOf(
                    GetPullRequestResult.Found(pullRequestDetail()),
                    GetPullRequestResult.PullRequestNotFound(PullRequestId("pr_missing-02")),
                    GetPullRequestResult.WorkspaceNotConfigured,
                ),
            ),
        )
        application { installReadApi(dependencies) }

        val foundResponse = client.get("/api/v1/pull-requests/pr_found-01")
        val foundText = foundResponse.bodyAsText()
        val detail = assertEnvelope(foundResponse, foundText, "pullRequestFound").result()
            .objectValue("pullRequest")
        assertEquals("pr_detail", detail.objectValue("pullRequest").string("pullRequestId"))
        assertEquals("unavailable", detail.objectValue("pullRequest").objectValue("readiness").string("type"))
        assertEquals("0123456789abcdef", detail.string("headCommit"))
        assertEquals(listOf("build-second", "build-first"), detail.array("builds").strings("key"))
        assertEquals("stale", detail.objectValue("freshness").string("type"))
        assertEquals("2026-08-17T09:00:00Z", detail.objectValue("freshness").string("snapshotAt"))
        assertEquals(4_200_000L, detail.objectValue("freshness").long("ageMilliseconds"))
        assertEquals("2026-08-17T09:30:00Z", detail.objectValue("freshness").string("staleSince"))
        assertFalse(foundText.contains(RAW_ACTIVITY_MARKDOWN))

        val missingResponse = client.get("/api/v1/pull-requests/pr_missing-02")
        val missingText = missingResponse.bodyAsText()
        val missing = assertEnvelope(missingResponse, missingText, "pullRequestNotFound").result()
        assertEquals("pr_missing-02", missing.string("pullRequestId"))
        assertFalse(missingText.contains(RAW_ACTIVITY_MARKDOWN))

        val unconfiguredResponse = client.get("/api/v1/pull-requests/pr_any-03")
        val unconfiguredText = unconfiguredResponse.bodyAsText()
        assertEquals(
            "bitbucket-helper workspace configure",
            assertEnvelope(unconfiguredResponse, unconfiguredText, "workspaceNotConfigured")
                .result().string("setupCommand"),
        )
        assertFalse(unconfiguredText.contains(RAW_ACTIVITY_MARKDOWN))
        assertEquals(
            listOf("pr_found-01", "pr_missing-02", "pr_any-03"),
            dependencies.pullRequestQueries.map { it.pullRequestId.value },
        )
    }

    @Test
    fun `inbox maps available and unconfigured with explicit nulls and stored order`() = testApplication {
        val dependencies = FakeApiV1Dependencies(
            inboxResults = ArrayDeque(
                listOf(
                    GetInboxResult.Available(InboxProjection(actionItems())),
                    GetInboxResult.WorkspaceNotConfigured,
                ),
            ),
        )
        application { installReadApi(dependencies) }

        val availableResponse = client.get("/api/v1/inbox")
        val availableText = availableResponse.bodyAsText()
        val inbox = assertEnvelope(availableResponse, availableText, "available").result().objectValue("inbox")
        assertEquals(listOf("ai_second", "ai_first"), inbox.array("items").strings("actionItemId"))
        assertEquals(JsonNull, inbox.array("items")[0].jsonObject["acknowledgedAt"])
        assertEquals("2026-08-17T10:12:00Z", inbox.array("items")[1].jsonObject.string("acknowledgedAt"))
        assertFalse(availableText.contains(RAW_ACTIVITY_MARKDOWN))

        val unconfiguredResponse = client.get("/api/v1/inbox")
        val unconfiguredText = unconfiguredResponse.bodyAsText()
        assertEnvelope(unconfiguredResponse, unconfiguredText, "workspaceNotConfigured")
        assertFalse(unconfiguredText.contains(RAW_ACTIVITY_MARKDOWN))
        assertEquals(2, dependencies.inboxCalls)
    }

    @Test
    fun `synchronization maps all freshness and problem variants with explicit nulls and stored order`() = testApplication {
        val dependencies = FakeApiV1Dependencies(
            synchronizationResults = ArrayDeque(
                listOf(
                    GetSynchronizationStatusResult.Available(synchronizationVariants()),
                    GetSynchronizationStatusResult.WorkspaceNotConfigured,
                ),
            ),
        )
        application { installReadApi(dependencies) }

        val availableResponse = client.get("/api/v1/synchronization")
        val availableText = availableResponse.bodyAsText()
        val repositories = assertEnvelope(availableResponse, availableText, "available").result()
            .array("repositories")
        assertEquals(listOf("repo_gamma", "repo_alpha", "repo_beta"), repositories.strings("repositoryId"))

        val never = repositories[0].jsonObject
        assertEquals(JsonNull, never["lastAttemptAt"])
        assertEquals(JsonNull, never["lastAttemptOutcome"])
        assertEquals(JsonNull, never["lastSuccessAt"])
        assertEquals("neverSynchronized", never.objectValue("freshness").string("type"))
        assertEquals("none", never.objectValue("problem").string("type"))

        val fresh = repositories[1].jsonObject
        assertEquals("fresh", fresh.objectValue("freshness").string("type"))
        assertEquals("2026-08-17T10:00:00Z", fresh.objectValue("freshness").string("snapshotAt"))
        assertEquals(300_000L, fresh.objectValue("freshness").long("ageMilliseconds"))

        val stale = repositories[2].jsonObject
        assertEquals("stale", stale.objectValue("freshness").string("type"))
        assertEquals("2026-08-17T08:00:00Z", stale.objectValue("freshness").string("snapshotAt"))
        assertEquals(7_200_000L, stale.objectValue("freshness").long("ageMilliseconds"))
        assertEquals("2026-08-17T09:00:00Z", stale.objectValue("freshness").string("staleSince"))
        val partialFailure = stale.objectValue("problem").objectValue("partialFailure")
        assertEquals(3, partialFailure.int("attemptedCount"))
        assertEquals(1, partialFailure.int("succeededCount"))
        assertEquals(2, partialFailure.int("failedCount"))
        assertEquals(listOf("network", "rateLimited"), partialFailure.array("failures").strings("category"))
        assertEquals(JsonNull, partialFailure.array("failures")[0].jsonObject["retryAt"])
        assertEquals("2026-08-17T11:00:00Z", partialFailure.array("failures")[1].jsonObject.string("retryAt"))
        assertFalse(availableText.contains(RAW_ACTIVITY_MARKDOWN))

        val unconfiguredResponse = client.get("/api/v1/synchronization")
        val unconfiguredText = unconfiguredResponse.bodyAsText()
        assertEnvelope(unconfiguredResponse, unconfiguredText, "workspaceNotConfigured")
        assertFalse(unconfiguredText.contains(RAW_ACTIVITY_MARKDOWN))
        assertEquals(2, dependencies.synchronizationCalls)
    }

    @Test
    fun `application enums map exhaustively to exact generated wire literals`() = testApplication {
        val dependencies = FakeApiV1Dependencies(
            pullRequestResults = ArrayDeque(
                listOf(GetPullRequestResult.Found(enumPullRequestDetail())),
            ),
            inboxResults = ArrayDeque(
                listOf(GetInboxResult.Available(InboxProjection(enumActionItems()))),
            ),
            synchronizationResults = ArrayDeque(
                listOf(GetSynchronizationStatusResult.Available(enumSynchronizationVariants())),
            ),
        )
        application { installReadApi(dependencies) }

        val inboxResponse = client.get("/api/v1/inbox")
        val inboxText = inboxResponse.bodyAsText()
        val actionItems = assertEnvelope(inboxResponse, inboxText, "available").result()
            .objectValue("inbox").array("items")
        assertEquals(
            listOf("open", "acknowledged", "closed"),
            actionItems.strings("state"),
        )

        val pullRequestResponse = client.get("/api/v1/pull-requests/pr_enum-matrix")
        val pullRequestText = pullRequestResponse.bodyAsText()
        val builds = assertEnvelope(pullRequestResponse, pullRequestText, "pullRequestFound").result()
            .objectValue("pullRequest").array("builds")
        assertEquals(
            listOf("noBuilds", "inProgress", "successful", "failed", "unknown"),
            builds.strings("state"),
        )

        val synchronizationResponse = client.get("/api/v1/synchronization")
        val synchronizationText = synchronizationResponse.bodyAsText()
        val repositories = assertEnvelope(synchronizationResponse, synchronizationText, "available").result()
            .array("repositories")
        assertEquals(
            listOf("idle", "queued", "running"),
            repositories.strings("activity"),
        )
        assertEquals(
            listOf("succeeded", "partialFailure", "failed"),
            repositories.strings("lastAttemptOutcome"),
        )
        assertEquals(
            listOf(
                "authentication",
                "authorization",
                "rateLimited",
                "timeout",
                "network",
                "upstream",
                "malformedUpstream",
            ),
            repositories[2].jsonObject.objectValue("problem").objectValue("partialFailure")
                .array("failures").strings("category"),
        )

        assertEquals(1, dependencies.inboxCalls)
        assertEquals(listOf("pr_enum-matrix"), dependencies.pullRequestQueries.map { it.pullRequestId.value })
        assertEquals(1, dependencies.synchronizationCalls)
    }

    @Test
    fun `malformed identifiers are safe 400 request errors and invoke no use case`() = testApplication {
        val dependencies = FakeApiV1Dependencies()
        application { installReadApi(dependencies) }

        val invalidRevision = client.get(
            "/api/v1/dashboard?afterRevision=sentinel-secret-invalid-revision",
        ) {
            header(HttpHeaders.Authorization, "Bearer sentinel-secret-authorization")
        }
        val invalidRevisionText = invalidRevision.bodyAsText()
        val revisionBody = assertError(invalidRevision, invalidRevisionText, "afterRevision")
        assertEquals("INVALID_IDENTIFIER", revisionBody.objectValue("error").array("violations")[0]
            .jsonObject.string("code"))
        assertContainsNone(
            invalidRevisionText,
            "sentinel-secret-invalid-revision",
            "sentinel-secret-authorization",
        )

        val invalidPullRequest = client.get("/api/v1/pull-requests/sentinel-secret-invalid-id")
        val invalidPullRequestText = invalidPullRequest.bodyAsText()
        assertError(invalidPullRequest, invalidPullRequestText, "pullRequestId")
        assertContainsNone(invalidPullRequestText, "sentinel-secret-invalid-id")

        assertEquals(emptyList<GetDashboardSnapshotQuery>(), dependencies.dashboardQueries)
        assertEquals(emptyList<GetPullRequestQuery>(), dependencies.pullRequestQueries)
    }

    @Test
    fun `well formed unknown pull request invokes one query and remains typed under 200`() = testApplication {
        val dependencies = FakeApiV1Dependencies(
            pullRequestResults = ArrayDeque(
                listOf(GetPullRequestResult.PullRequestNotFound(PullRequestId("pr_unknown-safe"))),
            ),
        )
        application { installReadApi(dependencies) }

        val response = client.get("/api/v1/pull-requests/pr_unknown-safe")
        val responseText = response.bodyAsText()
        val result = assertEnvelope(response, responseText, "pullRequestNotFound").result()

        assertEquals("pr_unknown-safe", result.string("pullRequestId"))
        assertEquals(listOf("pr_unknown-safe"), dependencies.pullRequestQueries.map { it.pullRequestId.value })
    }

    private fun Application.installReadApi(dependencies: FakeApiV1Dependencies) {
        installApiV1(TransportKind.UNIX) {
            installReadRoutes(dependencies.toReadDependencies())
        }
    }

    private fun assertEnvelope(response: HttpResponse, responseText: String, type: String): JsonObject {
        val body = json.parseToJsonElement(responseText).jsonObject
        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals(JsonPrimitive("1"), body["apiVersion"])
        assertTrue(requestIdPattern.matches(body.string("requestId")))
        assertEquals(type, body.result().string("type"))
        assertResponsePolicy(response)
        return body
    }

    private fun assertError(response: HttpResponse, responseText: String, field: String): JsonObject {
        val body = json.parseToJsonElement(responseText).jsonObject
        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertEquals(JsonPrimitive("1"), body["apiVersion"])
        assertTrue(requestIdPattern.matches(body.string("requestId")))
        assertEquals("INVALID_REQUEST", body.objectValue("error").string("code"))
        assertEquals(field, body.objectValue("error").array("violations")[0].jsonObject.string("field"))
        assertResponsePolicy(response)
        return body
    }

    private fun assertResponsePolicy(response: HttpResponse) {
        assertEquals("no-store", response.headers[HttpHeaders.CacheControl])
        assertEquals(ContentType.Application.Json, response.contentType()?.withoutParameters())
    }

    private fun assertContainsNone(text: String, vararg forbidden: String) {
        forbidden.forEach { assertFalse(text.contains(it), "response exposed $it") }
    }

    private fun JsonObject.result(): JsonObject = objectValue("result")
    private fun JsonObject.objectValue(name: String): JsonObject = getValue(name).jsonObject
    private fun JsonObject.array(name: String): JsonArray = getValue(name).jsonArray
    private fun JsonObject.string(name: String): String = getValue(name).jsonPrimitive.content
    private fun JsonObject.long(name: String): Long = getValue(name).jsonPrimitive.content.toLong()
    private fun JsonObject.int(name: String): Int = getValue(name).jsonPrimitive.content.toInt()
    private fun JsonArray.strings(name: String): List<String> = map { it.jsonObject.string(name) }

    private class FakeApiV1Dependencies(
        private val dashboardResults: ArrayDeque<DashboardResult> = ArrayDeque(
            listOf(DashboardResult.WorkspaceNotConfigured),
        ),
        private val pullRequestListResults: ArrayDeque<ListPullRequestsResult> = ArrayDeque(
            listOf(ListPullRequestsResult.WorkspaceNotConfigured),
        ),
        private val pullRequestResults: ArrayDeque<GetPullRequestResult> = ArrayDeque(
            listOf(GetPullRequestResult.WorkspaceNotConfigured),
        ),
        private val inboxResults: ArrayDeque<GetInboxResult> = ArrayDeque(
            listOf(GetInboxResult.WorkspaceNotConfigured),
        ),
        private val synchronizationResults: ArrayDeque<GetSynchronizationStatusResult> = ArrayDeque(
            listOf(GetSynchronizationStatusResult.WorkspaceNotConfigured),
        ),
    ) {
        val dashboardQueries = mutableListOf<GetDashboardSnapshotQuery>()
        var pullRequestListCalls: Int = 0
        val pullRequestQueries = mutableListOf<GetPullRequestQuery>()
        var inboxCalls: Int = 0
        var synchronizationCalls: Int = 0

        fun toReadDependencies(): ReadApiV1Dependencies = ReadApiV1Dependencies(
            getDashboardSnapshot = GetDashboardSnapshot { query ->
                dashboardQueries += query
                dashboardResults.removeFirst()
            },
            listPullRequests = ListPullRequests {
                pullRequestListCalls += 1
                pullRequestListResults.removeFirst()
            },
            getPullRequest = GetPullRequest { query ->
                pullRequestQueries += query
                pullRequestResults.removeFirst()
            },
            getInbox = GetInbox {
                inboxCalls += 1
                inboxResults.removeFirst()
            },
            getSynchronizationStatus = GetSynchronizationStatus {
                synchronizationCalls += 1
                synchronizationResults.removeFirst()
            },
        )
    }
}

private const val RAW_ACTIVITY_MARKDOWN = "sentinel-raw-activity-markdown"

private fun dashboardSnapshot(): DashboardSnapshot = DashboardSnapshot(
    revision = DashboardRevision("dr_current-02"),
    generatedAt = Instant.parse("2026-08-17T10:20:00Z"),
    workspace = WorkspaceConfigurationProjection(
        workspaceId = WorkspaceId("ws_example"),
        bitbucketApiBaseUrl = URI("https://api.bitbucket.org/2.0"),
        workspaceSlug = "example-workspace",
        workspaceDisplayName = "Example Workspace",
        workspaceWebUrl = URI("https://bitbucket.org/example-workspace"),
        retentionDays = 30,
        repositories = listOf(
            configuredRepository("repo_zeta", "zeta"),
            configuredRepository("repo_alpha", "alpha"),
        ),
    ),
    repositoryGroups = repositoryGroups(),
    inbox = InboxProjection(actionItems()),
    polling = DashboardPolling.Active(1750),
)

private fun configuredRepository(id: String, slug: String): ConfiguredRepositoryProjection =
    ConfiguredRepositoryProjection(
        repositoryId = RepositoryId(id),
        slug = slug,
        displayName = slug.replaceFirstChar(Char::uppercase),
        webUrl = URI("https://bitbucket.org/example-workspace/$slug"),
    )

private fun repositoryGroups(): List<RepositoryGroupProjection> = listOf(
    repositoryGroup("repo_zeta", "zeta", pullRequestCard("pr_zeta", ReadinessProjection.Available(
        passed = 1,
        total = 2,
        checks = listOf(
            ReadinessCheckProjection("builds", passed = true, safeReason = null),
            ReadinessCheckProjection("approvals", passed = false, safeReason = "Approval required"),
        ),
    ))),
    repositoryGroup("repo_alpha", "alpha", pullRequestCard("pr_alpha", ReadinessProjection.Unavailable(
        safeReason = "Readiness is unavailable",
    ))),
)

private fun repositoryGroup(
    repositoryId: String,
    slug: String,
    pullRequest: PullRequestCardProjection,
): RepositoryGroupProjection = RepositoryGroupProjection(
    repositoryId = RepositoryId(repositoryId),
    slug = slug,
    displayName = slug.replaceFirstChar(Char::uppercase),
    webUrl = URI("https://bitbucket.org/example-workspace/$slug"),
    revision = RepositoryRevision("rrev_${slug}-02"),
    synchronization = SynchronizationProjection(
        repositoryId = RepositoryId(repositoryId),
        activity = SynchronizationActivity.IDLE,
        lastAttemptAt = null,
        lastAttemptOutcome = null,
        lastSuccessAt = null,
        freshness = Freshness.NeverSynchronized,
        problem = SynchronizationProblem.None,
    ),
    readinessSummary = ReadinessSummaryProjection(
        readyPullRequestCount = 0,
        availablePullRequestCount = 1,
        unavailablePullRequestCount = 0,
    ),
    pullRequests = listOf(pullRequest),
)

private fun pullRequestCard(id: String, readiness: ReadinessProjection): PullRequestCardProjection =
    PullRequestCardProjection(
        id = PullRequestId(id),
        repositoryId = RepositoryId(if (id == "pr_zeta") "repo_zeta" else "repo_alpha"),
        upstreamNumber = if (id == "pr_zeta") 21 else 7,
        title = "Safe pull request $id",
        author = ActorProjection("actor_ada", "Ada Lovelace"),
        draft = false,
        createdAt = Instant.parse("2026-08-17T08:00:00Z"),
        updatedAt = Instant.parse("2026-08-17T10:00:00Z"),
        webUrl = URI("https://bitbucket.org/example-workspace/repository/pull-requests/21"),
        readiness = readiness,
        buildState = BuildState.IN_PROGRESS,
        actionableItemCount = 1,
        acknowledgedItemCount = 1,
        actionItems = actionItems(),
    )

private fun pullRequestDetail(): PullRequestDetailProjection = PullRequestDetailProjection(
    pullRequest = pullRequestCard(
        id = "pr_detail",
        readiness = ReadinessProjection.Unavailable("Readiness source unavailable"),
    ),
    headCommit = "0123456789abcdef",
    builds = listOf(
        BuildProjection("build-second", BuildState.FAILED),
        BuildProjection("build-first", BuildState.SUCCESSFUL),
    ),
    freshness = Freshness.Stale(
        snapshotAt = Instant.parse("2026-08-17T09:00:00Z"),
        age = Duration.ofMinutes(70),
        staleSince = Instant.parse("2026-08-17T09:30:00Z"),
    ),
)

private fun actionItems(): List<ActionItemProjection> = listOf(
    ActionItemProjection(
        id = ActionItemId("ai_second"),
        pullRequestId = PullRequestId("pr_zeta"),
        repositoryId = RepositoryId("repo_zeta"),
        repositoryDisplayName = "Zeta",
        pullRequestNumber = 21,
        pullRequestTitle = "Safe pull request pr_zeta",
        activityVersion = ActivityVersion("av_second"),
        kind = "comment",
        actor = ActorProjection("actor_grace", "Grace Hopper"),
        activityAt = Instant.parse("2026-08-17T10:10:00Z"),
        state = ActionItemState.OPEN,
        acknowledgedAt = null,
        webUrl = URI("https://bitbucket.org/example-workspace/zeta/pull-requests/21#comment-2"),
    ),
    ActionItemProjection(
        id = ActionItemId("ai_first"),
        pullRequestId = PullRequestId("pr_zeta"),
        repositoryId = RepositoryId("repo_zeta"),
        repositoryDisplayName = "Zeta",
        pullRequestNumber = 21,
        pullRequestTitle = "Safe pull request pr_zeta",
        activityVersion = ActivityVersion("av_first"),
        kind = "approval",
        actor = ActorProjection("actor_ada", "Ada Lovelace"),
        activityAt = Instant.parse("2026-08-17T10:11:00Z"),
        state = ActionItemState.ACKNOWLEDGED,
        acknowledgedAt = Instant.parse("2026-08-17T10:12:00Z"),
        webUrl = URI("https://bitbucket.org/example-workspace/zeta/pull-requests/21"),
    ),
)

private fun synchronizationVariants(): List<SynchronizationProjection> = listOf(
    SynchronizationProjection(
        repositoryId = RepositoryId("repo_gamma"),
        activity = SynchronizationActivity.IDLE,
        lastAttemptAt = null,
        lastAttemptOutcome = null,
        lastSuccessAt = null,
        freshness = Freshness.NeverSynchronized,
        problem = SynchronizationProblem.None,
    ),
    SynchronizationProjection(
        repositoryId = RepositoryId("repo_alpha"),
        activity = SynchronizationActivity.RUNNING,
        lastAttemptAt = Instant.parse("2026-08-17T10:00:00Z"),
        lastAttemptOutcome = SynchronizationAttemptOutcome.SUCCEEDED,
        lastSuccessAt = Instant.parse("2026-08-17T10:00:00Z"),
        freshness = Freshness.Fresh(
            snapshotAt = Instant.parse("2026-08-17T10:00:00Z"),
            age = Duration.ofMinutes(5),
        ),
        problem = SynchronizationProblem.None,
    ),
    SynchronizationProjection(
        repositoryId = RepositoryId("repo_beta"),
        activity = SynchronizationActivity.QUEUED,
        lastAttemptAt = Instant.parse("2026-08-17T08:00:00Z"),
        lastAttemptOutcome = SynchronizationAttemptOutcome.PARTIAL_FAILURE,
        lastSuccessAt = Instant.parse("2026-08-17T07:30:00Z"),
        freshness = Freshness.Stale(
            snapshotAt = Instant.parse("2026-08-17T08:00:00Z"),
            age = Duration.ofHours(2),
            staleSince = Instant.parse("2026-08-17T09:00:00Z"),
        ),
        problem = SynchronizationProblem.Present(
            PartialFailureMetadata(
                attemptedCount = 3,
                succeededCount = 1,
                failures = listOf(
                    SynchronizationFailure(SynchronizationFailureCategory.NETWORK, true, null),
                    SynchronizationFailure(
                        SynchronizationFailureCategory.RATE_LIMITED,
                        true,
                        Instant.parse("2026-08-17T11:00:00Z"),
                    ),
                ),
            ),
        ),
    ),
)

private fun enumPullRequestDetail(): PullRequestDetailProjection = PullRequestDetailProjection(
    pullRequest = pullRequestCard(
        id = "pr_enum-matrix",
        readiness = ReadinessProjection.Unavailable("Readiness source unavailable"),
    ),
    headCommit = "enum-matrix-head",
    builds = listOf(
        BuildProjection("build-no-builds", BuildState.NO_BUILDS),
        BuildProjection("build-in-progress", BuildState.IN_PROGRESS),
        BuildProjection("build-successful", BuildState.SUCCESSFUL),
        BuildProjection("build-failed", BuildState.FAILED),
        BuildProjection("build-unknown", BuildState.UNKNOWN),
    ),
    freshness = Freshness.NeverSynchronized,
)

private fun enumActionItems(): List<ActionItemProjection> = listOf(
    enumActionItem("ai_enum-open", "av_enum-open", ActionItemState.OPEN),
    enumActionItem("ai_enum-acknowledged", "av_enum-acknowledged", ActionItemState.ACKNOWLEDGED),
    enumActionItem("ai_enum-closed", "av_enum-closed", ActionItemState.CLOSED),
)

private fun enumActionItem(
    actionItemId: String,
    activityVersion: String,
    state: ActionItemState,
): ActionItemProjection = ActionItemProjection(
    id = ActionItemId(actionItemId),
    pullRequestId = PullRequestId("pr_enum-matrix"),
    repositoryId = RepositoryId("repo_enum-matrix"),
    repositoryDisplayName = "Enum Matrix",
    pullRequestNumber = 44,
    pullRequestTitle = "Enum matrix pull request",
    activityVersion = ActivityVersion(activityVersion),
    kind = "enum-matrix",
    actor = ActorProjection("actor_enum-matrix", "Enum Matrix Actor"),
    activityAt = Instant.parse("2026-08-17T12:00:00Z"),
    state = state,
    acknowledgedAt = null,
    webUrl = URI("https://bitbucket.org/example-workspace/enum-matrix/pull-requests/44"),
)

private fun enumSynchronizationVariants(): List<SynchronizationProjection> = listOf(
    enumSynchronization(
        repositoryId = "repo_enum-idle",
        activity = SynchronizationActivity.IDLE,
        outcome = SynchronizationAttemptOutcome.SUCCEEDED,
        problem = SynchronizationProblem.None,
    ),
    enumSynchronization(
        repositoryId = "repo_enum-queued",
        activity = SynchronizationActivity.QUEUED,
        outcome = SynchronizationAttemptOutcome.PARTIAL_FAILURE,
        problem = SynchronizationProblem.None,
    ),
    enumSynchronization(
        repositoryId = "repo_enum-running",
        activity = SynchronizationActivity.RUNNING,
        outcome = SynchronizationAttemptOutcome.FAILED,
        problem = SynchronizationProblem.Present(
            PartialFailureMetadata(
                attemptedCount = 7,
                succeededCount = 0,
                failures = listOf(
                    SynchronizationFailure(SynchronizationFailureCategory.AUTHENTICATION, false, null),
                    SynchronizationFailure(SynchronizationFailureCategory.AUTHORIZATION, false, null),
                    SynchronizationFailure(SynchronizationFailureCategory.RATE_LIMITED, true, null),
                    SynchronizationFailure(SynchronizationFailureCategory.TIMEOUT, true, null),
                    SynchronizationFailure(SynchronizationFailureCategory.NETWORK, true, null),
                    SynchronizationFailure(SynchronizationFailureCategory.UPSTREAM, true, null),
                    SynchronizationFailure(SynchronizationFailureCategory.MALFORMED_UPSTREAM, false, null),
                ),
            ),
        ),
    ),
)

private fun enumSynchronization(
    repositoryId: String,
    activity: SynchronizationActivity,
    outcome: SynchronizationAttemptOutcome,
    problem: SynchronizationProblem,
): SynchronizationProjection = SynchronizationProjection(
    repositoryId = RepositoryId(repositoryId),
    activity = activity,
    lastAttemptAt = Instant.parse("2026-08-17T12:00:00Z"),
    lastAttemptOutcome = outcome,
    lastSuccessAt = null,
    freshness = Freshness.NeverSynchronized,
    problem = problem,
)

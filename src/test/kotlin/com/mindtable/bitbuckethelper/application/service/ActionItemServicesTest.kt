package com.mindtable.bitbuckethelper.application.service

import com.mindtable.bitbuckethelper.application.model.*
import com.mindtable.bitbuckethelper.application.port.outbound.*
import com.mindtable.bitbuckethelper.domain.shared.*
import java.net.URI
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.concurrent.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class ActionItemServicesTest {
    private val now = Instant.parse("2026-08-15T12:00:00Z")

    @Test
    fun `missing and locally stale versions are typed without a gateway call`() = runTest {
        val state = actionState()
        val gateway = LiveGateway()
        val services = services(state, gateway)

        val missing = services.getLiveContent(GetLiveActivityContentCommand(missingAction, versionA))
        val stale = services.getLiveContent(GetLiveActivityContentCommand(actionItemA, versionOld))

        assertEquals(LiveActivityContentResult.ActionItemNotFound(missingAction, versionA), missing)
        val staleResult = assertInstanceOf(LiveActivityContentResult.StaleActivityVersion::class.java, stale)
        assertEquals(versionOld, staleResult.requestedVersion)
        assertEquals(versionA, staleResult.current.activityVersion)
        assertEquals("Repository", staleResult.current.repositoryDisplayName)
        assertEquals(42, staleResult.current.pullRequestNumber)
        assertEquals("Pull request", staleResult.current.pullRequestTitle)
        assertEquals(ActorProjection("reviewer", "Reviewer"), staleResult.current.actor)
        assertEquals(0, gateway.liveCalls)
    }

    @Test
    fun `exact live success returns markdown only in the result and never persists it`() = runTest {
        val rawMarkdown = "**secret upstream markdown**"
        val state = actionState()
        val before = state.actionItems.single()
        val gateway = LiveGateway().apply {
            liveResult = GatewayResult.Success(GatewayLiveActivityContent(versionA, rawMarkdown, now.plusSeconds(1)))
        }

        val result = services(state, gateway).getLiveContent(GetLiveActivityContentCommand(actionItemA, versionA))

        assertEquals(
            LiveActivityContentResult.ContentAvailable(actionItemA, versionA, rawMarkdown, now.plusSeconds(1)),
            result,
        )
        assertEquals(before, state.actionItems.single())
        assertEquals(0, state.saveCalls)
        assertEquals(0, state.acknowledgeCalls)
        assertFalse(state.toString().contains(rawMarkdown))
        assertEquals(1, gateway.liveCalls)
    }

    @Test
    fun `thread live content reads the latest external comment id from its durable https link`() = runTest {
        val rawMarkdown = "**latest external reply**"
        val state = actionState().apply {
            actionItems[0] = actionItems[0].copy(
                sourceKind = "THREAD",
                upstreamSourceId = "501",
                webUrl = URI("https://bitbucket.org/team/repository/pull-requests/42#comment-502"),
            )
        }
        val gateway = LiveGateway().apply {
            liveResult = GatewayResult.Success(GatewayLiveActivityContent(versionA, rawMarkdown, now.plusSeconds(1)))
        }

        val result = services(state, gateway).getLiveContent(GetLiveActivityContentCommand(actionItemA, versionA))

        assertEquals(
            LiveActivityContentResult.ContentAvailable(actionItemA, versionA, rawMarkdown, now.plusSeconds(1)),
            result,
        )
        assertEquals(listOf("502"), gateway.liveSourceIds)
        assertEquals("501", state.actionItems.single().upstreamSourceId)
        assertFalse(state.toString().contains(rawMarkdown))
    }

    @Test
    fun `thread live content fails safely when the latest comment link has no bounded positive id`() = runTest {
        val unsafeFragments = listOf(
            "https://bitbucket.org/team/repository/pull-requests/42",
            "https://bitbucket.org/team/repository/pull-requests/42#comment-0",
            "https://bitbucket.org/team/repository/pull-requests/42#comment-0502",
            "https://bitbucket.org/team/repository/pull-requests/42#comment-%35%30%32",
            "https://bitbucket.org/team/repository/pull-requests/42#comment-not-a-number",
            "https://bitbucket.org/team/repository/pull-requests/42#comment-999999999999999999999999999999999999",
            "http://bitbucket.org/team/repository/pull-requests/42#comment-502",
            "https://user:secret@bitbucket.org/team/repository/pull-requests/42#comment-502",
            "https://bitbucket.org/team/repository/pull-requests/42?private=true#comment-502",
        )

        unsafeFragments.forEach { link ->
            val state = actionState().apply {
                actionItems[0] = actionItems[0].copy(
                    sourceKind = "THREAD",
                    upstreamSourceId = "501",
                    webUrl = URI(link),
                )
            }
            val gateway = LiveGateway()

            val result = services(state, gateway)
                .getLiveContent(GetLiveActivityContentCommand(actionItemA, versionA))

            assertEquals(
                LiveActivityContentResult.ContentUnavailable(
                    actionItemA,
                    versionA,
                    LiveContentUnavailableReason.MALFORMED_UPSTREAM,
                    retryable = false,
                    retryAt = null,
                ),
                result,
                link,
            )
            assertEquals(0, gateway.liveCalls, link)
            assertFalse(result.toString().contains(link), link)
        }
    }

    @Test
    fun `gateway version mismatch reports newer activity and discards the returned body`() = runTest {
        val rawMarkdown = "newer body must be discarded"
        val state = actionState()
        val gateway = LiveGateway().apply {
            liveResult = GatewayResult.Success(GatewayLiveActivityContent(versionB, rawMarkdown, now.plusSeconds(1)))
        }

        val result = services(state, gateway).getLiveContent(GetLiveActivityContentCommand(actionItemA, versionA))

        assertEquals(
            LiveActivityContentResult.NewerActivityObserved(actionItemA, versionA, versionB, repositoryA),
            result,
        )
        assertFalse(result.toString().contains(rawMarkdown))
        assertFalse(state.toString().contains(rawMarkdown))
        assertEquals(0, state.saveCalls)
    }

    @Test
    fun `durable version is revalidated after the gateway before exact content can escape`() = runTest {
        val rawMarkdown = "stale body must be discarded"
        val state = actionState()
        val gateway = LiveGateway().apply {
            beforeLiveReturn = {
                state.actionItems[0] = state.actionItems[0].copy(
                    activityVersion = versionB,
                    actorDisplayName = "Concurrent Reviewer",
                )
            }
            liveResult = GatewayResult.Success(GatewayLiveActivityContent(versionA, rawMarkdown, now.plusSeconds(1)))
        }

        val result = services(state, gateway).getLiveContent(GetLiveActivityContentCommand(actionItemA, versionA))

        val stale = assertInstanceOf(LiveActivityContentResult.StaleActivityVersion::class.java, result)
        assertEquals(versionB, stale.current.activityVersion)
        assertEquals("Concurrent Reviewer", stale.current.actor.displayName)
        assertFalse(result.toString().contains(rawMarkdown))
    }

    @Test
    fun `gateway not found maps deleted and every failure category maps safe retry metadata`() = runTest {
        val state = actionState()
        val gateway = LiveGateway().apply { liveResult = GatewayResult.NotFound }
        val service = services(state, gateway)

        assertEquals(
            LiveActivityContentResult.ContentUnavailable(
                actionItemA,
                versionA,
                LiveContentUnavailableReason.DELETED,
                retryable = false,
                retryAt = null,
            ),
            service.getLiveContent(GetLiveActivityContentCommand(actionItemA, versionA)),
        )

        val expectedReasons = mapOf(
            GatewayFailureCategory.AUTHENTICATION to LiveContentUnavailableReason.AUTHENTICATION,
            GatewayFailureCategory.AUTHORIZATION to LiveContentUnavailableReason.AUTHORIZATION,
            GatewayFailureCategory.RATE_LIMITED to LiveContentUnavailableReason.RATE_LIMITED,
            GatewayFailureCategory.TIMEOUT to LiveContentUnavailableReason.TIMEOUT,
            GatewayFailureCategory.NETWORK to LiveContentUnavailableReason.NETWORK,
            GatewayFailureCategory.UPSTREAM to LiveContentUnavailableReason.UPSTREAM,
            GatewayFailureCategory.MALFORMED_RESPONSE to LiveContentUnavailableReason.MALFORMED_UPSTREAM,
            GatewayFailureCategory.UNSAFE_PAGINATION to LiveContentUnavailableReason.MALFORMED_UPSTREAM,
        )
        expectedReasons.forEach { (category, expectedReason) ->
            val retryAt = now.plusSeconds(category.ordinal.toLong() + 1)
            val retryable = category.ordinal % 2 == 0
            gateway.liveResult = GatewayResult.Failure(GatewayFailure(category, retryable, retryAt))
            val result = assertInstanceOf(
                LiveActivityContentResult.ContentUnavailable::class.java,
                service.getLiveContent(GetLiveActivityContentCommand(actionItemA, versionA)),
            )
            assertEquals(expectedReason, result.reason, category.name)
            assertEquals(retryable, result.retryable, category.name)
            assertEquals(retryAt, result.retryAt, category.name)
        }
    }

    @Test
    fun `removed inactive and unresolvable durable contexts never call the gateway or leak content`() = runTest {
        val rawMarkdown = "context body"
        val variants = listOf<(ActionState) -> Unit>(
            { it.configuration = requireNotNull(it.configuration).copy(repositories = it.configuration!!.repositories.map { repository -> repository.copy(removedAt = now) }) },
            { it.pullRequests[0] = it.pullRequests[0].copy(active = false, inactiveAt = now) },
            { it.pullRequests.clear() },
            { it.actionItems[0] = it.actionItems[0].copy(repositoryId = repositoryOther) },
        )

        variants.forEachIndexed { index, mutate ->
            val state = actionState().also(mutate)
            val gateway = LiveGateway().apply {
                liveResult = GatewayResult.Success(GatewayLiveActivityContent(versionA, rawMarkdown, now))
            }
            val result = services(state, gateway).getLiveContent(GetLiveActivityContentCommand(actionItemA, versionA))
            assertEquals(LiveActivityContentResult.ActionItemNotFound(actionItemA, versionA), result, "variant $index")
            assertEquals(0, gateway.liveCalls, "variant $index")
            assertFalse(result.toString().contains(rawMarkdown), "variant $index")
        }
    }

    @Test
    fun `gateway cancellation propagates unchanged`() = runTest {
        val cancellation = CancellationException("stop live request")
        val gateway = LiveGateway().apply { liveFailure = cancellation }

        val observed: Throwable? = try {
            services(actionState(), gateway).getLiveContent(GetLiveActivityContentCommand(actionItemA, versionA))
            null
        } catch (failure: Throwable) {
            failure
        }

        assertSame(cancellation, observed)
    }

    @Test
    fun `acknowledgment maps every atomic store outcome with one injected timestamp and complete metadata`() = runTest {
        val updatedState = actionState()
        val updatedClock = CountingClock(now)
        val updated = services(updatedState, LiveGateway(), updatedClock)
            .acknowledge(AcknowledgeActionItemCommand(actionItemA, versionA))
        assertEquals(AcknowledgeActionItemResult.Acknowledged(actionItemA, versionA, now), updated)
        assertEquals(1, updatedClock.calls)
        assertEquals(now, updatedState.actionItems.single().acknowledgedAt)
        assertEquals(1, updatedState.acknowledgeCalls)

        val alreadyState = actionState().apply {
            actionItems[0] = actionItems[0].copy(
                state = ActionItemState.ACKNOWLEDGED,
                acknowledgedVersion = versionA,
                acknowledgedAt = now.minusSeconds(10),
            )
        }
        assertEquals(
            AcknowledgeActionItemResult.AlreadyAcknowledged(actionItemA, versionA),
            services(alreadyState, LiveGateway()).acknowledge(AcknowledgeActionItemCommand(actionItemA, versionA)),
        )

        val staleState = actionState().apply {
            actionItems[0] = actionItems[0].copy(activityVersion = versionB, actorDisplayName = "Newest Reviewer")
        }
        val stale = assertInstanceOf(
            AcknowledgeActionItemResult.StaleActivityVersion::class.java,
            services(staleState, LiveGateway()).acknowledge(AcknowledgeActionItemCommand(actionItemA, versionA)),
        )
        assertEquals(versionB, stale.current.activityVersion)
        assertEquals("Newest Reviewer", stale.current.actor.displayName)
        assertEquals("Repository", stale.current.repositoryDisplayName)
        assertEquals("Pull request", stale.current.pullRequestTitle)

        val closedState = actionState().apply { actionItems[0] = actionItems[0].copy(state = ActionItemState.CLOSED) }
        assertEquals(
            AcknowledgeActionItemResult.AcknowledgmentRejected(actionItemA, versionA),
            services(closedState, LiveGateway()).acknowledge(AcknowledgeActionItemCommand(actionItemA, versionA)),
        )
        assertEquals(
            AcknowledgeActionItemResult.ActionItemNotFound(missingAction, versionA),
            services(actionState(), LiveGateway()).acknowledge(AcknowledgeActionItemCommand(missingAction, versionA)),
        )
    }

    @Test
    fun `acknowledgment clamps a regressed clock to the current activity time`() = runTest {
        val activityAt = now.plusSeconds(5)
        val state = actionState().apply {
            actionItems[0] = actionItems[0].copy(
                activityAt = activityAt,
                observedAt = activityAt.plusSeconds(1),
            )
        }

        val result = services(state, LiveGateway(), Clock.fixed(now, ZoneOffset.UTC))
            .acknowledge(AcknowledgeActionItemCommand(actionItemA, versionA))

        assertEquals(AcknowledgeActionItemResult.Acknowledged(actionItemA, versionA, activityAt), result)
        assertEquals(activityAt, state.actionItems.single().acknowledgedAt)
    }

    @Test
    fun `stale and rejected acknowledgments do not call the gateway or optimistically mutate state`() = runTest {
        val variants = listOf(
            actionState().apply { actionItems[0] = actionItems[0].copy(activityVersion = versionB) },
            actionState().apply { actionItems[0] = actionItems[0].copy(state = ActionItemState.CLOSED) },
        )
        variants.forEach { state ->
            val gateway = LiveGateway()
            val before = state.actionItems.single()

            services(state, gateway).acknowledge(AcknowledgeActionItemCommand(actionItemA, versionA))

            assertEquals(before, state.actionItems.single())
            assertEquals(0, state.saveCalls)
            assertEquals(0, gateway.liveCalls)
        }
    }

    @Test
    fun `closed item remains rejected after its current version was previously acknowledged`() = runTest {
        val state = actionState().apply {
            actionItems[0] = actionItems[0].copy(
                state = ActionItemState.CLOSED,
                acknowledgedVersion = versionA,
                acknowledgedAt = now.minusSeconds(1),
            )
        }
        val before = state.actionItems.single()

        val result = services(state, LiveGateway())
            .acknowledge(AcknowledgeActionItemCommand(actionItemA, versionA))

        assertEquals(AcknowledgeActionItemResult.AcknowledgmentRejected(actionItemA, versionA), result)
        assertEquals(before, state.actionItems.single())
        assertEquals(0, state.acknowledgeCalls)
    }

    private fun services(
        state: ActionState,
        gateway: LiveGateway,
        clock: Clock = Clock.fixed(now, ZoneOffset.UTC),
    ) = ActionItemServices(ActionTransactions(state), gateway, clock)

    private fun actionState() = ActionState(
        configuration = StoredInstallationConfiguration(
            WorkspaceId("ws_team"),
            URI("https://api.bitbucket.org/2.0"),
            "team",
            "Team",
            URI("https://bitbucket.org/team"),
            "current-user",
            "Current User",
            now.minusSeconds(3_600),
            30,
            listOf(
                StoredConfiguredRepository(
                    repositoryA,
                    WorkspaceId("ws_team"),
                    "repository",
                    "Repository",
                    URI("https://bitbucket.org/team/repository"),
                    null,
                ),
            ),
        ),
        pullRequests = mutableListOf(
            StoredPullRequestSnapshot(
                pullRequestA,
                repositoryA,
                42,
                "Pull request",
                "author",
                "Author",
                false,
                "head",
                URI("https://bitbucket.org/team/repository/pull-requests/42"),
                now.minusSeconds(3_600),
                now.minusSeconds(60),
                now.minusSeconds(30),
                true,
                null,
                StoredReadiness.Available(1, 1, listOf(StoredReadinessCheck("CHECK", true, null))),
                listOf(StoredBuildObservation("build", BuildState.SUCCESSFUL, now.minusSeconds(30))),
                true,
            ),
        ),
        actionItems = mutableListOf(
            StoredActionItemSnapshot(
                actionItemA,
                pullRequestA,
                repositoryA,
                "COMMENT",
                "comment-7",
                "reviewer",
                "Reviewer",
                now.minusSeconds(20),
                now.minusSeconds(10),
                versionA,
                ActionItemState.OPEN,
                null,
                null,
                URI("https://bitbucket.org/team/repository/pull-requests/42#comment-7"),
            ),
        ),
    )

    private companion object {
        val repositoryA = RepositoryId("repo_alpha")
        val repositoryOther = RepositoryId("repo_other")
        val pullRequestA = PullRequestId("pr_alpha")
        val actionItemA = ActionItemId("ai_alpha")
        val missingAction = ActionItemId("ai_missing")
        val versionOld = ActivityVersion("av_old")
        val versionA = ActivityVersion("av_alpha")
        val versionB = ActivityVersion("av_beta")
    }
}

private data class ActionState(
    var configuration: StoredInstallationConfiguration?,
    val pullRequests: MutableList<StoredPullRequestSnapshot>,
    val actionItems: MutableList<StoredActionItemSnapshot>,
    var saveCalls: Int = 0,
    var acknowledgeCalls: Int = 0,
)

private class ActionTransactions(private val state: ActionState) : ApplicationTransactionRunner {
    override suspend fun <T> inTransaction(block: suspend ApplicationTransaction.() -> T): T =
        block(ActionTransaction(state))
}

private class ActionTransaction(private val state: ActionState) : ApplicationTransaction {
    override val configurationStore = object : ConfigurationStore {
        override suspend fun find() = state.configuration
        override suspend fun save(configuration: StoredInstallationConfiguration) { state.configuration = configuration }
    }
    override val pullRequestStore = object : PullRequestStore {
        override suspend fun find(id: PullRequestId) = state.pullRequests.singleOrNull { it.id == id }
        override suspend fun listByRepository(repositoryId: RepositoryId, includeInactive: Boolean) =
            state.pullRequests.filter { it.repositoryId == repositoryId && (includeInactive || it.active) }
        override suspend fun save(snapshot: StoredPullRequestSnapshot) { state.saveCalls++ }
        override suspend fun markMissingInactive(repositoryId: RepositoryId, activePullRequestIds: Set<PullRequestId>, authoritativeAt: Instant) = error("unused")
        override suspend fun markInactive(id: PullRequestId, inactiveAt: Instant) = error("unused")
        override suspend fun listInactiveBefore(cutoff: Instant) = error("unused")
        override suspend fun delete(id: PullRequestId) = error("unused")
    }
    override val actionItemStore = object : ActionItemStore {
        override suspend fun find(id: ActionItemId) = state.actionItems.singleOrNull { it.id == id }
        override suspend fun listByPullRequest(pullRequestId: PullRequestId) = state.actionItems.filter { it.pullRequestId == pullRequestId }
        override suspend fun listActionable() = state.actionItems.filter { it.state == ActionItemState.OPEN && it.acknowledgedVersion != it.activityVersion }
        override suspend fun save(snapshot: StoredActionItemSnapshot) {
            state.saveCalls++
            state.actionItems.removeIf { it.id == snapshot.id }
            state.actionItems += snapshot
        }
        override suspend fun acknowledge(
            id: ActionItemId,
            expectedVersion: ActivityVersion,
            acknowledgedAt: Instant,
        ): StoredAcknowledgmentResult {
            state.acknowledgeCalls++
            val current = state.actionItems.singleOrNull { it.id == id } ?: return StoredAcknowledgmentResult.Missing
            if (current.activityVersion != expectedVersion) return StoredAcknowledgmentResult.VersionMismatch(current)
            if (current.acknowledgedVersion == expectedVersion) return StoredAcknowledgmentResult.AlreadyApplied(current)
            if (current.state != ActionItemState.OPEN) return StoredAcknowledgmentResult.NotActionable(current)
            val updated = current.copy(
                state = ActionItemState.ACKNOWLEDGED,
                acknowledgedVersion = expectedVersion,
                acknowledgedAt = acknowledgedAt,
            )
            state.actionItems.remove(current)
            state.actionItems += updated
            return StoredAcknowledgmentResult.Updated(updated)
        }
        override suspend fun deleteByPullRequest(pullRequestId: PullRequestId) = error("unused")
    }
    override val synchronizationCheckpointStore: SynchronizationCheckpointStore get() = error("unused")
    override val notificationIntentStore: NotificationIntentStore get() = error("unused")
    override val reminderProjectionStore: ReminderProjectionStore get() = error("unused")
}

private class LiveGateway : BitbucketGateway {
    var liveCalls = 0
    val liveSourceIds = mutableListOf<String>()
    var liveResult: GatewayResult<GatewayLiveActivityContent> = GatewayResult.Success(
        GatewayLiveActivityContent(ActivityVersion("av_alpha"), "body", Instant.EPOCH),
    )
    var liveFailure: Throwable? = null
    var beforeLiveReturn: () -> Unit = {}

    override suspend fun getLiveActivityContent(
        repository: GatewayRepositoryAddress,
        upstreamNumber: Long,
        sourceId: String,
    ): GatewayResult<GatewayLiveActivityContent> {
        liveCalls++
        liveSourceIds += sourceId
        liveFailure?.let { throw it }
        beforeLiveReturn()
        return liveResult
    }

    override suspend fun currentUser(apiBaseUrl: URI): GatewayResult<GatewayUserObservation> = error("unused")
    override suspend fun resolveWorkspace(apiBaseUrl: URI, workspaceSlug: String): GatewayResult<GatewayWorkspaceObservation> = error("unused")
    override suspend fun resolveRepository(apiBaseUrl: URI, workspaceSlug: String, repositorySlug: String): GatewayResult<GatewayRepositoryObservation> = error("unused")
    override suspend fun listAuthoredOpenPullRequests(repository: GatewayRepositoryAddress, currentUserStableId: String): GatewayResult<List<GatewayPullRequestSummary>> = error("unused")
    override suspend fun getPullRequest(repository: GatewayRepositoryAddress, upstreamNumber: Long): GatewayResult<GatewayPullRequestDetail> = error("unused")
    override suspend fun getEffectiveDefaultReviewers(repository: GatewayRepositoryAddress, upstreamNumber: Long): GatewayResult<List<GatewayUserObservation>> = error("unused")
    override suspend fun listBuilds(repository: GatewayRepositoryAddress, upstreamNumber: Long): GatewayResult<List<GatewayBuildObservation>> = error("unused")
    override suspend fun listTasks(repository: GatewayRepositoryAddress, upstreamNumber: Long): GatewayResult<List<GatewayTaskObservation>> = error("unused")
    override suspend fun listActivity(repository: GatewayRepositoryAddress, upstreamNumber: Long): GatewayResult<List<GatewayActivityObservation>> = error("unused")
}

private class CountingClock(private val value: Instant) : Clock() {
    var calls = 0
    override fun getZone(): ZoneId = ZoneOffset.UTC
    override fun withZone(zone: ZoneId): Clock = this
    override fun instant(): Instant = value.also { calls++ }
}

package com.mindtable.bitbuckethelper.application.service

import com.mindtable.bitbuckethelper.application.model.*
import com.mindtable.bitbuckethelper.application.port.outbound.*
import com.mindtable.bitbuckethelper.domain.shared.*
import java.net.URI
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import kotlinx.coroutines.test.runTest

class ReadQueryServicesTest {
    private val now = Instant.parse("2026-08-15T12:00:00Z")

    @Test
    fun `every read port returns its typed unconfigured result`() = runTest {
        val services = services(ReadState())

        assertEquals(DashboardResult.WorkspaceNotConfigured, services.dashboard(GetDashboardSnapshotQuery(null)))
        assertEquals(ListPullRequestsResult.WorkspaceNotConfigured, services.listPullRequests())
        assertEquals(
            GetPullRequestResult.WorkspaceNotConfigured,
            services.getPullRequest(GetPullRequestQuery(pullRequestA)),
        )
        assertEquals(GetInboxResult.WorkspaceNotConfigured, services.getInbox())
        assertEquals(GetSynchronizationStatusResult.WorkspaceNotConfigured, services.getSynchronizationStatus())
    }

    @Test
    fun `bulk and detail projections expose only active configured context in stable product order`() = runTest {
        val failure = SynchronizationFailure(SynchronizationFailureCategory.NETWORK, true, now.plusSeconds(30))
        val partial = PartialFailureMetadata(3, 2, listOf(failure))
        val repositoryAlpha = repository(repositoryB, "Alpha", "alpha")
        val repositoryAlphaTie = repository(repositoryA, "ALPHA", "alpha-tie")
        val removed = repository(repositoryRemoved, "Removed", "removed", now.minusSeconds(1))
        val newer = pullRequest(
            id = pullRequestB,
            repositoryId = repositoryB,
            number = 22,
            title = "Newer",
            updatedAt = now.minusSeconds(5),
            readiness = availableReadiness(7),
            builds = listOf(
                StoredBuildObservation("z-build", BuildState.SUCCESSFUL, now),
                StoredBuildObservation("a-build", BuildState.FAILED, now),
            ),
        )
        val sameTimeLowerId = pullRequest(
            id = pullRequestA,
            repositoryId = repositoryB,
            number = 21,
            title = "Tie A",
            updatedAt = now.minusSeconds(10),
            readiness = StoredReadiness.Unavailable("Facts unavailable"),
            builds = emptyList(),
        )
        val sameTimeHigherId = pullRequest(
            id = pullRequestC,
            repositoryId = repositoryB,
            number = 23,
            title = "Tie C",
            updatedAt = now.minusSeconds(10),
            readiness = availableReadiness(6),
            builds = listOf(StoredBuildObservation("build", BuildState.IN_PROGRESS, now)),
        )
        val inactive = pullRequest(
            id = pullRequestInactive,
            repositoryId = repositoryB,
            number = 24,
            title = "Inactive secret",
            active = false,
            inactiveAt = now.minusSeconds(60),
        )
        val removedRepositoryPullRequest = pullRequest(
            id = pullRequestRemoved,
            repositoryId = repositoryRemoved,
            number = 25,
            title = "Removed repository secret",
        )
        val actionableNewest = action(
            actionItemC,
            pullRequestB,
            repositoryB,
            activityAt = now.minusSeconds(1),
            actorStableId = "actor-new",
            actorDisplayName = "New Actor",
        )
        val actionableTieA = action(
            actionItemA,
            pullRequestB,
            repositoryB,
            activityAt = now.minusSeconds(2),
        )
        val actionableTieB = action(
            actionItemB,
            pullRequestB,
            repositoryB,
            activityAt = now.minusSeconds(2),
        )
        val acknowledged = action(
            actionItemAcknowledged,
            pullRequestB,
            repositoryB,
            state = ActionItemState.ACKNOWLEDGED,
            acknowledgedVersion = versionA,
            acknowledgedAt = now.minusSeconds(3),
            activityAt = now.minusSeconds(3),
        )
        val closed = action(
            actionItemClosed,
            pullRequestB,
            repositoryB,
            state = ActionItemState.CLOSED,
            activityAt = now.minusSeconds(4),
        )
        val state = ReadState(
            configuration = configuration(listOf(removed, repositoryAlpha, repositoryAlphaTie)),
            pullRequests = mutableListOf(inactive, sameTimeHigherId, removedRepositoryPullRequest, newer, sameTimeLowerId),
            actionItems = mutableListOf(
                closed,
                action(actionItemInactive, pullRequestInactive, repositoryB),
                actionableTieB,
                acknowledged,
                action(actionItemRemoved, pullRequestRemoved, repositoryRemoved),
                actionableNewest,
                actionableTieA,
            ),
            synchronizations = mutableListOf<StoredSynchronizationSnapshot>(
                storedSynchronization(repositoryB, problem = SynchronizationProblem.Present(partial)),
                storedSynchronization(repositoryA, snapshotAt = null),
                storedSynchronization(repositoryRemoved),
            ),
        )
        val services = services(state)

        val dashboard = assertInstanceOf(
            DashboardResult.SnapshotChanged::class.java,
            services.dashboard(GetDashboardSnapshotQuery(null)),
        ).snapshot
        val listed = assertInstanceOf(
            ListPullRequestsResult.Available::class.java,
            services.listPullRequests(),
        ).repositoryGroups
        val inbox = assertInstanceOf(GetInboxResult.Available::class.java, services.getInbox()).inbox
        val synchronizationResults = assertInstanceOf(
            GetSynchronizationStatusResult.Available::class.java,
            services.getSynchronizationStatus(),
        ).repositories

        assertEquals(listOf(repositoryA, repositoryB), dashboard.repositoryGroups.map { it.repositoryId })
        assertEquals(dashboard.repositoryGroups, listed)
        assertEquals(listOf(repositoryA, repositoryB), dashboard.workspace.repositories.map { it.repositoryId })
        assertFalse(dashboard.toString().contains("Inactive secret"))
        assertFalse(dashboard.toString().contains("Removed repository secret"))
        val alpha = dashboard.repositoryGroups.single { it.repositoryId == repositoryB }
        assertEquals(listOf(pullRequestB, pullRequestA, pullRequestC), alpha.pullRequests.map { it.id })
        assertEquals(ReadinessSummaryProjection(1, 2, 1), alpha.readinessSummary)
        assertEquals(SynchronizationProblem.Present(partial), alpha.synchronization.problem)
        val card = alpha.pullRequests.first()
        assertEquals(ActorProjection("author-22", "Author 22"), card.author)
        assertEquals(BuildState.FAILED, card.buildState)
        assertEquals(3, card.actionableItemCount)
        assertEquals(1, card.acknowledgedItemCount)
        assertEquals(
            listOf(actionItemC, actionItemA, actionItemB, actionItemAcknowledged, actionItemClosed),
            card.actionItems.map { it.id },
        )
        assertEquals(ActorProjection("actor-new", "New Actor"), card.actionItems.first().actor)
        assertEquals(listOf(actionItemC, actionItemA, actionItemB), inbox.items.map { it.id })
        assertEquals(listOf(repositoryA, repositoryB), synchronizationResults.map { it.repositoryId })

        val detail = assertInstanceOf(
            GetPullRequestResult.Found::class.java,
            services.getPullRequest(GetPullRequestQuery(pullRequestB)),
        ).pullRequest
        assertEquals("head-22", detail.headCommit)
        assertEquals(listOf("a-build", "z-build"), detail.builds.map { it.key })
        val readiness = assertInstanceOf(ReadinessProjection.Available::class.java, detail.pullRequest.readiness)
        assertEquals(7, readiness.total)
        assertEquals(7, readiness.checks.size)
        assertEquals(Freshness.Fresh(now.minusSeconds(120), Duration.ofSeconds(120)), detail.freshness)
        assertEquals(
            GetPullRequestResult.PullRequestNotFound(pullRequestInactive),
            services.getPullRequest(GetPullRequestQuery(pullRequestInactive)),
        )
        assertEquals(
            GetPullRequestResult.PullRequestNotFound(pullRequestRemoved),
            services.getPullRequest(GetPullRequestQuery(pullRequestRemoved)),
        )
    }

    @Test
    fun `freshness uses one clock instant an inclusive stale boundary and nonnegative age`() = runTest {
        val clock = MutableProjectionClock(now)
        val state = ReadState(
            configuration = configuration(listOf(repository(repositoryA, "Alpha", "alpha"))),
            pullRequests = mutableListOf(pullRequest()),
            synchronizations = mutableListOf(storedSynchronization(repositoryA, snapshotAt = now.minusSeconds(599))),
        )
        val services = services(state, clock)

        var projectedSynchronization = services.synchronization(repositoryA)
        assertEquals(Freshness.Fresh(now.minusSeconds(599), Duration.ofSeconds(599)), projectedSynchronization.freshness)

        state.synchronizations[0] = storedSynchronization(repositoryA, snapshotAt = now.minusSeconds(600))
        projectedSynchronization = services.synchronization(repositoryA)
        assertEquals(
            Freshness.Stale(now.minusSeconds(600), Duration.ofSeconds(600), now),
            projectedSynchronization.freshness,
        )

        state.synchronizations[0] = storedSynchronization(repositoryA, snapshotAt = now.plusSeconds(30))
        projectedSynchronization = services.synchronization(repositoryA)
        assertEquals(Freshness.Fresh(now.plusSeconds(30), Duration.ZERO), projectedSynchronization.freshness)
    }

    @Test
    fun `revisions are url safe canonical order independent and exclude generated time and age`() = runTest {
        val clock = MutableProjectionClock(now)
        val firstState = canonicalRevisionState(reversed = false)
        val secondState = canonicalRevisionState(reversed = true)
        val firstServices = services(firstState, clock)
        val secondServices = services(secondState, clock)

        val first = firstServices.changed()
        val reordered = secondServices.changed()
        assertTrue(first.revision.value.matches(Regex("dr_[A-Za-z0-9_-]+")))
        assertTrue(first.repositoryGroups.all { it.revision.value.matches(Regex("rrev_[A-Za-z0-9_-]+")) })
        assertEquals(first.revision, reordered.revision)
        assertEquals(
            first.repositoryGroups.map { it.revision },
            reordered.repositoryGroups.map { it.revision },
        )

        clock.current = now.plusSeconds(1)
        val oneSecondOlder = firstServices.changed()
        assertNotEquals(first.generatedAt, oneSecondOlder.generatedAt)
        assertEquals(first.revision, oneSecondOlder.revision)
        assertEquals(
            first.repositoryGroups.map { it.revision },
            oneSecondOlder.repositoryGroups.map { it.revision },
        )

        val visibleMutations: List<(ReadState) -> Unit> = listOf(
            { it.configuration = requireNotNull(it.configuration).copy(workspaceDisplayName = "Changed workspace") },
            { state -> state.configuration = requireNotNull(state.configuration).copy(retentionDays = 31) },
            { state -> state.configuration = requireNotNull(state.configuration).copy(repositories = state.configuration!!.repositories.map { it.copy(displayName = it.displayName + " changed") }) },
            { it.pullRequests[0] = it.pullRequests[0].copy(title = "Changed PR") },
            { it.pullRequests[0] = it.pullRequests[0].copy(authorDisplayName = "Changed author") },
            { it.pullRequests[0] = it.pullRequests[0].copy(headCommit = "changed-head") },
            { it.pullRequests[0] = it.pullRequests[0].copy(readiness = StoredReadiness.Unavailable("Changed readiness")) },
            { it.pullRequests[0] = it.pullRequests[0].copy(builds = listOf(StoredBuildObservation("build", BuildState.FAILED, now))) },
            { it.actionItems[0] = it.actionItems[0].copy(actorDisplayName = "Changed actor") },
            { it.actionItems[0] = it.actionItems[0].copy(activityVersion = versionB) },
            { it.synchronizations[0] = it.synchronizations[0].copy(lastAttemptOutcome = SynchronizationAttemptOutcome.FAILED) },
            { it.synchronizations[0] = it.synchronizations[0].copy(problem = SynchronizationProblem.Present(PartialFailureMetadata(1, 0, listOf(networkFailure())))) },
        )
        visibleMutations.forEachIndexed { index, mutation ->
            val state = canonicalRevisionState(reversed = index % 2 == 0)
            mutation(state)
            assertNotEquals(first.revision, services(state, MutableProjectionClock(now)).changed().revision, "mutation $index")
        }
    }

    @Test
    fun `freshness and polling bucket transitions revise once while equal revision returns current polling`() = runTest {
        val clock = MutableProjectionClock(now)
        val state = canonicalRevisionState(reversed = false).also {
            it.synchronizations[0] = it.synchronizations[0].copy(snapshotAt = now.minusSeconds(599))
        }
        val services = services(state, clock)
        val fresh = services.changed()

        clock.current = now.plusSeconds(1)
        val staleAtBoundary = services.changed()
        assertNotEquals(fresh.revision, staleAtBoundary.revision)
        clock.current = now.plusSeconds(60)
        assertEquals(staleAtBoundary.revision, services.changed().revision)

        state.synchronizations[0] = state.synchronizations[0].copy(activity = SynchronizationActivity.RUNNING)
        val active = services.changed()
        assertNotEquals(staleAtBoundary.revision, active.revision)
        assertEquals(DashboardPolling.Active(250), active.polling)
        clock.current = now.plusSeconds(61)
        val unchanged = assertInstanceOf(
            DashboardResult.SnapshotUnchanged::class.java,
            services.dashboard(GetDashboardSnapshotQuery(active.revision)),
        )
        assertEquals(active.revision, unchanged.revision)
        assertEquals(clock.current, unchanged.serverTime)
        assertEquals(DashboardPolling.Active(250), unchanged.polling)
    }

    @Test
    fun `partial failure keeps the last known pull requests and no raw body side channel is projected`() = runTest {
        val rawBody = "RAW_MARKDOWN_MUST_NOT_ESCAPE"
        val failure = networkFailure()
        val state = canonicalRevisionState(reversed = false).apply {
            unprojectedRawBodies[actionItemA] = rawBody
            synchronizations[0] = synchronizations[0].copy(
                lastAttemptOutcome = SynchronizationAttemptOutcome.PARTIAL_FAILURE,
                problem = SynchronizationProblem.Present(PartialFailureMetadata(2, 1, listOf(failure))),
                snapshotAt = now.minusSeconds(60),
            )
        }

        val dashboard = services(state).changed()
        val repository = dashboard.repositoryGroups.single { it.repositoryId == repositoryA }

        assertEquals(listOf(pullRequestB, pullRequestA), repository.pullRequests.map { it.id })
        assertEquals(
            SynchronizationProblem.Present(PartialFailureMetadata(2, 1, listOf(failure))),
            repository.synchronization.problem,
        )
        assertFalse(dashboard.toString().contains(rawBody))
        assertFalse(
            PullRequestDetailProjection::class.java.declaredFields.any {
                it.name.contains("body", ignoreCase = true) || it.name.contains("markdown", ignoreCase = true)
            },
        )
        assertFalse(
            ActionItemProjection::class.java.declaredFields.any {
                it.name.contains("body", ignoreCase = true) || it.name.contains("markdown", ignoreCase = true)
            },
        )
    }

    private fun services(state: ReadState, clock: Clock = Clock.fixed(now, ZoneOffset.UTC)) = ReadQueryServices(
        transactions = ReadTransactions(state),
        clock = clock,
        freshnessPolicy = ProjectionFreshnessPolicy(Duration.ofMinutes(10), Duration.ofMillis(250)),
    )

    private suspend fun ReadQueryServices.changed(): DashboardSnapshot = assertInstanceOf(
        DashboardResult.SnapshotChanged::class.java,
        dashboard(GetDashboardSnapshotQuery(null)),
    ).snapshot

    private suspend fun ReadQueryServices.synchronization(repositoryId: RepositoryId): SynchronizationProjection =
        assertInstanceOf(
            GetSynchronizationStatusResult.Available::class.java,
            getSynchronizationStatus(),
        ).repositories.single { it.repositoryId == repositoryId }

    private fun canonicalRevisionState(reversed: Boolean): ReadState {
        val repositories = listOf(
            repository(repositoryA, "Alpha", "alpha"),
            repository(repositoryB, "Beta", "beta"),
        )
        val pullRequests = listOf(
            pullRequest(
                pullRequestA,
                repositoryA,
                number = 1,
                updatedAt = now.minusSeconds(30),
                builds = ordered(
                    reversed,
                    StoredBuildObservation("z-build", BuildState.SUCCESSFUL, now.minusSeconds(1)),
                    StoredBuildObservation("a-build", BuildState.FAILED, now.minusSeconds(2)),
                ),
            ),
            pullRequest(
                pullRequestB,
                repositoryA,
                number = 2,
                updatedAt = now.minusSeconds(10),
                builds = ordered(
                    reversed,
                    StoredBuildObservation("y-build", BuildState.IN_PROGRESS, now.minusSeconds(3)),
                    StoredBuildObservation("b-build", BuildState.SUCCESSFUL, now.minusSeconds(4)),
                ),
            ),
            pullRequest(
                pullRequestC,
                repositoryB,
                number = 3,
                updatedAt = now.minusSeconds(20),
                builds = ordered(
                    reversed,
                    StoredBuildObservation("x-build", BuildState.UNKNOWN, now.minusSeconds(5)),
                    StoredBuildObservation("c-build", BuildState.SUCCESSFUL, now.minusSeconds(6)),
                ),
            ),
        )
        val actions = listOf(
            action(actionItemA, pullRequestA, repositoryA, activityAt = now.minusSeconds(8)),
            action(actionItemB, pullRequestA, repositoryA, activityAt = now.minusSeconds(7)),
            action(actionItemC, pullRequestB, repositoryA, activityAt = now.minusSeconds(6)),
        )
        val failures = ordered(
            reversed,
            networkFailure(),
            SynchronizationFailure(SynchronizationFailureCategory.AUTHORIZATION, false, null),
        )
        val synchronizations = listOf(
            storedSynchronization(
                repositoryA,
                snapshotAt = now.minusSeconds(120),
                problem = SynchronizationProblem.Present(PartialFailureMetadata(3, 1, failures)),
            ),
            storedSynchronization(repositoryB, snapshotAt = now.minusSeconds(90)),
        )
        return ReadState(
            configuration = configuration(if (reversed) repositories.reversed() else repositories),
            pullRequests = (if (reversed) pullRequests.reversed() else pullRequests).toMutableList(),
            actionItems = (if (reversed) actions.reversed() else actions).toMutableList(),
            synchronizations = (if (reversed) synchronizations.reversed() else synchronizations).toMutableList(),
        )
    }

    private fun <T> ordered(reversed: Boolean, vararg values: T): List<T> =
        values.toList().let { if (reversed) it.reversed() else it }

    private fun configuration(repositories: List<StoredConfiguredRepository>) = StoredInstallationConfiguration(
        workspaceId = WorkspaceId("ws_team"),
        bitbucketApiBaseUrl = URI("https://api.bitbucket.org/2.0"),
        workspaceSlug = "team",
        workspaceDisplayName = "Team",
        workspaceWebUrl = URI("https://bitbucket.org/team"),
        currentUserStableId = "current-user",
        currentUserDisplayName = "Current User",
        configuredAt = now.minusSeconds(3_600),
        retentionDays = 30,
        repositories = repositories,
    )

    private fun repository(
        id: RepositoryId,
        displayName: String,
        slug: String,
        removedAt: Instant? = null,
    ) = StoredConfiguredRepository(
        id,
        WorkspaceId("ws_team"),
        slug,
        displayName,
        URI("https://bitbucket.org/team/$slug"),
        removedAt,
    )

    private fun pullRequest(
        id: PullRequestId = pullRequestA,
        repositoryId: RepositoryId = repositoryA,
        number: Long = 1,
        title: String = "Pull request",
        updatedAt: Instant = now.minusSeconds(30),
        active: Boolean = true,
        inactiveAt: Instant? = null,
        readiness: StoredReadiness = availableReadiness(6),
        builds: List<StoredBuildObservation> = listOf(StoredBuildObservation("build", BuildState.SUCCESSFUL, now)),
    ) = StoredPullRequestSnapshot(
        id = id,
        repositoryId = repositoryId,
        upstreamNumber = number,
        title = title,
        authorStableId = "author-$number",
        authorDisplayName = "Author $number",
        draft = false,
        headCommit = "head-$number",
        webUrl = URI("https://bitbucket.org/team/repository/pull-requests/$number"),
        createdAt = now.minusSeconds(3_600),
        updatedAt = updatedAt,
        observedAt = now.minusSeconds(20),
        active = active,
        inactiveAt = inactiveAt,
        readiness = readiness,
        builds = builds,
        buildsWereGreen = builds.isNotEmpty() && builds.all { it.state == BuildState.SUCCESSFUL },
    )

    private fun availableReadiness(passed: Int) = StoredReadiness.Available(
        passed = passed,
        total = 7,
        checks = (1..7).map { number ->
            StoredReadinessCheck("CHECK_$number", number <= passed, if (number <= passed) null else "Check failed")
        },
    )

    private fun action(
        id: ActionItemId,
        pullRequestId: PullRequestId,
        repositoryId: RepositoryId,
        state: ActionItemState = ActionItemState.OPEN,
        acknowledgedVersion: ActivityVersion? = null,
        acknowledgedAt: Instant? = null,
        activityAt: Instant = now.minusSeconds(10),
        actorStableId: String = "actor",
        actorDisplayName: String = "Actor",
    ) = StoredActionItemSnapshot(
        id = id,
        pullRequestId = pullRequestId,
        repositoryId = repositoryId,
        sourceKind = "COMMENT",
        upstreamSourceId = "source-${id.value}",
        actorStableId = actorStableId,
        actorDisplayName = actorDisplayName,
        activityAt = activityAt,
        observedAt = activityAt,
        activityVersion = versionA,
        state = state,
        acknowledgedVersion = acknowledgedVersion,
        acknowledgedAt = acknowledgedAt,
        webUrl = URI("https://bitbucket.org/team/repository/pull-requests/1#${id.value}"),
    )

    private fun storedSynchronization(
        repositoryId: RepositoryId,
        activity: SynchronizationActivity = SynchronizationActivity.IDLE,
        snapshotAt: Instant? = now.minusSeconds(120),
        problem: SynchronizationProblem = SynchronizationProblem.None,
    ) = StoredSynchronizationSnapshot(
        repositoryId = repositoryId,
        activity = activity,
        lastAttemptAt = now.minusSeconds(60),
        lastAttemptOutcome = SynchronizationAttemptOutcome.SUCCEEDED,
        lastSuccessAt = snapshotAt,
        snapshotAt = snapshotAt,
        problem = problem,
        consecutiveFailureCount = 0,
        backoffUntil = null,
        pullRequestCursor = "pull-request-cursor",
        activityCursor = "activity-cursor",
    )

    private fun networkFailure() = SynchronizationFailure(
        SynchronizationFailureCategory.NETWORK,
        retryable = true,
        retryAt = now.plusSeconds(30),
    )

    private companion object {
        val repositoryA = RepositoryId("repo_alpha")
        val repositoryB = RepositoryId("repo_beta")
        val repositoryRemoved = RepositoryId("repo_removed")
        val pullRequestA = PullRequestId("pr_alpha")
        val pullRequestB = PullRequestId("pr_beta")
        val pullRequestC = PullRequestId("pr_charlie")
        val pullRequestInactive = PullRequestId("pr_inactive")
        val pullRequestRemoved = PullRequestId("pr_removed")
        val actionItemA = ActionItemId("ai_alpha")
        val actionItemB = ActionItemId("ai_beta")
        val actionItemC = ActionItemId("ai_charlie")
        val actionItemAcknowledged = ActionItemId("ai_acknowledged")
        val actionItemClosed = ActionItemId("ai_closed")
        val actionItemInactive = ActionItemId("ai_inactive")
        val actionItemRemoved = ActionItemId("ai_removed")
        val versionA = ActivityVersion("av_alpha")
        val versionB = ActivityVersion("av_beta")
    }
}

private data class ReadState(
    var configuration: StoredInstallationConfiguration? = null,
    val pullRequests: MutableList<StoredPullRequestSnapshot> = mutableListOf(),
    val actionItems: MutableList<StoredActionItemSnapshot> = mutableListOf(),
    val synchronizations: MutableList<StoredSynchronizationSnapshot> = mutableListOf(),
    val unprojectedRawBodies: MutableMap<ActionItemId, String> = mutableMapOf(),
)

private class ReadTransactions(private val state: ReadState) : ApplicationTransactionRunner {
    override suspend fun <T> inTransaction(block: suspend ApplicationTransaction.() -> T): T =
        block(ReadTransaction(state))
}

private class ReadTransaction(private val state: ReadState) : ApplicationTransaction {
    override val configurationStore = object : ConfigurationStore {
        override suspend fun find() = state.configuration
        override suspend fun save(configuration: StoredInstallationConfiguration) = error("read fake")
    }
    override val pullRequestStore = object : PullRequestStore {
        override suspend fun find(id: PullRequestId) = state.pullRequests.singleOrNull { it.id == id }
        override suspend fun listByRepository(repositoryId: RepositoryId, includeInactive: Boolean) =
            state.pullRequests.filter { it.repositoryId == repositoryId && (includeInactive || it.active) }
        override suspend fun save(snapshot: StoredPullRequestSnapshot) = error("read fake")
        override suspend fun markMissingInactive(repositoryId: RepositoryId, activePullRequestIds: Set<PullRequestId>, authoritativeAt: Instant) = error("read fake")
        override suspend fun markInactive(id: PullRequestId, inactiveAt: Instant) = error("read fake")
        override suspend fun listInactiveBefore(cutoff: Instant) = error("read fake")
        override suspend fun delete(id: PullRequestId) = error("read fake")
    }
    override val actionItemStore = object : ActionItemStore {
        override suspend fun find(id: ActionItemId) = state.actionItems.singleOrNull { it.id == id }
        override suspend fun listByPullRequest(pullRequestId: PullRequestId) = state.actionItems.filter { it.pullRequestId == pullRequestId }
        override suspend fun listActionable() = state.actionItems.filter { it.state == ActionItemState.OPEN && it.acknowledgedVersion != it.activityVersion }
        override suspend fun save(snapshot: StoredActionItemSnapshot) = error("read fake")
        override suspend fun acknowledge(id: ActionItemId, expectedVersion: ActivityVersion, acknowledgedAt: Instant) = error("read fake")
        override suspend fun deleteByPullRequest(pullRequestId: PullRequestId) = error("read fake")
    }
    override val synchronizationCheckpointStore = object : SynchronizationCheckpointStore {
        override suspend fun find(repositoryId: RepositoryId) = state.synchronizations.singleOrNull { it.repositoryId == repositoryId }
        override suspend fun list() = state.synchronizations.toList()
        override suspend fun save(snapshot: StoredSynchronizationSnapshot) = error("read fake")
    }
    override val notificationIntentStore: NotificationIntentStore get() = error("read fake")
    override val reminderProjectionStore: ReminderProjectionStore get() = error("read fake")
}

private class MutableProjectionClock(var current: Instant) : Clock() {
    override fun getZone(): ZoneId = ZoneOffset.UTC
    override fun withZone(zone: ZoneId): Clock = this
    override fun instant(): Instant = current
}

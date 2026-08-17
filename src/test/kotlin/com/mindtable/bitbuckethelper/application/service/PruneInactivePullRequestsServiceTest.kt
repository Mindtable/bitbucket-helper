package com.mindtable.bitbuckethelper.application.service

import com.mindtable.bitbuckethelper.application.model.*
import com.mindtable.bitbuckethelper.application.port.outbound.*
import com.mindtable.bitbuckethelper.domain.shared.*
import java.net.URI
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class PruneInactivePullRequestsServiceTest {
    private val now = Instant.parse("2026-08-15T12:00:00Z")
    private val cutoff = now.minusSeconds(30L * 24 * 60 * 60)

    @Test
    fun `unconfigured retention is a typed no-op with one completion instant`() = runTest {
        val state = PruneState()
        val clock = CountingPruneClock(now)

        val result = PruneInactivePullRequestsService(PruneTransactions(state), clock).invoke()

        assertEquals(PruneInactivePullRequestsResult(0, now), result)
        assertEquals(1, clock.calls)
        assertTrue(state.pullRequests.isEmpty())
        assertTrue(state.actionItems.isEmpty())
    }

    @Test
    fun `configured retention prunes only strictly older inactive pull requests without actionable items`() = runTest {
        val activeOld = pullRequest(PullRequestId("pr_active"), active = true, inactiveAt = null, observedAt = cutoff.minusSeconds(100))
        val exactBoundary = pullRequest(PullRequestId("pr_boundary"), inactiveAt = cutoff)
        val recent = pullRequest(PullRequestId("pr_recent"), inactiveAt = cutoff.plusSeconds(1))
        val actionableNoAck = pullRequest(PullRequestId("pr_open_no_ack"), inactiveAt = cutoff.minusSeconds(1))
        val actionableStaleAck = pullRequest(PullRequestId("pr_open_stale_ack"), inactiveAt = cutoff.minusSeconds(2))
        val safeAcknowledged = pullRequest(PullRequestId("pr_acknowledged"), inactiveAt = cutoff.minusSeconds(3))
        val safeClosed = pullRequest(PullRequestId("pr_closed"), inactiveAt = cutoff.minusSeconds(4))
        val safeOpenCurrentAck = pullRequest(PullRequestId("pr_open_current_ack"), inactiveAt = cutoff.minusSeconds(5))
        val state = PruneState(
            configuration = configuration(retentionDays = 30),
            pullRequests = mutableListOf(
                safeClosed,
                exactBoundary,
                activeOld,
                actionableStaleAck,
                recent,
                safeOpenCurrentAck,
                actionableNoAck,
                safeAcknowledged,
            ),
            actionItems = mutableListOf(
                action(actionableNoAck.id, ActionItemState.OPEN, acknowledgedVersion = null),
                action(actionableStaleAck.id, ActionItemState.OPEN, acknowledgedVersion = versionOld),
                action(safeAcknowledged.id, ActionItemState.ACKNOWLEDGED, acknowledgedVersion = versionCurrent),
                action(safeClosed.id, ActionItemState.CLOSED, acknowledgedVersion = null),
                action(safeOpenCurrentAck.id, ActionItemState.OPEN, acknowledgedVersion = versionCurrent),
            ),
        )

        val result = PruneInactivePullRequestsService(
            PruneTransactions(state),
            Clock.fixed(now, ZoneOffset.UTC),
        ).invoke()

        assertEquals(PruneInactivePullRequestsResult(3, now), result)
        assertEquals(
            setOf(activeOld.id, exactBoundary.id, recent.id, actionableNoAck.id, actionableStaleAck.id),
            state.pullRequests.mapTo(mutableSetOf()) { it.id },
        )
        assertEquals(
            setOf(actionableNoAck.id, actionableStaleAck.id),
            state.actionItems.mapTo(mutableSetOf()) { it.pullRequestId },
        )
        assertEquals(
            listOf(
                "actions:${safeAcknowledged.id.value}",
                "pull-request:${safeAcknowledged.id.value}",
                "actions:${safeClosed.id.value}",
                "pull-request:${safeClosed.id.value}",
                "actions:${safeOpenCurrentAck.id.value}",
                "pull-request:${safeOpenCurrentAck.id.value}",
            ),
            state.committedDeleteOrder,
        )
    }

    @Test
    fun `load filter and ordered deletes roll back atomically on failure`() = runTest {
        val first = pullRequest(PullRequestId("pr_first"), inactiveAt = cutoff.minusSeconds(10))
        val second = pullRequest(PullRequestId("pr_second"), inactiveAt = cutoff.minusSeconds(20))
        val state = PruneState(
            configuration = configuration(30),
            pullRequests = mutableListOf(first, second),
            actionItems = mutableListOf(
                action(first.id, ActionItemState.CLOSED, null),
                action(second.id, ActionItemState.ACKNOWLEDGED, versionCurrent),
            ),
            failDeletingPullRequest = second.id,
        )
        val beforePullRequests = state.pullRequests.toList()
        val beforeActions = state.actionItems.toList()

        val failure = runCatching {
            PruneInactivePullRequestsService(
                PruneTransactions(state),
                Clock.fixed(now, ZoneOffset.UTC),
            ).invoke()
        }.exceptionOrNull()

        assertEquals("delete failed", failure?.message)
        assertEquals(beforePullRequests, state.pullRequests)
        assertEquals(beforeActions, state.actionItems)
        assertTrue(state.committedDeleteOrder.isEmpty())
    }

    @Test
    fun `pruning is idempotent and each invocation reports its own completedAt`() = runTest {
        val old = pullRequest(PullRequestId("pr_old"), inactiveAt = cutoff.minusSeconds(1))
        val state = PruneState(
            configuration = configuration(30),
            pullRequests = mutableListOf(old),
            actionItems = mutableListOf(action(old.id, ActionItemState.CLOSED, null)),
        )
        val clock = MutablePruneClock(now)
        val service = PruneInactivePullRequestsService(PruneTransactions(state), clock)

        assertEquals(PruneInactivePullRequestsResult(1, now), service.invoke())
        clock.current = now.plusSeconds(60)
        assertEquals(PruneInactivePullRequestsResult(0, now.plusSeconds(60)), service.invoke())
        assertTrue(state.pullRequests.isEmpty())
        assertTrue(state.actionItems.isEmpty())
    }

    private fun configuration(retentionDays: Int) = StoredInstallationConfiguration(
        WorkspaceId("ws_team"),
        URI("https://api.bitbucket.org/2.0"),
        "team",
        "Team",
        URI("https://bitbucket.org/team"),
        "current-user",
        "Current User",
        now.minusSeconds(3_600),
        retentionDays,
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
    )

    private fun pullRequest(
        id: PullRequestId,
        active: Boolean = false,
        inactiveAt: Instant?,
        observedAt: Instant = inactiveAt ?: now,
    ) = StoredPullRequestSnapshot(
        id,
        repositoryA,
        id.value.hashCode().toLong().let { if (it < 0) -it else it },
        id.value,
        "author",
        "Author",
        false,
        "head",
        URI("https://bitbucket.org/team/repository/pull-requests/${id.value}"),
        now.minusSeconds(100),
        now.minusSeconds(100),
        observedAt,
        active,
        inactiveAt,
        StoredReadiness.Unavailable("Unavailable"),
        emptyList(),
        false,
    )

    private fun action(
        pullRequestId: PullRequestId,
        state: ActionItemState,
        acknowledgedVersion: ActivityVersion?,
    ) = StoredActionItemSnapshot(
        ActionItemId("ai_${pullRequestId.value.removePrefix("pr_")}"),
        pullRequestId,
        repositoryA,
        "COMMENT",
        "source",
        "actor",
        "Actor",
        now.minusSeconds(100),
        now.minusSeconds(100),
        versionCurrent,
        state,
        acknowledgedVersion,
        acknowledgedVersion?.let { now.minusSeconds(100) },
        URI("https://bitbucket.org/team/repository/pull-requests/1#comment"),
    )

    private companion object {
        val repositoryA = RepositoryId("repo_alpha")
        val versionOld = ActivityVersion("av_old")
        val versionCurrent = ActivityVersion("av_current")
    }
}

private data class PruneState(
    var configuration: StoredInstallationConfiguration? = null,
    val pullRequests: MutableList<StoredPullRequestSnapshot> = mutableListOf(),
    val actionItems: MutableList<StoredActionItemSnapshot> = mutableListOf(),
    val committedDeleteOrder: MutableList<String> = mutableListOf(),
    var failDeletingPullRequest: PullRequestId? = null,
)

private class PruneTransactions(private val state: PruneState) : ApplicationTransactionRunner {
    override suspend fun <T> inTransaction(block: suspend ApplicationTransaction.() -> T): T {
        val working = PruneState(
            configuration = state.configuration,
            pullRequests = state.pullRequests.toMutableList(),
            actionItems = state.actionItems.toMutableList(),
            committedDeleteOrder = mutableListOf(),
            failDeletingPullRequest = state.failDeletingPullRequest,
        )
        val result = block(PruneTransaction(working))
        state.configuration = working.configuration
        state.pullRequests.clear()
        state.pullRequests += working.pullRequests
        state.actionItems.clear()
        state.actionItems += working.actionItems
        state.committedDeleteOrder += working.committedDeleteOrder
        return result
    }
}

private class PruneTransaction(private val state: PruneState) : ApplicationTransaction {
    override val configurationStore = object : ConfigurationStore {
        override suspend fun find() = state.configuration
        override suspend fun save(configuration: StoredInstallationConfiguration) { state.configuration = configuration }
    }
    override val pullRequestStore = object : PullRequestStore {
        override suspend fun find(id: PullRequestId) = state.pullRequests.singleOrNull { it.id == id }
        override suspend fun listByRepository(repositoryId: RepositoryId, includeInactive: Boolean) = error("unused")
        override suspend fun save(snapshot: StoredPullRequestSnapshot) = error("unused")
        override suspend fun markMissingInactive(repositoryId: RepositoryId, activePullRequestIds: Set<PullRequestId>, authoritativeAt: Instant) = error("unused")
        override suspend fun markInactive(id: PullRequestId, inactiveAt: Instant) = error("unused")
        override suspend fun listInactiveBefore(cutoff: Instant) = state.pullRequests.toList()
        override suspend fun delete(id: PullRequestId) {
            if (state.failDeletingPullRequest == id) error("delete failed")
            check(state.actionItems.none { it.pullRequestId == id }) { "actions must be deleted first" }
            state.committedDeleteOrder += "pull-request:${id.value}"
            state.pullRequests.removeIf { it.id == id }
        }
    }
    override val actionItemStore = object : ActionItemStore {
        override suspend fun find(id: ActionItemId) = state.actionItems.singleOrNull { it.id == id }
        override suspend fun listByPullRequest(pullRequestId: PullRequestId) = state.actionItems.filter { it.pullRequestId == pullRequestId }
        override suspend fun listActionable() = error("unused")
        override suspend fun save(snapshot: StoredActionItemSnapshot) = error("unused")
        override suspend fun acknowledge(id: ActionItemId, expectedVersion: ActivityVersion, acknowledgedAt: Instant) = error("unused")
        override suspend fun deleteByPullRequest(pullRequestId: PullRequestId) {
            state.committedDeleteOrder += "actions:${pullRequestId.value}"
            state.actionItems.removeIf { it.pullRequestId == pullRequestId }
        }
    }
    override val synchronizationCheckpointStore: SynchronizationCheckpointStore get() = error("unused")
    override val notificationIntentStore: NotificationIntentStore get() = error("unused")
    override val reminderProjectionStore: ReminderProjectionStore get() = error("unused")
}

private class CountingPruneClock(private val value: Instant) : Clock() {
    var calls = 0
    override fun getZone() = ZoneOffset.UTC
    override fun withZone(zone: java.time.ZoneId): Clock = this
    override fun instant(): Instant = value.also { calls++ }
}

private class MutablePruneClock(var current: Instant) : Clock() {
    override fun getZone() = ZoneOffset.UTC
    override fun withZone(zone: java.time.ZoneId): Clock = this
    override fun instant(): Instant = current
}

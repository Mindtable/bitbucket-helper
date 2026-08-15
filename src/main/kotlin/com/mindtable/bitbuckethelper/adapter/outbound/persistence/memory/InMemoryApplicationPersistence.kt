package com.mindtable.bitbuckethelper.adapter.outbound.persistence.memory

import com.mindtable.bitbuckethelper.application.model.*
import com.mindtable.bitbuckethelper.application.port.outbound.*
import com.mindtable.bitbuckethelper.domain.shared.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Instant
import java.util.concurrent.atomic.AtomicBoolean

class InMemoryApplicationPersistence : ApplicationTransactionRunner, AutoCloseable {
    private val mutex = Mutex()
    private val closed = AtomicBoolean(false)
    private var state = State()

    override suspend fun <T> inTransaction(block: suspend ApplicationTransaction.() -> T): T {
        check(!closed.get()) { "Persistence is closed" }
        return mutex.withLock {
            check(!closed.get()) { "Persistence is closed" }
            val working = state.copyForTransaction()
            val result = block(Transaction(working))
            state = working.freeze()
            result
        }
    }

    override fun close() { closed.set(true) }

    private data class State(
        var configuration: StoredInstallationConfiguration? = null,
        val pullRequests: MutableMap<PullRequestId, StoredPullRequestSnapshot> = linkedMapOf(),
        val actionItems: MutableMap<ActionItemId, StoredActionItemSnapshot> = linkedMapOf(),
        val synchronizations: MutableMap<RepositoryId, StoredSynchronizationSnapshot> = linkedMapOf(),
        val intents: MutableMap<NotificationIntentId, StoredNotificationIntent> = linkedMapOf(),
        val attempts: MutableMap<NotificationIntentId, MutableList<StoredNotificationAttempt>> = linkedMapOf(),
    ) {
        fun copyForTransaction() = State(
            configuration?.detached(), pullRequests.mapValuesTo(linkedMapOf()) { it.value.detached() },
            actionItems.toMutableMap(), synchronizations.toMutableMap(), intents.toMutableMap(),
            attempts.mapValuesTo(linkedMapOf()) { (_, value) -> value.toMutableList() },
        )
        fun freeze() = copyForTransaction()
    }

    private class Transaction(private val state: State) : ApplicationTransaction {
        override val configurationStore: ConfigurationStore = object : ConfigurationStore {
            override suspend fun find() = state.configuration?.detached()
            override suspend fun save(configuration: StoredInstallationConfiguration) { state.configuration = configuration.detached() }
        }
        override val pullRequestStore: PullRequestStore = object : PullRequestStore {
            override suspend fun find(id: PullRequestId) = state.pullRequests[id]?.detached()
            override suspend fun listByRepository(repositoryId: RepositoryId, includeInactive: Boolean) = state.pullRequests.values
                .filter { it.repositoryId == repositoryId && (includeInactive || it.active) }.sortedBy { it.id.value }.map { it.detached() }
            override suspend fun save(snapshot: StoredPullRequestSnapshot) { state.pullRequests[snapshot.id] = snapshot.detached() }
            override suspend fun markMissingInactive(repositoryId: RepositoryId, activePullRequestIds: Set<PullRequestId>, authoritativeAt: Instant): List<StoredPullRequestSnapshot> {
                val changed = state.pullRequests.values.filter { it.repositoryId == repositoryId && it.active && it.id !in activePullRequestIds }
                    .sortedBy { it.id.value }
                    .map { it.copy(active = false, inactiveAt = authoritativeAt, observedAt = authoritativeAt) }
                changed.forEach { state.pullRequests[it.id] = it }
                return changed.map { it.detached() }
            }
            override suspend fun markInactive(id: PullRequestId, inactiveAt: Instant) {
                state.pullRequests[id]?.let { if (it.active) state.pullRequests[id] = it.copy(active = false, inactiveAt = inactiveAt, observedAt = inactiveAt) }
            }
            override suspend fun listInactiveBefore(cutoff: Instant) = state.pullRequests.values
                .filter { !it.active && it.inactiveAt != null && it.inactiveAt < cutoff }.sortedBy { it.id.value }.map { it.detached() }
            override suspend fun delete(id: PullRequestId) {
                state.pullRequests.remove(id)
                state.actionItems.entries.removeIf { it.value.pullRequestId == id }
            }
        }
        override val actionItemStore: ActionItemStore = object : ActionItemStore {
            override suspend fun find(id: ActionItemId) = state.actionItems[id]
            override suspend fun listByPullRequest(pullRequestId: PullRequestId) = state.actionItems.values.filter { it.pullRequestId == pullRequestId }.sortedBy { it.id.value }
            override suspend fun listActionable() = state.actionItems.values.filter { it.isActionable() }.sortedBy { it.id.value }
            override suspend fun save(snapshot: StoredActionItemSnapshot) { state.actionItems[snapshot.id] = snapshot }
            override suspend fun acknowledge(id: ActionItemId, expectedVersion: ActivityVersion, acknowledgedAt: Instant): StoredAcknowledgmentResult {
                val current = state.actionItems[id] ?: return StoredAcknowledgmentResult.Missing
                if (current.activityVersion != expectedVersion) return StoredAcknowledgmentResult.VersionMismatch(current)
                if (current.acknowledgedVersion == expectedVersion) return StoredAcknowledgmentResult.AlreadyApplied(current)
                if (current.state != ActionItemState.OPEN) return StoredAcknowledgmentResult.NotActionable(current)
                val updated = current.copy(state = ActionItemState.ACKNOWLEDGED, acknowledgedVersion = expectedVersion, acknowledgedAt = acknowledgedAt)
                state.actionItems[id] = updated
                return StoredAcknowledgmentResult.Updated(updated)
            }
            override suspend fun deleteByPullRequest(pullRequestId: PullRequestId) { state.actionItems.entries.removeIf { it.value.pullRequestId == pullRequestId } }
        }
        override val synchronizationCheckpointStore: SynchronizationCheckpointStore = object : SynchronizationCheckpointStore {
            override suspend fun find(repositoryId: RepositoryId) = state.synchronizations[repositoryId]
            override suspend fun list() = state.synchronizations.values.sortedBy { it.repositoryId.value }
            override suspend fun save(snapshot: StoredSynchronizationSnapshot) { state.synchronizations[snapshot.repositoryId] = snapshot }
        }
        override val notificationIntentStore: NotificationIntentStore = object : NotificationIntentStore {
            override suspend fun insertIfAbsent(intent: StoredNotificationIntent): NotificationIntentInsertResult {
                val existing = state.intents[intent.id]
                if (existing != null) return NotificationIntentInsertResult.Existing(existing)
                state.intents[intent.id] = intent
                return NotificationIntentInsertResult.Inserted(intent)
            }
            override suspend fun find(id: NotificationIntentId) = state.intents[id]
            override suspend fun findDue(now: Instant, limit: Int): List<StoredNotificationIntent> {
                if (limit <= 0) return emptyList()
                return state.intents.values.filter {
                    it.state == NotificationIntentState.PENDING && it.nextAttemptAt?.let { due -> due <= now } == true &&
                        (it.lease == null || it.lease.expiresAt <= now)
                }.sortedWith(compareBy<StoredNotificationIntent>({ it.nextAttemptAt }, { it.createdAt }, { it.id.value })).take(limit)
            }
            override suspend fun tryClaim(id: NotificationIntentId, owner: String, acquiredAt: Instant, expiresAt: Instant): StoredNotificationIntent? {
                val current = state.intents[id] ?: return null
                if (current.state != NotificationIntentState.PENDING) return null
                if (current.lease != null && current.lease.expiresAt > acquiredAt && current.lease.owner != owner) return null
                val claimed = current.copy(lease = NotificationLease(owner, acquiredAt, expiresAt))
                state.intents[id] = claimed
                return claimed
            }
            override suspend fun releaseClaim(id: NotificationIntentId, owner: String): Boolean {
                val current = state.intents[id] ?: return false
                if (current.lease?.owner != owner) return false
                state.intents[id] = current.copy(lease = null)
                return true
            }
            override suspend fun completeAttempt(id: NotificationIntentId, owner: String, completion: NotificationAttemptCompletion): Boolean {
                val current = state.intents[id] ?: return false
                if (current.lease?.owner != owner || completion.attempt.intentId != id || completion.attempt.attemptNumber != current.attemptCount + 1) return false
                state.attempts.getOrPut(id) { mutableListOf() }.add(completion.attempt)
                state.intents[id] = current.copy(state = completion.resultingState, attemptCount = completion.attempt.attemptNumber,
                    nextAttemptAt = completion.nextAttemptAt, lease = null)
                return true
            }
            override suspend fun listAttempts(id: NotificationIntentId) = state.attempts[id].orEmpty().sortedWith(compareBy({ it.attemptNumber }, { it.id.value }))
        }
        override val reminderProjectionStore: ReminderProjectionStore = object : ReminderProjectionStore {
            override suspend fun listRepositoriesWithActionableItems(): List<ReminderRepositoryProjection> {
                val configuration = state.configuration ?: return emptyList()
                val eligible = actionableRepositoryIds()
                return configuration.repositories.filter { it.removedAt == null && it.id in eligible }.sortedBy { it.id.value }
                    .map { ReminderRepositoryProjection(it.id, it.displayName, it.webUrl) }
            }
            override suspend fun listActionableItems(repositoryId: RepositoryId): List<ReminderActionItemProjection> {
                if (state.configuration?.repositories?.none { it.id == repositoryId && it.removedAt == null } != false) return emptyList()
                val activePullRequests = state.pullRequests.values.filter { it.repositoryId == repositoryId && it.active }.mapTo(mutableSetOf()) { it.id }
                return state.actionItems.values.filter { it.repositoryId == repositoryId && it.pullRequestId in activePullRequests && it.isActionable() }
                    .sortedBy { it.id.value }.map { ReminderActionItemProjection(it.id, it.repositoryId, it.activityVersion) }
            }
            private fun actionableRepositoryIds() = state.pullRequests.values.filter { it.active }.mapNotNull { pullRequest ->
                pullRequest.repositoryId.takeIf { repositoryId -> state.actionItems.values.any { it.repositoryId == repositoryId && it.pullRequestId == pullRequest.id && it.isActionable() } }
            }.toSet()
        }
    }
}

private fun StoredActionItemSnapshot.isActionable() = state == ActionItemState.OPEN && acknowledgedVersion != activityVersion
private fun StoredInstallationConfiguration.detached() = copy(repositories = repositories.toList())
private fun StoredPullRequestSnapshot.detached() = copy(
    readiness = when (val value = readiness) {
        is StoredReadiness.Available -> value.copy(checks = value.checks.toList())
        is StoredReadiness.Unavailable -> value.copy()
    },
    builds = builds.toList(),
)

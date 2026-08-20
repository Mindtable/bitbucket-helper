package com.mindtable.bitbuckethelper.application.service

import com.mindtable.bitbuckethelper.application.model.NotificationAttemptCompletion
import com.mindtable.bitbuckethelper.application.model.NotificationIntentInsertResult
import com.mindtable.bitbuckethelper.application.model.NotificationIntentState
import com.mindtable.bitbuckethelper.application.model.NotificationLease
import com.mindtable.bitbuckethelper.application.model.StoredNotificationAttempt
import com.mindtable.bitbuckethelper.application.model.StoredNotificationIntent
import com.mindtable.bitbuckethelper.application.port.outbound.ActionItemStore
import com.mindtable.bitbuckethelper.application.port.outbound.ApplicationTransaction
import com.mindtable.bitbuckethelper.application.port.outbound.ApplicationTransactionRunner
import com.mindtable.bitbuckethelper.application.port.outbound.ConfigurationStore
import com.mindtable.bitbuckethelper.application.port.outbound.NotificationIntentStore
import com.mindtable.bitbuckethelper.application.port.outbound.PullRequestStore
import com.mindtable.bitbuckethelper.application.port.outbound.ReminderProjectionStore
import com.mindtable.bitbuckethelper.application.port.outbound.SynchronizationCheckpointStore
import com.mindtable.bitbuckethelper.domain.shared.NotificationIntentId
import java.time.Instant
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.asContextElement
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

internal class FakeNotificationTransactionRunner(
    initialIntents: List<StoredNotificationIntent> = emptyList(),
) : ApplicationTransactionRunner {
    private val mutex = Mutex()
    private val transactionMarker = ThreadLocal<Boolean>()
    private var committed = State(
        intents = initialIntents.associateBy { it.id }.toMutableMap(),
    )
    private var completionFailure: Throwable? = null
    private var completionGate: CompletionGate? = null
    private var releaseFailure: Throwable? = null
    @Volatile
    var committedAttemptCount: Int = 0
        private set

    val isTransactionActive: Boolean
        get() = transactionMarker.get() == true

    override suspend fun <T> inTransaction(block: suspend ApplicationTransaction.() -> T): T = mutex.withLock {
        val working = committed.copyForTransaction()
        val operations = mutableListOf<String>()
        val transaction = NotificationTransaction(NotificationStore(working, operations))
        val result = withContext(transactionMarker.asContextElement(true)) {
            block(transaction)
        }
        committed = working.also { it.transactions += operations.toList() }
        committedAttemptCount = committed.attempts.values.sumOf { it.size }
        result
    }

    suspend fun intent(id: NotificationIntentId): StoredNotificationIntent? = mutex.withLock {
        committed.intents[id]
    }

    suspend fun attempts(id: NotificationIntentId): List<StoredNotificationAttempt> = mutex.withLock {
        committed.attempts[id].orEmpty().toList()
    }

    suspend fun claimInvocations(): List<ClaimInvocation> = mutex.withLock {
        committed.claimInvocations.toList()
    }

    suspend fun findDueInvocations(): List<FindDueInvocation> = mutex.withLock {
        committed.findDueInvocations.toList()
    }

    suspend fun transactions(): List<List<String>> = mutex.withLock {
        committed.transactions.toList()
    }

    suspend fun failNextCompletion(failure: Throwable) = mutex.withLock {
        completionFailure = failure
    }

    suspend fun blockNextCompletion(
        entered: CompletableDeferred<Unit>,
        release: CompletableDeferred<Unit>,
    ) = mutex.withLock {
        completionGate = CompletionGate(entered, release)
    }

    suspend fun failNextRelease(failure: Throwable) = mutex.withLock {
        releaseFailure = failure
    }

    data class ClaimInvocation(
        val id: NotificationIntentId,
        val owner: String,
        val acquiredAt: Instant,
        val expiresAt: Instant,
    )

    data class FindDueInvocation(
        val now: Instant,
        val limit: Int,
    )

    private data class CompletionGate(
        val entered: CompletableDeferred<Unit>,
        val release: CompletableDeferred<Unit>,
    )

    private inner class NotificationStore(
        private val state: State,
        private val operations: MutableList<String>,
    ) : NotificationIntentStore {
        override suspend fun insertIfAbsent(intent: StoredNotificationIntent): NotificationIntentInsertResult {
            operations += "insertIfAbsent:${intent.id.value}"
            val existing = state.intents[intent.id]
            if (existing != null) return NotificationIntentInsertResult.Existing(existing)
            state.intents[intent.id] = intent
            return NotificationIntentInsertResult.Inserted(intent)
        }

        override suspend fun find(id: NotificationIntentId): StoredNotificationIntent? {
            operations += "find:${id.value}"
            return state.intents[id]
        }

        override suspend fun findDue(now: Instant, limit: Int): List<StoredNotificationIntent> {
            operations += "findDue"
            state.findDueInvocations += FindDueInvocation(now, limit)
            return state.intents.values
                .asSequence()
                .filter { it.isDueAt(now) }
                .sortedWith(compareBy<StoredNotificationIntent>({ it.createdAt }, { it.id.value }))
                .take(limit)
                .toList()
        }

        override suspend fun tryClaim(
            id: NotificationIntentId,
            owner: String,
            acquiredAt: Instant,
            expiresAt: Instant,
        ): StoredNotificationIntent? {
            operations += "tryClaim:${id.value}"
            state.claimInvocations += ClaimInvocation(id, owner, acquiredAt, expiresAt)
            val intent = state.intents[id]?.takeIf { it.isDueAt(acquiredAt) } ?: return null
            return intent.copy(
                lease = NotificationLease(owner, acquiredAt, expiresAt),
            ).also { state.intents[id] = it }
        }

        override suspend fun releaseClaim(id: NotificationIntentId, owner: String): Boolean {
            operations += "releaseClaim:${id.value}"
            releaseFailure?.let { failure ->
                releaseFailure = null
                throw failure
            }
            val intent = state.intents[id] ?: return false
            if (intent.lease?.owner != owner) return false
            state.intents[id] = intent.copy(lease = null)
            return true
        }

        override suspend fun completeAttempt(
            id: NotificationIntentId,
            owner: String,
            completion: NotificationAttemptCompletion,
        ): Boolean {
            operations += "completeAttempt:${id.value}"
            completionGate?.also { completionGate = null }?.let { gate ->
                gate.entered.complete(Unit)
                gate.release.await()
            }
            completionFailure?.let { failure ->
                completionFailure = null
                throw failure
            }
            val intent = state.intents[id] ?: return false
            if (intent.lease?.owner != owner || completion.attempt.intentId != id) return false
            state.attempts.getOrPut(id, ::mutableListOf) += completion.attempt
            state.intents[id] = intent.copy(
                state = completion.resultingState,
                attemptCount = completion.attempt.attemptNumber,
                nextAttemptAt = completion.nextAttemptAt,
                lease = null,
            )
            return true
        }

        override suspend fun listAttempts(id: NotificationIntentId): List<StoredNotificationAttempt> {
            operations += "listAttempts:${id.value}"
            return state.attempts[id].orEmpty().toList()
        }
    }

    private class NotificationTransaction(
        override val notificationIntentStore: NotificationIntentStore,
    ) : ApplicationTransaction {
        override val configurationStore: ConfigurationStore
            get() = error("Notification test transaction does not provide configuration state")
        override val pullRequestStore: PullRequestStore
            get() = error("Notification test transaction does not provide pull request state")
        override val actionItemStore: ActionItemStore
            get() = error("Notification test transaction does not provide action item state")
        override val synchronizationCheckpointStore: SynchronizationCheckpointStore
            get() = error("Notification test transaction does not provide synchronization state")
        override val reminderProjectionStore: ReminderProjectionStore
            get() = error("Notification test transaction does not provide reminder state")
    }

    private data class State(
        val intents: MutableMap<NotificationIntentId, StoredNotificationIntent>,
        val attempts: MutableMap<NotificationIntentId, MutableList<StoredNotificationAttempt>> = mutableMapOf(),
        val claimInvocations: MutableList<ClaimInvocation> = mutableListOf(),
        val findDueInvocations: MutableList<FindDueInvocation> = mutableListOf(),
        val transactions: MutableList<List<String>> = mutableListOf(),
    ) {
        fun copyForTransaction(): State = State(
            intents = intents.toMutableMap(),
            attempts = attempts.mapValuesTo(mutableMapOf()) { (_, values) -> values.toMutableList() },
            claimInvocations = claimInvocations.toMutableList(),
            findDueInvocations = findDueInvocations.toMutableList(),
            transactions = transactions.toMutableList(),
        )
    }

    private fun StoredNotificationIntent.isDueAt(now: Instant): Boolean =
        state == NotificationIntentState.PENDING &&
            (nextAttemptAt == null || !nextAttemptAt.isAfter(now)) &&
            (lease == null || !lease.expiresAt.isAfter(now))
}

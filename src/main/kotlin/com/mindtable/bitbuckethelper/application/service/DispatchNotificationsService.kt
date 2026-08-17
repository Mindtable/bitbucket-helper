package com.mindtable.bitbuckethelper.application.service

import com.mindtable.bitbuckethelper.application.model.NotificationAttemptCompletion
import com.mindtable.bitbuckethelper.application.model.NotificationDeliveryResult
import com.mindtable.bitbuckethelper.application.model.NotificationDispatchSummary
import com.mindtable.bitbuckethelper.application.model.NotificationIntentState
import com.mindtable.bitbuckethelper.application.model.StoredNotificationAttempt
import com.mindtable.bitbuckethelper.application.model.StoredNotificationIntent
import com.mindtable.bitbuckethelper.application.policy.NotificationRetryDecision
import com.mindtable.bitbuckethelper.application.policy.NotificationRetryPolicy
import com.mindtable.bitbuckethelper.application.port.inbound.DispatchNotifications
import com.mindtable.bitbuckethelper.application.port.outbound.ApplicationTransactionRunner
import com.mindtable.bitbuckethelper.application.port.outbound.NotificationSender
import com.mindtable.bitbuckethelper.domain.shared.NotificationAttemptId
import com.mindtable.bitbuckethelper.domain.shared.NotificationIntentId
import java.time.Clock
import java.time.Duration
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

class DispatchNotificationsService(
    private val transactions: ApplicationTransactionRunner,
    private val sender: NotificationSender,
    private val retryPolicy: NotificationRetryPolicy,
    private val clock: Clock,
) : DispatchNotifications {
    override suspend fun invoke(intentIds: List<NotificationIntentId>): NotificationDispatchSummary {
        val candidates = transactions.inTransaction {
            buildList {
                intentIds.distinct().forEach { id ->
                    notificationIntentStore.find(id)?.let(::add)
                }
            }
        }.sortedWith(compareBy<StoredNotificationIntent>({ it.createdAt }, { it.id.value }))
        val outcomes = candidates.mapNotNull { dispatch(it.id) }
        return NotificationDispatchSummary(
            attemptedIntentIds = outcomes.map { it.id },
            acceptedCount = outcomes.count { it.decision == NotificationRetryDecision.Accepted },
            retryScheduledCount = outcomes.count { it.decision is NotificationRetryDecision.RetryAt },
            exhaustedCount = outcomes.count { it.decision == NotificationRetryDecision.Exhausted },
        )
    }

    private suspend fun dispatch(id: NotificationIntentId): DispatchOutcome? {
        val acquiredAt = clock.instant()
        val worker = "notification-worker-${UUID.randomUUID()}"
        val claimed = transactions.inTransaction {
            notificationIntentStore.tryClaim(
                id = id,
                owner = worker,
                acquiredAt = acquiredAt,
                expiresAt = acquiredAt.plus(LEASE_DURATION),
            )
        } ?: return null

        val result = try {
            sender.send(claimed.request)
        } catch (cancellation: CancellationException) {
            releaseAfterCancellation(claimed.id, worker, cancellation)
        }
        val completion = runCatching {
            withContext(NonCancellable) {
                completeClaimedAttempt(claimed, worker, result)
            }
        }
        val cancellation = currentCancellation()
        if (cancellation != null) {
            if (completion.isFailure) {
                cancellation.addSuppressed(IllegalStateException("Notification attempt cleanup failed"))
            }
            throw cancellation
        }
        return completion.getOrThrow()
    }

    private suspend fun completeClaimedAttempt(
        claimed: StoredNotificationIntent,
        worker: String,
        result: NotificationDeliveryResult,
    ): DispatchOutcome {
        val completedAt = clock.instant()
        val attemptNumber = claimed.attemptCount + 1
        val decision = retryPolicy.decide(result, attemptNumber, completedAt)
        val completion = NotificationAttemptCompletion(
            attempt = StoredNotificationAttempt(
                id = NotificationAttemptId("na_${UUID.randomUUID()}"),
                intentId = claimed.id,
                attemptNumber = attemptNumber,
                completedAt = completedAt,
                result = result,
            ),
            resultingState = decision.resultingState(),
            nextAttemptAt = (decision as? NotificationRetryDecision.RetryAt)?.at,
        )
        val completed = transactions.inTransaction {
            notificationIntentStore.completeAttempt(claimed.id, worker, completion)
        }
        check(completed) { "Notification attempt could not be recorded" }
        return DispatchOutcome(claimed.id, decision)
    }

    private suspend fun currentCancellation(): CancellationException? = try {
        currentCoroutineContext().ensureActive()
        null
    } catch (cancellation: CancellationException) {
        cancellation
    }

    private suspend fun releaseAfterCancellation(
        id: NotificationIntentId,
        worker: String,
        cancellation: CancellationException,
    ): Nothing {
        val cleanupFailure = try {
            val released = withContext(NonCancellable) {
                transactions.inTransaction {
                    notificationIntentStore.releaseClaim(id, worker)
                }
            }
            if (released) null else IllegalStateException("Notification claim cleanup failed")
        } catch (_: Throwable) {
            IllegalStateException("Notification claim cleanup failed")
        }
        cleanupFailure?.let(cancellation::addSuppressed)
        throw cancellation
    }

    private fun NotificationRetryDecision.resultingState(): NotificationIntentState = when (this) {
        NotificationRetryDecision.Accepted -> NotificationIntentState.ACCEPTED
        is NotificationRetryDecision.RetryAt -> NotificationIntentState.PENDING
        NotificationRetryDecision.Exhausted -> NotificationIntentState.EXHAUSTED
    }

    private data class DispatchOutcome(
        val id: NotificationIntentId,
        val decision: NotificationRetryDecision,
    )

    private companion object {
        val LEASE_DURATION: Duration = Duration.ofMinutes(2)
    }
}

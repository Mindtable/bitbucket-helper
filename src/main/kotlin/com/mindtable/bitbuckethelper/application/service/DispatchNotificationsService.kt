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
import com.mindtable.bitbuckethelper.application.port.outbound.NotificationAttemptOutcome
import com.mindtable.bitbuckethelper.application.port.outbound.NotificationRetryOutcome
import com.mindtable.bitbuckethelper.application.port.outbound.NotificationSender
import com.mindtable.bitbuckethelper.application.port.outbound.OperationalEvent
import com.mindtable.bitbuckethelper.application.port.outbound.OperationalEventRecorder
import com.mindtable.bitbuckethelper.domain.shared.NotificationAttemptId
import com.mindtable.bitbuckethelper.domain.shared.NotificationIntentId
import com.mindtable.bitbuckethelper.observability.MonotonicTimeSource
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
    private val operationalEventRecorder: OperationalEventRecorder = OperationalEventRecorder.NONE,
    private val timeSource: MonotonicTimeSource = MonotonicTimeSource.SYSTEM,
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
        val startedAtNanos = runCatching { timeSource.nanoTime() }.getOrDefault(0L)
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
                completeClaimedAttempt(claimed, worker, result, startedAtNanos)
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
        startedAtNanos: Long,
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
        operationalEventRecorder.recordSafely(
            OperationalEvent.NotificationAttemptFinished(
                intentId = completion.attempt.intentId,
                attemptId = completion.attempt.id,
                attemptNumber = completion.attempt.attemptNumber,
                outcome = result.toOperationalOutcome(),
                failureCategory = (result as? NotificationDeliveryResult.Failed)?.category,
                ambiguous = (result as? NotificationDeliveryResult.Failed)?.ambiguous,
                retryDecision = decision.toOperationalOutcome(),
                durationMilliseconds = elapsedMilliseconds(startedAtNanos),
            ),
        )
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
        } catch (failure: Throwable) {
            failure
        }
        cleanupFailure?.let {
            operationalEventRecorder.recordSafely(OperationalEvent.NotificationCleanupFailed(id, it))
            cancellation.addSuppressed(IllegalStateException("Notification claim cleanup failed"))
        }
        throw cancellation
    }

    private fun elapsedMilliseconds(startedAtNanos: Long): Long = runCatching {
        ((timeSource.nanoTime() - startedAtNanos).coerceAtLeast(0L)) / NANOS_PER_MILLISECOND
    }.getOrDefault(0L)

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
        const val NANOS_PER_MILLISECOND = 1_000_000L
    }
}

private fun OperationalEventRecorder.recordSafely(event: OperationalEvent) {
    try {
        record(event)
    } catch (_: Throwable) {
        // Observability must not alter notification state or cancellation behavior.
    }
}

private fun NotificationDeliveryResult.toOperationalOutcome(): NotificationAttemptOutcome = when (this) {
    NotificationDeliveryResult.Accepted -> NotificationAttemptOutcome.ACCEPTED
    is NotificationDeliveryResult.Failed -> NotificationAttemptOutcome.FAILED
}

private fun NotificationRetryDecision.toOperationalOutcome(): NotificationRetryOutcome = when (this) {
    NotificationRetryDecision.Accepted -> NotificationRetryOutcome.ACCEPTED
    is NotificationRetryDecision.RetryAt -> NotificationRetryOutcome.RETRY_SCHEDULED
    NotificationRetryDecision.Exhausted -> NotificationRetryOutcome.EXHAUSTED
}

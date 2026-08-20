package com.mindtable.bitbuckethelper.adapter.outbound.observability

import com.mindtable.bitbuckethelper.adapter.outbound.observability.Log4jOperationalEventRecorder.Companion.fixedCategory
import com.mindtable.bitbuckethelper.application.port.outbound.OperationalEvent
import com.mindtable.bitbuckethelper.application.port.outbound.OperationalEventRecorder
import com.mindtable.bitbuckethelper.application.port.outbound.NotificationAttemptOutcome
import com.mindtable.bitbuckethelper.application.port.outbound.NotificationRetryOutcome
import com.mindtable.bitbuckethelper.application.port.outbound.RefreshRepositoryOutcome
import com.mindtable.bitbuckethelper.observability.BackendEventRecorder
import com.mindtable.bitbuckethelper.observability.BackendLogEvent
import java.util.Locale

/** Converts framework-free application events into the fixed backend schema. */
class Log4jOperationalEventRecorder(
    private val backendRecorder: BackendEventRecorder = BackendEventRecorder.NONE,
) : OperationalEventRecorder {
    override fun record(event: OperationalEvent) {
        when (event) {
            is OperationalEvent.HealthProbeFailed -> backendRecorder.record(
                BackendLogEvent.HealthProbeFailed(
                    component = fixedCategory(event.component.name),
                    failure = event.failure,
                ),
            )

            is OperationalEvent.RefreshRunRegistered -> backendRecorder.record(
                BackendLogEvent.RefreshRunRegistered(
                    refreshRunId = event.refreshRunId.value,
                    repositoryCount = event.repositoryCount,
                    startedCount = event.startedCount,
                    joinedCount = event.joinedCount,
                    deferredCount = event.deferredCount,
                    notConfiguredCount = event.notConfiguredCount,
                ),
            )

            is OperationalEvent.RefreshRepositoryFinished -> recordRefreshRepository(event)
            is OperationalEvent.NotificationAttemptFinished -> recordNotificationAttempt(event)
            is OperationalEvent.NotificationCleanupFailed -> backendRecorder.record(
                BackendLogEvent.NotificationCleanupFailed(
                    intentId = event.intentId.value,
                    failure = event.failure,
                ),
            )
        }
    }

    private fun recordRefreshRepository(event: OperationalEvent.RefreshRepositoryFinished) {
        val runId = event.refreshRunId?.value
        val repositoryId = event.repositoryId.value
        val category = event.failureCategory?.name?.let(::fixedCategory)
        val retryAt = event.retryAt?.toString()
        when (event.outcome) {
            RefreshRepositoryOutcome.SUCCEEDED,
            RefreshRepositoryOutcome.NOT_CONFIGURED,
            -> backendRecorder.record(
                BackendLogEvent.RefreshRepositoryCompleted(
                    refreshRunId = runId,
                    repositoryId = repositoryId,
                    outcome = fixedCategory(event.outcome.name),
                    durationMilliseconds = event.durationMilliseconds,
                    failureCategory = category,
                    retryable = event.retryable,
                    retryAt = retryAt,
                    failureCount = event.failureCount,
                    failureCategories = fixedCategories(event.failureCategories),
                ),
            )

            RefreshRepositoryOutcome.PARTIAL -> backendRecorder.record(
                BackendLogEvent.RefreshRepositoryPartial(
                    refreshRunId = runId,
                    repositoryId = repositoryId,
                    failureCategory = category ?: "partial",
                    retryable = event.retryable,
                    durationMilliseconds = event.durationMilliseconds,
                    failureCount = event.failureCount,
                    failureCategories = fixedCategories(event.failureCategories),
                ),
            )

            RefreshRepositoryOutcome.FAILED -> backendRecorder.record(
                BackendLogEvent.RefreshRepositoryFailed(
                    refreshRunId = runId,
                    repositoryId = repositoryId,
                    failureCategory = category ?: "unknown",
                    retryable = event.retryable,
                    retryAt = retryAt,
                    durationMilliseconds = event.durationMilliseconds,
                    failureCount = event.failureCount,
                    failureCategories = fixedCategories(event.failureCategories),
                ),
            )

            RefreshRepositoryOutcome.DEFERRED -> backendRecorder.record(
                BackendLogEvent.RefreshRepositoryDeferred(
                    refreshRunId = runId,
                    repositoryId = repositoryId,
                    retryAt = retryAt,
                    durationMilliseconds = event.durationMilliseconds,
                    failureCount = event.failureCount,
                    failureCategories = fixedCategories(event.failureCategories),
                ),
            )

            RefreshRepositoryOutcome.UNEXPECTED -> {
                val failure = event.unexpectedFailure
                if (failure == null) {
                    backendRecorder.record(
                        BackendLogEvent.RefreshRepositoryCompleted(
                            refreshRunId = runId,
                            repositoryId = repositoryId,
                            outcome = "unexpected",
                            durationMilliseconds = event.durationMilliseconds,
                            failureCount = event.failureCount,
                            failureCategories = fixedCategories(event.failureCategories),
                        ),
                    )
                } else {
                    backendRecorder.record(
                        BackendLogEvent.RefreshRepositoryUnexpected(
                            refreshRunId = runId,
                            repositoryId = repositoryId,
                            durationMilliseconds = event.durationMilliseconds,
                            failure = failure,
                            failureCount = event.failureCount,
                            failureCategories = fixedCategories(event.failureCategories),
                        ),
                    )
                }
            }
        }
    }

    private fun recordNotificationAttempt(event: OperationalEvent.NotificationAttemptFinished) {
        val intentId = event.intentId.value
        val attemptId = event.attemptId.value
        val retryDecision = fixedCategory(event.retryDecision.name)
        when (event.outcome) {
            NotificationAttemptOutcome.ACCEPTED -> backendRecorder.record(
                BackendLogEvent.NotificationDeliveryCompleted(
                    intentId = intentId,
                    attemptId = attemptId,
                    attemptNumber = event.attemptNumber,
                    outcome = "accepted",
                    retryDecision = retryDecision,
                    durationMilliseconds = event.durationMilliseconds,
                ),
            )

            NotificationAttemptOutcome.FAILED -> backendRecorder.record(
                BackendLogEvent.NotificationDeliveryFailed(
                    intentId = intentId,
                    attemptId = attemptId,
                    attemptNumber = event.attemptNumber,
                    category = event.failureCategory?.name?.let(::fixedCategory) ?: "unknown",
                    ambiguous = event.ambiguous,
                    retryDecision = retryDecision,
                    durationMilliseconds = event.durationMilliseconds,
                ),
            )
        }
    }

    private companion object {
        private const val MAX_FAILURE_CATEGORIES = 8

        fun fixedCategory(value: String): String = value.lowercase(Locale.ROOT)

        fun fixedCategories(values: List<com.mindtable.bitbuckethelper.application.model.SynchronizationFailureCategory>): List<String> =
            values.asSequence()
                .map { fixedCategory(it.name) }
                .distinct()
                .sorted()
                .take(MAX_FAILURE_CATEGORIES)
                .toList()
    }
}

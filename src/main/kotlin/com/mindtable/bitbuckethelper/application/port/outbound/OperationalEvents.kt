package com.mindtable.bitbuckethelper.application.port.outbound

import com.mindtable.bitbuckethelper.application.model.HealthComponent
import com.mindtable.bitbuckethelper.application.model.NotificationDeliveryFailureCategory
import com.mindtable.bitbuckethelper.application.model.SynchronizationFailureCategory
import com.mindtable.bitbuckethelper.domain.shared.NotificationAttemptId
import com.mindtable.bitbuckethelper.domain.shared.NotificationIntentId
import com.mindtable.bitbuckethelper.domain.shared.RefreshRunId
import com.mindtable.bitbuckethelper.domain.shared.RepositoryId
import java.time.Instant

fun interface OperationalEventRecorder {
    fun record(event: OperationalEvent)

    companion object {
        val NONE: OperationalEventRecorder = OperationalEventRecorder { }
    }
}

enum class RefreshRepositoryOutcome {
    SUCCEEDED,
    PARTIAL,
    FAILED,
    DEFERRED,
    NOT_CONFIGURED,
    UNEXPECTED,
}

enum class NotificationAttemptOutcome {
    ACCEPTED,
    FAILED,
}

enum class NotificationRetryOutcome {
    ACCEPTED,
    RETRY_SCHEDULED,
    EXHAUSTED,
}

sealed interface OperationalEvent {
    class HealthProbeFailed(
        val component: HealthComponent,
        val failure: Throwable,
    ) : OperationalEvent {
        override fun toString(): String =
            "health.probe.failed(component=$component, failure=<redacted>)"
    }

    data class RefreshRunRegistered(
        val refreshRunId: RefreshRunId,
        val repositoryCount: Int,
        val startedCount: Int,
        val joinedCount: Int,
        val deferredCount: Int,
        val notConfiguredCount: Int,
    ) : OperationalEvent

    class RefreshRepositoryFinished(
        val refreshRunId: RefreshRunId?,
        val repositoryId: RepositoryId,
        val outcome: RefreshRepositoryOutcome,
        val failureCategory: SynchronizationFailureCategory?,
        val retryable: Boolean?,
        val retryAt: Instant?,
        val durationMilliseconds: Long,
        val unexpectedFailure: Throwable? = null,
        val failureCount: Int = 0,
        val failureCategories: List<SynchronizationFailureCategory> = emptyList(),
    ) : OperationalEvent {
        override fun toString(): String = buildString {
            append("refresh.repository.finished(")
            append("refreshRunId=").append(refreshRunId?.value)
            append(", repositoryId=").append(repositoryId.value)
            append(", outcome=").append(outcome)
            append(", failureCategory=").append(failureCategory)
            append(", retryable=").append(retryable)
            append(", retryAt=").append(retryAt)
            append(", durationMilliseconds=").append(durationMilliseconds)
            append(", failureCount=").append(failureCount)
            append(", failureCategories=").append(failureCategories)
            if (unexpectedFailure != null) append(", unexpectedFailure=<redacted>")
            append(')')
        }
    }

    data class NotificationAttemptFinished(
        val intentId: NotificationIntentId,
        val attemptId: NotificationAttemptId,
        val attemptNumber: Int,
        val outcome: NotificationAttemptOutcome,
        val failureCategory: NotificationDeliveryFailureCategory?,
        val ambiguous: Boolean?,
        val retryDecision: NotificationRetryOutcome,
        val durationMilliseconds: Long,
    ) : OperationalEvent

    class NotificationCleanupFailed(
        val intentId: NotificationIntentId,
        val failure: Throwable,
    ) : OperationalEvent {
        override fun toString(): String =
            "notification.cleanup.failed(intentId=${intentId.value}, failure=<redacted>)"
    }
}

package com.mindtable.bitbuckethelper.application.policy

import com.mindtable.bitbuckethelper.application.model.NotificationDeliveryFailureCategory
import com.mindtable.bitbuckethelper.application.model.NotificationDeliveryResult
import java.time.Duration
import java.time.Instant

sealed interface NotificationRetryDecision {
    data object Accepted : NotificationRetryDecision
    data class RetryAt(val at: Instant) : NotificationRetryDecision
    data object Exhausted : NotificationRetryDecision
}

class NotificationRetryPolicy {
    fun decide(
        result: NotificationDeliveryResult,
        completedAttemptNumber: Int,
        completedAt: Instant,
    ): NotificationRetryDecision = when (result) {
        NotificationDeliveryResult.Accepted -> NotificationRetryDecision.Accepted
        is NotificationDeliveryResult.Failed -> failedDecision(result.category, completedAttemptNumber, completedAt)
    }

    private fun failedDecision(
        category: NotificationDeliveryFailureCategory,
        completedAttemptNumber: Int,
        completedAt: Instant,
    ): NotificationRetryDecision = when {
        category in TERMINAL_FAILURES -> NotificationRetryDecision.Exhausted
        completedAttemptNumber !in 1..6 -> NotificationRetryDecision.Exhausted
        else -> NotificationRetryDecision.RetryAt(completedAt.plus(RETRY_DELAYS[completedAttemptNumber]))
    }

    private companion object {
        val TERMINAL_FAILURES = setOf(
            NotificationDeliveryFailureCategory.INVALID_ARGUMENTS,
            NotificationDeliveryFailureCategory.UNSUPPORTED_PLATFORM,
        )
        val RETRY_DELAYS = arrayOf(
            Duration.ZERO,
            Duration.ofMinutes(1),
            Duration.ofMinutes(5),
            Duration.ofMinutes(15),
            Duration.ofHours(1),
            Duration.ofHours(6),
            Duration.ofHours(24),
        )
    }
}

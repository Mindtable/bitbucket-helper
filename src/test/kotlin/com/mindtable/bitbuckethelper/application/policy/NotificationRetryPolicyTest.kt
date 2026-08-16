package com.mindtable.bitbuckethelper.application.policy

import com.mindtable.bitbuckethelper.application.model.NotificationDeliveryFailureCategory
import com.mindtable.bitbuckethelper.application.model.NotificationDeliveryResult
import java.time.Instant
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class NotificationRetryPolicyTest {
    @Test
    fun `delivery completion produces the bounded retry decision table`() {
        // Catches a changed delay, misclassified delivery result, or retry past the sixth failed attempt.
        val completedAt = Instant.parse("2026-08-16T09:00:00Z")
        val cases = listOf(
            Case("first failure retries in one minute", failed(NotificationDeliveryFailureCategory.DELIVERY_FAILED), 1, NotificationRetryDecision.RetryAt(Instant.parse("2026-08-16T09:01:00Z"))),
            Case("second failure retries in five minutes", failed(NotificationDeliveryFailureCategory.DELIVERY_FAILED), 2, NotificationRetryDecision.RetryAt(Instant.parse("2026-08-16T09:05:00Z"))),
            Case("third failure retries in fifteen minutes", failed(NotificationDeliveryFailureCategory.DELIVERY_FAILED), 3, NotificationRetryDecision.RetryAt(Instant.parse("2026-08-16T09:15:00Z"))),
            Case("fourth failure retries in one hour", failed(NotificationDeliveryFailureCategory.DELIVERY_FAILED), 4, NotificationRetryDecision.RetryAt(Instant.parse("2026-08-16T10:00:00Z"))),
            Case("fifth failure retries in six hours", failed(NotificationDeliveryFailureCategory.DELIVERY_FAILED), 5, NotificationRetryDecision.RetryAt(Instant.parse("2026-08-16T15:00:00Z"))),
            Case("sixth failure retries in twenty-four hours", failed(NotificationDeliveryFailureCategory.DELIVERY_FAILED), 6, NotificationRetryDecision.RetryAt(Instant.parse("2026-08-17T09:00:00Z"))),
            Case("seventh failure is exhausted", failed(NotificationDeliveryFailureCategory.DELIVERY_FAILED), 7, NotificationRetryDecision.Exhausted),
            Case("invalid arguments are exhausted immediately", failed(NotificationDeliveryFailureCategory.INVALID_ARGUMENTS), 1, NotificationRetryDecision.Exhausted),
            Case("unsupported platform is exhausted immediately", failed(NotificationDeliveryFailureCategory.UNSUPPORTED_PLATFORM), 1, NotificationRetryDecision.Exhausted),
            Case("dependency unavailable retries", failed(NotificationDeliveryFailureCategory.DEPENDENCY_UNAVAILABLE), 1, NotificationRetryDecision.RetryAt(Instant.parse("2026-08-16T09:01:00Z"))),
            Case("delivery timeout retries", failed(NotificationDeliveryFailureCategory.DELIVERY_TIMEOUT), 1, NotificationRetryDecision.RetryAt(Instant.parse("2026-08-16T09:01:00Z"))),
            Case("internal error retries", failed(NotificationDeliveryFailureCategory.INTERNAL_ERROR), 1, NotificationRetryDecision.RetryAt(Instant.parse("2026-08-16T09:01:00Z"))),
            Case("process not started retries", failed(NotificationDeliveryFailureCategory.PROCESS_NOT_STARTED), 1, NotificationRetryDecision.RetryAt(Instant.parse("2026-08-16T09:01:00Z"))),
            Case("malformed response retries", failed(NotificationDeliveryFailureCategory.MALFORMED_RESPONSE), 1, NotificationRetryDecision.RetryAt(Instant.parse("2026-08-16T09:01:00Z"))),
            Case("unexpected exit retries", failed(NotificationDeliveryFailureCategory.UNEXPECTED_EXIT), 1, NotificationRetryDecision.RetryAt(Instant.parse("2026-08-16T09:01:00Z"))),
            Case("terminated by signal retries", failed(NotificationDeliveryFailureCategory.TERMINATED_BY_SIGNAL), 1, NotificationRetryDecision.RetryAt(Instant.parse("2026-08-16T09:01:00Z"))),
            Case("ambiguous process failure retries", failed(NotificationDeliveryFailureCategory.AMBIGUOUS_PROCESS_FAILURE), 1, NotificationRetryDecision.RetryAt(Instant.parse("2026-08-16T09:01:00Z"))),
            Case("accepted delivery is terminal", NotificationDeliveryResult.Accepted, 1, NotificationRetryDecision.Accepted),
        )

        cases.forEach { case ->
            assertEquals(
                case.expected,
                NotificationRetryPolicy().decide(case.result, case.completedAttemptNumber, completedAt),
                case.name,
            )
        }
    }

    private fun failed(category: NotificationDeliveryFailureCategory): NotificationDeliveryResult.Failed =
        NotificationDeliveryResult.Failed(category, ambiguous = false)

    private data class Case(
        val name: String,
        val result: NotificationDeliveryResult,
        val completedAttemptNumber: Int,
        val expected: NotificationRetryDecision,
    )
}

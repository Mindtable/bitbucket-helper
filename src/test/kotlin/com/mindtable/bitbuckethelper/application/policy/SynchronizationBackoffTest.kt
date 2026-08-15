package com.mindtable.bitbuckethelper.application.policy

import com.mindtable.bitbuckethelper.application.model.PartialFailureMetadata
import com.mindtable.bitbuckethelper.application.model.StoredSynchronizationSnapshot
import com.mindtable.bitbuckethelper.application.model.SynchronizationActivity
import com.mindtable.bitbuckethelper.application.model.SynchronizationAttemptOutcome
import com.mindtable.bitbuckethelper.application.model.SynchronizationFailure
import com.mindtable.bitbuckethelper.application.model.SynchronizationFailureCategory
import com.mindtable.bitbuckethelper.application.model.SynchronizationProblem
import com.mindtable.bitbuckethelper.domain.shared.RepositoryId
import java.time.Duration
import java.time.Instant
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class SynchronizationBackoffTest {
    private val repositoryId = RepositoryId("repo_backoff")
    private val now = Instant.parse("2026-08-15T12:00:00Z")
    private val retryableFailure = SynchronizationFailure(
        SynchronizationFailureCategory.NETWORK,
        retryable = true,
        retryAt = null,
    )

    @Test
    fun `default failure schedule is deterministic finite and bounded`() {
        val policy = SynchronizationBackoff()

        assertEquals(now.plusSeconds(30), policy.retryAt(now, checkpoint(1), retryableFailure))
        assertEquals(now.plusSeconds(120), policy.retryAt(now, checkpoint(2), retryableFailure))
        assertEquals(now.plusSeconds(600), policy.retryAt(now, checkpoint(3), retryableFailure))
        assertEquals(now.plusSeconds(1_800), policy.retryAt(now, checkpoint(4), retryableFailure))
        assertEquals(now.plusSeconds(3_600), policy.retryAt(now, checkpoint(5), retryableFailure))
        assertEquals(now.plusSeconds(3_600), policy.retryAt(now, checkpoint(Int.MAX_VALUE), retryableFailure))
    }

    @Test
    fun `later upstream retry instant wins over local delay`() {
        val upstreamRetryAt = now.plus(Duration.ofHours(3))
        val failure = retryableFailure.copy(retryAt = upstreamRetryAt)

        assertEquals(upstreamRetryAt, SynchronizationBackoff().retryAt(now, checkpoint(1), failure))
    }

    @Test
    fun `bounded addition is safe at the instant upper limit`() {
        val nearMaximum = Instant.MAX.minusSeconds(10)

        assertEquals(
            Instant.MAX,
            SynchronizationBackoff().retryAt(nearMaximum, checkpoint(Int.MAX_VALUE), retryableFailure),
        )
    }

    @Test
    fun `explicit schedule supports deterministic policy testing`() {
        val policy = SynchronizationBackoff(
            delays = listOf(Duration.ofSeconds(7), Duration.ofSeconds(11)),
            maximumDelay = Duration.ofSeconds(11),
        )

        assertEquals(now.plusSeconds(7), policy.retryAt(now, checkpoint(1), retryableFailure))
        assertEquals(now.plusSeconds(11), policy.retryAt(now, checkpoint(99), retryableFailure))
    }

    private fun checkpoint(consecutiveFailures: Int) = StoredSynchronizationSnapshot(
        repositoryId = repositoryId,
        activity = SynchronizationActivity.IDLE,
        lastAttemptAt = now,
        lastAttemptOutcome = SynchronizationAttemptOutcome.FAILED,
        lastSuccessAt = now.minusSeconds(300),
        snapshotAt = now.minusSeconds(300),
        problem = SynchronizationProblem.Present(
            PartialFailureMetadata(1, 0, listOf(retryableFailure)),
        ),
        consecutiveFailureCount = consecutiveFailures,
        backoffUntil = null,
        pullRequestCursor = "pull-cursor",
        activityCursor = "activity-cursor",
    )
}

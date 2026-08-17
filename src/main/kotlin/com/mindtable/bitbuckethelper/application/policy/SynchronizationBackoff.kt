package com.mindtable.bitbuckethelper.application.policy

import com.mindtable.bitbuckethelper.application.model.StoredSynchronizationSnapshot
import com.mindtable.bitbuckethelper.application.model.SynchronizationFailure
import java.time.Duration
import java.time.Instant

/**
 * Calculates a repository retry instant from persisted failure state.
 *
 * The default finite schedule is 30 seconds, 2 minutes, 10 minutes, 30 minutes,
 * and 1 hour. Further consecutive failures remain capped at 1 hour. An upstream
 * retry instant is always a lower bound, regardless of the local schedule.
 */
class SynchronizationBackoff(
    private val delays: List<Duration> = DEFAULT_DELAYS,
    private val maximumDelay: Duration = DEFAULT_MAXIMUM_DELAY,
) {
    init {
        require(delays.isNotEmpty()) { "Backoff requires at least one delay" }
        require(!maximumDelay.isZero && !maximumDelay.isNegative) { "Maximum backoff must be positive" }
        require(delays.all { !it.isZero && !it.isNegative && it <= maximumDelay }) {
            "Every backoff delay must be positive and no greater than the maximum"
        }
    }

    fun retryAt(
        now: Instant,
        checkpoint: StoredSynchronizationSnapshot,
        failure: SynchronizationFailure,
    ): Instant {
        val failureIndex = (checkpoint.consecutiveFailureCount.coerceAtLeast(1) - 1)
            .coerceAtMost(delays.lastIndex)
        val localRetryAt = saturatedPlus(now, delays[failureIndex])
        val upstreamRetryAt = failure.retryAt
        return if (upstreamRetryAt != null && upstreamRetryAt > localRetryAt) upstreamRetryAt else localRetryAt
    }

    private fun saturatedPlus(instant: Instant, duration: Duration): Instant =
        runCatching { instant.plus(duration) }.getOrDefault(Instant.MAX)

    companion object {
        val DEFAULT_DELAYS: List<Duration> = listOf(
            Duration.ofSeconds(30),
            Duration.ofMinutes(2),
            Duration.ofMinutes(10),
            Duration.ofMinutes(30),
            Duration.ofHours(1),
        )
        val DEFAULT_MAXIMUM_DELAY: Duration = Duration.ofHours(1)
    }
}

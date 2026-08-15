package com.mindtable.bitbuckethelper.application.model

import com.mindtable.bitbuckethelper.domain.shared.RepositoryId
import java.time.Duration
import java.time.Instant

enum class SynchronizationActivity { IDLE, QUEUED, RUNNING }

enum class SynchronizationAttemptOutcome { SUCCEEDED, PARTIAL_FAILURE, FAILED }

enum class SynchronizationFailureCategory {
    AUTHENTICATION,
    AUTHORIZATION,
    RATE_LIMITED,
    TIMEOUT,
    NETWORK,
    UPSTREAM,
    MALFORMED_UPSTREAM,
}

data class SynchronizationFailure(
    val category: SynchronizationFailureCategory,
    val retryable: Boolean,
    val retryAt: Instant?,
)

data class PartialFailureMetadata(
    val attemptedCount: Int,
    val succeededCount: Int,
    val failures: List<SynchronizationFailure>,
) {
    init {
        require(attemptedCount > 0) { "Partial failure must include at least one attempted operation" }
        require(succeededCount in 0 until attemptedCount) {
            "Partial failure must include at least one failed operation"
        }
        require(failures.size == attemptedCount - succeededCount) {
            "Each failed operation must have one typed failure"
        }
    }

    val failedCount: Int get() = attemptedCount - succeededCount
}

sealed interface SynchronizationProblem {
    data object None : SynchronizationProblem
    data class Present(val metadata: PartialFailureMetadata) : SynchronizationProblem
}

sealed interface Freshness {
    data object NeverSynchronized : Freshness
    data class Fresh(val snapshotAt: Instant, val age: Duration) : Freshness
    data class Stale(val snapshotAt: Instant, val age: Duration, val staleSince: Instant) : Freshness
}

data class StoredSynchronizationSnapshot(
    val repositoryId: RepositoryId,
    val activity: SynchronizationActivity,
    val lastAttemptAt: Instant?,
    val lastAttemptOutcome: SynchronizationAttemptOutcome?,
    val lastSuccessAt: Instant?,
    val snapshotAt: Instant?,
    val problem: SynchronizationProblem,
    val consecutiveFailureCount: Int,
    val backoffUntil: Instant?,
    val pullRequestCursor: String?,
    val activityCursor: String?,
)

data class SynchronizationProjection(
    val repositoryId: RepositoryId,
    val activity: SynchronizationActivity,
    val lastAttemptAt: Instant?,
    val lastAttemptOutcome: SynchronizationAttemptOutcome?,
    val lastSuccessAt: Instant?,
    val freshness: Freshness,
    val problem: SynchronizationProblem,
)

data class RefreshRepositoryCommand(val repositoryId: RepositoryId)

sealed interface RefreshRepositoryResult {
    val repositoryId: RepositoryId

    data class Succeeded(
        override val repositoryId: RepositoryId,
        val completedAt: Instant,
        val synchronization: SynchronizationProjection,
    ) : RefreshRepositoryResult

    data class PartiallySucceeded(
        override val repositoryId: RepositoryId,
        val completedAt: Instant,
        val partialFailure: PartialFailureMetadata,
        val synchronization: SynchronizationProjection,
    ) : RefreshRepositoryResult

    data class Failed(
        override val repositoryId: RepositoryId,
        val failure: SynchronizationFailure,
        val synchronization: SynchronizationProjection,
    ) : RefreshRepositoryResult

    data class DeferredByBackoff(
        override val repositoryId: RepositoryId,
        val retryAt: Instant,
        val synchronization: SynchronizationProjection,
    ) : RefreshRepositoryResult

    data class RepositoryNotConfigured(
        override val repositoryId: RepositoryId,
    ) : RefreshRepositoryResult
}

data class RefreshAllRepositoriesResult(val repositories: List<RefreshRepositoryResult>)

sealed interface GetSynchronizationStatusResult {
    data class Available(val repositories: List<SynchronizationProjection>) : GetSynchronizationStatusResult
    data object WorkspaceNotConfigured : GetSynchronizationStatusResult
}

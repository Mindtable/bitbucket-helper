package com.mindtable.bitbuckethelper.application.model

import com.mindtable.bitbuckethelper.domain.shared.RefreshRunId
import com.mindtable.bitbuckethelper.domain.shared.RepositoryId
import java.time.Instant

sealed interface RefreshTarget {
    data object AllConfiguredRepositories : RefreshTarget
    data class Repositories(val repositoryIds: List<RepositoryId>) : RefreshTarget
}

data class StartRefreshRunCommand(val target: RefreshTarget)

sealed interface RefreshRegistrationDisposition {
    val repositoryId: RepositoryId

    data class Started(override val repositoryId: RepositoryId) : RefreshRegistrationDisposition
    data class JoinedExisting(override val repositoryId: RepositoryId) : RefreshRegistrationDisposition
    data class DeferredByBackoff(
        override val repositoryId: RepositoryId,
        val retryAt: Instant,
    ) : RefreshRegistrationDisposition
    data class RepositoryNotConfigured(override val repositoryId: RepositoryId) : RefreshRegistrationDisposition
}

sealed interface RefreshRunRepositoryEntry {
    val repositoryId: RepositoryId

    data class Queued(override val repositoryId: RepositoryId) : RefreshRunRepositoryEntry
    data class Running(override val repositoryId: RepositoryId) : RefreshRunRepositoryEntry
    data class Succeeded(
        override val repositoryId: RepositoryId,
        val completedAt: Instant,
    ) : RefreshRunRepositoryEntry
    data class PartiallySucceeded(
        override val repositoryId: RepositoryId,
        val completedAt: Instant,
        val partialFailure: PartialFailureMetadata,
    ) : RefreshRunRepositoryEntry
    data class Failed(
        override val repositoryId: RepositoryId,
        val completedAt: Instant,
        val failure: SynchronizationFailure,
    ) : RefreshRunRepositoryEntry
    data class DeferredByBackoff(
        override val repositoryId: RepositoryId,
        val retryAt: Instant,
    ) : RefreshRunRepositoryEntry
}

data class RefreshRunSnapshot(
    val id: RefreshRunId,
    val createdAt: Instant,
    val expiresAt: Instant,
    val repositories: List<RefreshRunRepositoryEntry>,
)

data class ActivePollingAdvice(val afterMilliseconds: Long) {
    init {
        require(afterMilliseconds > 0) { "Active polling advice must have a positive delay" }
    }
}

sealed interface StartRefreshRunResult {
    data object WorkspaceNotConfigured : StartRefreshRunResult
    data object NoRepositoriesConfigured : StartRefreshRunResult
    data class RefreshRunRegistered(
        val refreshRun: RefreshRunSnapshot,
        val dispositions: List<RefreshRegistrationDisposition>,
    ) : StartRefreshRunResult
}

sealed interface GetRefreshRunResult {
    data class RefreshRunInProgress(
        val refreshRun: RefreshRunSnapshot,
        val polling: ActivePollingAdvice,
    ) : GetRefreshRunResult
    data class RefreshRunCompleted(val refreshRun: RefreshRunSnapshot) : GetRefreshRunResult
    data class RefreshRunUnavailable(val refreshRunId: RefreshRunId) : GetRefreshRunResult
}

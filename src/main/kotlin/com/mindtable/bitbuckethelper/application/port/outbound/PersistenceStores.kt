package com.mindtable.bitbuckethelper.application.port.outbound

import com.mindtable.bitbuckethelper.application.model.NotificationAttemptCompletion
import com.mindtable.bitbuckethelper.application.model.NotificationIntentInsertResult
import com.mindtable.bitbuckethelper.application.model.ReminderActionItemProjection
import com.mindtable.bitbuckethelper.application.model.ReminderRepositoryProjection
import com.mindtable.bitbuckethelper.application.model.StoredAcknowledgmentResult
import com.mindtable.bitbuckethelper.application.model.StoredActionItemSnapshot
import com.mindtable.bitbuckethelper.application.model.StoredInstallationConfiguration
import com.mindtable.bitbuckethelper.application.model.StoredNotificationAttempt
import com.mindtable.bitbuckethelper.application.model.StoredNotificationIntent
import com.mindtable.bitbuckethelper.application.model.StoredPullRequestSnapshot
import com.mindtable.bitbuckethelper.application.model.StoredSynchronizationSnapshot
import com.mindtable.bitbuckethelper.domain.shared.ActionItemId
import com.mindtable.bitbuckethelper.domain.shared.ActivityVersion
import com.mindtable.bitbuckethelper.domain.shared.NotificationIntentId
import com.mindtable.bitbuckethelper.domain.shared.PullRequestId
import com.mindtable.bitbuckethelper.domain.shared.RepositoryId
import java.time.Instant

interface ConfigurationStore {
    suspend fun find(): StoredInstallationConfiguration?
    suspend fun save(configuration: StoredInstallationConfiguration)
}

interface PullRequestStore {
    suspend fun find(id: PullRequestId): StoredPullRequestSnapshot?
    suspend fun listByRepository(repositoryId: RepositoryId, includeInactive: Boolean): List<StoredPullRequestSnapshot>
    suspend fun save(snapshot: StoredPullRequestSnapshot)
    suspend fun markMissingInactive(
        repositoryId: RepositoryId,
        activePullRequestIds: Set<PullRequestId>,
        authoritativeAt: Instant,
    ): List<StoredPullRequestSnapshot>
    suspend fun markInactive(id: PullRequestId, inactiveAt: Instant)
    suspend fun listInactiveBefore(cutoff: Instant): List<StoredPullRequestSnapshot>
    suspend fun delete(id: PullRequestId)
}

interface ActionItemStore {
    suspend fun find(id: ActionItemId): StoredActionItemSnapshot?
    suspend fun listByPullRequest(pullRequestId: PullRequestId): List<StoredActionItemSnapshot>
    suspend fun listActionable(): List<StoredActionItemSnapshot>
    suspend fun save(snapshot: StoredActionItemSnapshot)
    suspend fun acknowledge(
        id: ActionItemId,
        expectedVersion: ActivityVersion,
        acknowledgedAt: Instant,
    ): StoredAcknowledgmentResult
    suspend fun deleteByPullRequest(pullRequestId: PullRequestId)
}

interface SynchronizationCheckpointStore {
    suspend fun find(repositoryId: RepositoryId): StoredSynchronizationSnapshot?
    suspend fun list(): List<StoredSynchronizationSnapshot>
    suspend fun save(snapshot: StoredSynchronizationSnapshot)
}

interface NotificationIntentStore {
    suspend fun insertIfAbsent(intent: StoredNotificationIntent): NotificationIntentInsertResult
    suspend fun find(id: NotificationIntentId): StoredNotificationIntent?
    suspend fun findDue(now: Instant, limit: Int): List<StoredNotificationIntent>
    suspend fun tryClaim(
        id: NotificationIntentId,
        owner: String,
        acquiredAt: Instant,
        expiresAt: Instant,
    ): StoredNotificationIntent?
    suspend fun releaseClaim(id: NotificationIntentId, owner: String): Boolean
    suspend fun completeAttempt(
        id: NotificationIntentId,
        owner: String,
        completion: NotificationAttemptCompletion,
    ): Boolean
    suspend fun listAttempts(id: NotificationIntentId): List<StoredNotificationAttempt>
}

interface ReminderProjectionStore {
    suspend fun listRepositoriesWithActionableItems(): List<ReminderRepositoryProjection>
    suspend fun listActionableItems(repositoryId: RepositoryId): List<ReminderActionItemProjection>
}

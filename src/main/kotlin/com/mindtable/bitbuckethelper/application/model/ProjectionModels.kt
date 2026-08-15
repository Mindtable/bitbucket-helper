package com.mindtable.bitbuckethelper.application.model

import com.mindtable.bitbuckethelper.domain.shared.ActionItemId
import com.mindtable.bitbuckethelper.domain.shared.ActivityVersion
import com.mindtable.bitbuckethelper.domain.shared.DashboardRevision
import com.mindtable.bitbuckethelper.domain.shared.PullRequestId
import com.mindtable.bitbuckethelper.domain.shared.RepositoryId
import com.mindtable.bitbuckethelper.domain.shared.RepositoryRevision
import java.net.URI
import java.time.Instant

data class ActorProjection(
    val stableId: String,
    val displayName: String,
)

enum class ActionItemState { OPEN, ACKNOWLEDGED, CLOSED }

data class StoredReadinessCheck(
    val name: String,
    val passed: Boolean,
    val safeReason: String?,
)

sealed interface StoredReadiness {
    data class Available(
        val passed: Int,
        val total: Int,
        val checks: List<StoredReadinessCheck>,
    ) : StoredReadiness

    data class Unavailable(val safeReason: String) : StoredReadiness
}

data class StoredBuildObservation(
    val key: String,
    val state: BuildState,
    val observedAt: Instant,
)

data class StoredPullRequestSnapshot(
    val id: PullRequestId,
    val repositoryId: RepositoryId,
    val upstreamNumber: Long,
    val title: String,
    val authorStableId: String,
    val authorDisplayName: String,
    val draft: Boolean,
    val headCommit: String,
    val webUrl: URI,
    val createdAt: Instant,
    val updatedAt: Instant,
    val observedAt: Instant,
    val active: Boolean,
    val inactiveAt: Instant?,
    val readiness: StoredReadiness,
    val builds: List<StoredBuildObservation>,
    val buildsWereGreen: Boolean,
)

data class StoredActionItemSnapshot(
    val id: ActionItemId,
    val pullRequestId: PullRequestId,
    val repositoryId: RepositoryId,
    val sourceKind: String,
    val upstreamSourceId: String,
    val actorStableId: String,
    val actorDisplayName: String,
    val activityAt: Instant,
    val observedAt: Instant,
    val activityVersion: ActivityVersion,
    val state: ActionItemState,
    val acknowledgedVersion: ActivityVersion?,
    val acknowledgedAt: Instant?,
    val webUrl: URI,
)

sealed interface ReadinessProjection {
    data class Available(
        val passed: Int,
        val total: Int,
        val checks: List<ReadinessCheckProjection>,
    ) : ReadinessProjection

    data class Unavailable(val safeReason: String) : ReadinessProjection
}

data class ReadinessCheckProjection(
    val name: String,
    val passed: Boolean,
    val safeReason: String?,
)

enum class BuildState { NO_BUILDS, IN_PROGRESS, SUCCESSFUL, FAILED, UNKNOWN }

data class BuildProjection(
    val key: String,
    val state: BuildState,
)

data class ActionItemProjection(
    val id: ActionItemId,
    val pullRequestId: PullRequestId,
    val repositoryId: RepositoryId,
    val repositoryDisplayName: String,
    val pullRequestNumber: Long,
    val pullRequestTitle: String,
    val activityVersion: ActivityVersion,
    val kind: String,
    val actor: ActorProjection,
    val activityAt: Instant,
    val state: ActionItemState,
    val acknowledgedAt: Instant?,
    val webUrl: URI,
)

data class PullRequestCardProjection(
    val id: PullRequestId,
    val repositoryId: RepositoryId,
    val upstreamNumber: Long,
    val title: String,
    val author: ActorProjection,
    val draft: Boolean,
    val createdAt: Instant,
    val updatedAt: Instant,
    val webUrl: URI,
    val readiness: ReadinessProjection,
    val buildState: BuildState,
    val actionableItemCount: Int,
    val acknowledgedItemCount: Int,
    val actionItems: List<ActionItemProjection>,
)

data class PullRequestDetailProjection(
    val pullRequest: PullRequestCardProjection,
    val headCommit: String,
    val builds: List<BuildProjection>,
    val freshness: Freshness,
)

data class ReadinessSummaryProjection(
    val readyPullRequestCount: Int,
    val availablePullRequestCount: Int,
    val unavailablePullRequestCount: Int,
)

data class RepositoryGroupProjection(
    val repositoryId: RepositoryId,
    val slug: String,
    val displayName: String,
    val webUrl: URI,
    val revision: RepositoryRevision,
    val synchronization: SynchronizationProjection,
    val readinessSummary: ReadinessSummaryProjection,
    val pullRequests: List<PullRequestCardProjection>,
)

data class InboxProjection(val items: List<ActionItemProjection>)

sealed interface DashboardPolling {
    data object Idle : DashboardPolling
    data class Active(val afterMilliseconds: Long) : DashboardPolling
}

data class DashboardSnapshot(
    val revision: DashboardRevision,
    val generatedAt: Instant,
    val workspace: WorkspaceConfigurationProjection,
    val repositoryGroups: List<RepositoryGroupProjection>,
    val inbox: InboxProjection,
    val polling: DashboardPolling,
)

data class GetDashboardSnapshotQuery(val afterRevision: DashboardRevision?)

data class GetPullRequestQuery(val pullRequestId: PullRequestId)

data class GetLiveActivityContentCommand(
    val actionItemId: ActionItemId,
    val activityVersion: ActivityVersion,
)

data class AcknowledgeActionItemCommand(
    val actionItemId: ActionItemId,
    val activityVersion: ActivityVersion,
)

sealed interface DashboardResult {
    data class SnapshotChanged(val snapshot: DashboardSnapshot) : DashboardResult
    data class SnapshotUnchanged(
        val revision: DashboardRevision,
        val serverTime: Instant,
        val polling: DashboardPolling,
    ) : DashboardResult
    data object WorkspaceNotConfigured : DashboardResult
}

sealed interface ListPullRequestsResult {
    data class Available(val repositoryGroups: List<RepositoryGroupProjection>) : ListPullRequestsResult
    data object WorkspaceNotConfigured : ListPullRequestsResult
}

sealed interface GetPullRequestResult {
    data class Found(val pullRequest: PullRequestDetailProjection) : GetPullRequestResult
    data class PullRequestNotFound(val pullRequestId: PullRequestId) : GetPullRequestResult
    data object WorkspaceNotConfigured : GetPullRequestResult
}

sealed interface GetInboxResult {
    data class Available(val inbox: InboxProjection) : GetInboxResult
    data object WorkspaceNotConfigured : GetInboxResult
}

enum class LiveContentUnavailableReason {
    AUTHENTICATION,
    AUTHORIZATION,
    RATE_LIMITED,
    TIMEOUT,
    NETWORK,
    UPSTREAM,
    MALFORMED_UPSTREAM,
    DELETED,
}

sealed interface LiveActivityContentResult {
    val actionItemId: ActionItemId
    val requestedVersion: ActivityVersion

    data class ContentAvailable(
        override val actionItemId: ActionItemId,
        override val requestedVersion: ActivityVersion,
        val markdown: String,
        val fetchedAt: Instant,
    ) : LiveActivityContentResult

    data class StaleActivityVersion(
        override val actionItemId: ActionItemId,
        override val requestedVersion: ActivityVersion,
        val current: ActionItemProjection,
    ) : LiveActivityContentResult

    data class NewerActivityObserved(
        override val actionItemId: ActionItemId,
        override val requestedVersion: ActivityVersion,
        val observedVersion: ActivityVersion,
        val repositoryId: RepositoryId,
    ) : LiveActivityContentResult

    data class ContentUnavailable(
        override val actionItemId: ActionItemId,
        override val requestedVersion: ActivityVersion,
        val reason: LiveContentUnavailableReason,
        val retryable: Boolean,
        val retryAt: Instant?,
    ) : LiveActivityContentResult

    data class ActionItemNotFound(
        override val actionItemId: ActionItemId,
        override val requestedVersion: ActivityVersion,
    ) : LiveActivityContentResult
}

sealed interface AcknowledgeActionItemResult {
    val actionItemId: ActionItemId
    val requestedVersion: ActivityVersion

    data class Acknowledged(
        override val actionItemId: ActionItemId,
        override val requestedVersion: ActivityVersion,
        val acknowledgedAt: Instant,
    ) : AcknowledgeActionItemResult

    data class AlreadyAcknowledged(
        override val actionItemId: ActionItemId,
        override val requestedVersion: ActivityVersion,
    ) : AcknowledgeActionItemResult

    data class StaleActivityVersion(
        override val actionItemId: ActionItemId,
        override val requestedVersion: ActivityVersion,
        val current: ActionItemProjection,
    ) : AcknowledgeActionItemResult {
        val hasNewerActivity: Boolean get() = true
    }

    data class AcknowledgmentRejected(
        override val actionItemId: ActionItemId,
        override val requestedVersion: ActivityVersion,
    ) : AcknowledgeActionItemResult

    data class ActionItemNotFound(
        override val actionItemId: ActionItemId,
        override val requestedVersion: ActivityVersion,
    ) : AcknowledgeActionItemResult
}

sealed interface StoredAcknowledgmentResult {
    data class Updated(val snapshot: StoredActionItemSnapshot) : StoredAcknowledgmentResult
    data class AlreadyApplied(val snapshot: StoredActionItemSnapshot) : StoredAcknowledgmentResult
    data class VersionMismatch(val snapshot: StoredActionItemSnapshot) : StoredAcknowledgmentResult
    data class NotActionable(val snapshot: StoredActionItemSnapshot) : StoredAcknowledgmentResult
    data object Missing : StoredAcknowledgmentResult
}

data class ReminderRepositoryProjection(
    val repositoryId: RepositoryId,
    val displayName: String,
    val webUrl: URI,
)

data class ReminderActionItemProjection(
    val actionItemId: ActionItemId,
    val repositoryId: RepositoryId,
    val activityVersion: ActivityVersion,
)

data class PruneInactivePullRequestsResult(
    val prunedPullRequestCount: Int,
    val completedAt: Instant,
)

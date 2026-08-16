package com.mindtable.bitbuckethelper.application.model

import com.mindtable.bitbuckethelper.domain.shared.ActivityVersion
import com.mindtable.bitbuckethelper.domain.shared.RepositoryId
import com.mindtable.bitbuckethelper.domain.shared.WorkspaceId
import java.net.URI
import java.time.Instant

enum class GatewayFailureCategory {
    AUTHENTICATION,
    AUTHORIZATION,
    RATE_LIMITED,
    TIMEOUT,
    NETWORK,
    UPSTREAM,
    MALFORMED_RESPONSE,
    UNSAFE_PAGINATION,
}

data class GatewayFailure(
    val category: GatewayFailureCategory,
    val retryable: Boolean,
    val retryAt: Instant?,
)

sealed interface GatewayResult<out T> {
    data class Success<T>(val value: T) : GatewayResult<T>
    data object NotFound : GatewayResult<Nothing>
    data class Failure(val failure: GatewayFailure) : GatewayResult<Nothing>
}

data class GatewayUserObservation(
    val stableId: String,
    val displayName: String,
    val nickname: String?,
)

data class GatewayWorkspaceObservation(
    val id: WorkspaceId,
    val slug: String,
    val displayName: String,
    val webUrl: URI,
)

data class GatewayRepositoryObservation(
    val id: RepositoryId,
    val workspaceId: WorkspaceId,
    val slug: String,
    val displayName: String,
    val webUrl: URI,
)

data class GatewayRepositoryAddress(
    val id: RepositoryId,
    val apiBaseUrl: URI,
    val workspaceSlug: String,
    val repositorySlug: String,
)

data class GatewayPullRequestSummary(
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
)

data class GatewayPullRequestDetail(
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
    val approvalCount: Int,
    val approvedByStableIds: Set<String>,
    val hasChangesRequested: Boolean,
    val unresolvedCommentCount: Int,
    val destinationBranchIsCurrent: Boolean?,
    val hasMergeConflicts: Boolean?,
)

enum class GatewayBuildStatus { SUCCESSFUL, FAILED, STOPPED, IN_PROGRESS, UNKNOWN }

data class GatewayBuildObservation(
    val key: String,
    val status: GatewayBuildStatus,
    val observedAt: Instant,
)

data class GatewayTaskObservation(
    val key: String,
    val resolved: Boolean,
    val observedAt: Instant,
)

enum class GatewayActivityKind { COMMENT, REPLY, CHANGES_REQUESTED }

data class GatewayActivityObservation(
    val sourceKind: GatewayActivityKind,
    val sourceId: String,
    val actorStableId: String,
    val actorDisplayName: String,
    val activityAt: Instant,
    val activityVersion: ActivityVersion,
    val resolved: Boolean,
    val deleted: Boolean,
    val webUrl: URI,
)

data class GatewayLiveActivityContent(
    val activityVersion: ActivityVersion,
    val markdown: String,
    val fetchedAt: Instant,
) {
    override fun toString(): String =
        "GatewayLiveActivityContent(activityVersion=$activityVersion, markdown=<redacted>, fetchedAt=$fetchedAt)"
}

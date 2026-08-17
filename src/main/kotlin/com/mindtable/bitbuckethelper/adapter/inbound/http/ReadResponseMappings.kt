package com.mindtable.bitbuckethelper.adapter.inbound.http

import com.mindtable.bitbuckethelper.application.model.ActionItemProjection
import com.mindtable.bitbuckethelper.application.model.ActionItemState
import com.mindtable.bitbuckethelper.application.model.ActorProjection
import com.mindtable.bitbuckethelper.application.model.BuildProjection
import com.mindtable.bitbuckethelper.application.model.BuildState
import com.mindtable.bitbuckethelper.application.model.ConfiguredRepositoryProjection
import com.mindtable.bitbuckethelper.application.model.DashboardPolling
import com.mindtable.bitbuckethelper.application.model.DashboardResult
import com.mindtable.bitbuckethelper.application.model.DashboardSnapshot
import com.mindtable.bitbuckethelper.application.model.Freshness
import com.mindtable.bitbuckethelper.application.model.GetInboxResult
import com.mindtable.bitbuckethelper.application.model.GetPullRequestResult
import com.mindtable.bitbuckethelper.application.model.GetSynchronizationStatusResult
import com.mindtable.bitbuckethelper.application.model.InboxProjection
import com.mindtable.bitbuckethelper.application.model.ListPullRequestsResult
import com.mindtable.bitbuckethelper.application.model.PartialFailureMetadata
import com.mindtable.bitbuckethelper.application.model.PullRequestCardProjection
import com.mindtable.bitbuckethelper.application.model.PullRequestDetailProjection
import com.mindtable.bitbuckethelper.application.model.ReadinessCheckProjection
import com.mindtable.bitbuckethelper.application.model.ReadinessProjection
import com.mindtable.bitbuckethelper.application.model.ReadinessSummaryProjection
import com.mindtable.bitbuckethelper.application.model.RepositoryGroupProjection
import com.mindtable.bitbuckethelper.application.model.SynchronizationActivity
import com.mindtable.bitbuckethelper.application.model.SynchronizationAttemptOutcome
import com.mindtable.bitbuckethelper.application.model.SynchronizationFailure
import com.mindtable.bitbuckethelper.application.model.SynchronizationFailureCategory
import com.mindtable.bitbuckethelper.application.model.SynchronizationProblem
import com.mindtable.bitbuckethelper.application.model.SynchronizationProjection
import com.mindtable.bitbuckethelper.application.model.WorkspaceConfigurationProjection
import com.mindtable.bitbuckethelper.generated.api.v1.model.ActionItem
import com.mindtable.bitbuckethelper.generated.api.v1.model.Actor
import com.mindtable.bitbuckethelper.generated.api.v1.model.ApiVersion
import com.mindtable.bitbuckethelper.generated.api.v1.model.Build
import com.mindtable.bitbuckethelper.generated.api.v1.model.ConfiguredRepository
import com.mindtable.bitbuckethelper.generated.api.v1.model.DashboardResponse
import com.mindtable.bitbuckethelper.generated.api.v1.model.DashboardSnapshotChangedResult
import com.mindtable.bitbuckethelper.generated.api.v1.model.DashboardSnapshotUnchangedResult
import com.mindtable.bitbuckethelper.generated.api.v1.model.FreshnessFresh
import com.mindtable.bitbuckethelper.generated.api.v1.model.FreshnessNeverSynchronized
import com.mindtable.bitbuckethelper.generated.api.v1.model.FreshnessStale
import com.mindtable.bitbuckethelper.generated.api.v1.model.Inbox
import com.mindtable.bitbuckethelper.generated.api.v1.model.InboxAvailableResult
import com.mindtable.bitbuckethelper.generated.api.v1.model.InboxResponse
import com.mindtable.bitbuckethelper.generated.api.v1.model.PartialFailure
import com.mindtable.bitbuckethelper.generated.api.v1.model.PollingActive
import com.mindtable.bitbuckethelper.generated.api.v1.model.PollingIdle
import com.mindtable.bitbuckethelper.generated.api.v1.model.PullRequestCard
import com.mindtable.bitbuckethelper.generated.api.v1.model.PullRequestDetail
import com.mindtable.bitbuckethelper.generated.api.v1.model.PullRequestDetailResponse
import com.mindtable.bitbuckethelper.generated.api.v1.model.PullRequestFoundResult
import com.mindtable.bitbuckethelper.generated.api.v1.model.PullRequestListResponse
import com.mindtable.bitbuckethelper.generated.api.v1.model.PullRequestNotFoundResult
import com.mindtable.bitbuckethelper.generated.api.v1.model.PullRequestsAvailableResult
import com.mindtable.bitbuckethelper.generated.api.v1.model.ReadinessAvailable
import com.mindtable.bitbuckethelper.generated.api.v1.model.ReadinessCheck
import com.mindtable.bitbuckethelper.generated.api.v1.model.ReadinessSummary
import com.mindtable.bitbuckethelper.generated.api.v1.model.ReadinessUnavailable
import com.mindtable.bitbuckethelper.generated.api.v1.model.RepositoryGroup
import com.mindtable.bitbuckethelper.generated.api.v1.model.Synchronization
import com.mindtable.bitbuckethelper.generated.api.v1.model.SynchronizationAvailableResult
import com.mindtable.bitbuckethelper.generated.api.v1.model.SynchronizationProblemNone
import com.mindtable.bitbuckethelper.generated.api.v1.model.SynchronizationProblemPresent
import com.mindtable.bitbuckethelper.generated.api.v1.model.SynchronizationResponse
import com.mindtable.bitbuckethelper.generated.api.v1.model.WorkspaceConfiguration
import com.mindtable.bitbuckethelper.generated.api.v1.model.WorkspaceNotConfiguredResult
import com.mindtable.bitbuckethelper.generated.api.v1.model.ActionItemState as GeneratedActionItemState
import com.mindtable.bitbuckethelper.generated.api.v1.model.BuildState as GeneratedBuildState
import com.mindtable.bitbuckethelper.generated.api.v1.model.DashboardSnapshot as GeneratedDashboardSnapshot
import com.mindtable.bitbuckethelper.generated.api.v1.model.Freshness as GeneratedFreshness
import com.mindtable.bitbuckethelper.generated.api.v1.model.Polling as GeneratedPolling
import com.mindtable.bitbuckethelper.generated.api.v1.model.Readiness as GeneratedReadiness
import com.mindtable.bitbuckethelper.generated.api.v1.model.SynchronizationActivity as GeneratedSynchronizationActivity
import com.mindtable.bitbuckethelper.generated.api.v1.model.SynchronizationAttemptOutcome as GeneratedSynchronizationAttemptOutcome
import com.mindtable.bitbuckethelper.generated.api.v1.model.SynchronizationFailure as GeneratedSynchronizationFailure
import com.mindtable.bitbuckethelper.generated.api.v1.model.SynchronizationFailureCategory as GeneratedSynchronizationFailureCategory
import com.mindtable.bitbuckethelper.generated.api.v1.model.SynchronizationProblem as GeneratedSynchronizationProblem

internal fun DashboardResult.toDashboardResponse(requestId: String): DashboardResponse = DashboardResponse(
    apiVersion = ApiVersion._1,
    requestId = requestId,
    result = when (this) {
        is DashboardResult.SnapshotChanged -> DashboardSnapshotChangedResult(snapshot.toGenerated())
        is DashboardResult.SnapshotUnchanged -> DashboardSnapshotUnchangedResult(
            dashboardRevision = revision.value,
            serverTime = serverTime.toString(),
            polling = polling.toGenerated(),
        )
        DashboardResult.WorkspaceNotConfigured -> workspaceNotConfigured()
    },
)

internal fun ListPullRequestsResult.toPullRequestListResponse(requestId: String): PullRequestListResponse =
    PullRequestListResponse(
        apiVersion = ApiVersion._1,
        requestId = requestId,
        result = when (this) {
            is ListPullRequestsResult.Available -> PullRequestsAvailableResult(repositoryGroups.map { it.toGenerated() })
            ListPullRequestsResult.WorkspaceNotConfigured -> workspaceNotConfigured()
        },
    )

internal fun GetPullRequestResult.toPullRequestDetailResponse(requestId: String): PullRequestDetailResponse =
    PullRequestDetailResponse(
        apiVersion = ApiVersion._1,
        requestId = requestId,
        result = when (this) {
            is GetPullRequestResult.Found -> PullRequestFoundResult(pullRequest.toGenerated())
            is GetPullRequestResult.PullRequestNotFound -> PullRequestNotFoundResult(pullRequestId.value)
            GetPullRequestResult.WorkspaceNotConfigured -> workspaceNotConfigured()
        },
    )

internal fun GetInboxResult.toInboxResponse(requestId: String): InboxResponse = InboxResponse(
    apiVersion = ApiVersion._1,
    requestId = requestId,
    result = when (this) {
        is GetInboxResult.Available -> InboxAvailableResult(inbox.toGenerated())
        GetInboxResult.WorkspaceNotConfigured -> workspaceNotConfigured()
    },
)

internal fun GetSynchronizationStatusResult.toSynchronizationResponse(requestId: String): SynchronizationResponse =
    SynchronizationResponse(
        apiVersion = ApiVersion._1,
        requestId = requestId,
        result = when (this) {
            is GetSynchronizationStatusResult.Available ->
                SynchronizationAvailableResult(repositories.map { it.toGenerated() })
            GetSynchronizationStatusResult.WorkspaceNotConfigured -> workspaceNotConfigured()
        },
    )

private fun workspaceNotConfigured() = WorkspaceNotConfiguredResult(
    WorkspaceNotConfiguredResult.SetupCommand.bitbucketMinusHelper_workspace_configure,
)

private fun DashboardSnapshot.toGenerated(): GeneratedDashboardSnapshot = GeneratedDashboardSnapshot(
    dashboardRevision = revision.value,
    generatedAt = generatedAt.toString(),
    workspace = workspace.toGenerated(),
    repositoryGroups = repositoryGroups.map { it.toGenerated() },
    inbox = inbox.toGenerated(),
    polling = polling.toGenerated(),
)

private fun WorkspaceConfigurationProjection.toGenerated(): WorkspaceConfiguration = WorkspaceConfiguration(
    workspaceId = workspaceId.value,
    bitbucketApiBaseUrl = bitbucketApiBaseUrl.toString(),
    workspaceSlug = workspaceSlug,
    workspaceDisplayName = workspaceDisplayName,
    workspaceWebUrl = workspaceWebUrl.toString(),
    retentionDays = retentionDays,
    repositories = repositories.map { it.toGenerated() },
)

private fun ConfiguredRepositoryProjection.toGenerated(): ConfiguredRepository = ConfiguredRepository(
    repositoryId = repositoryId.value,
    slug = slug,
    displayName = displayName,
    webUrl = webUrl.toString(),
)

private fun RepositoryGroupProjection.toGenerated(): RepositoryGroup = RepositoryGroup(
    repositoryId = repositoryId.value,
    slug = slug,
    displayName = displayName,
    webUrl = webUrl.toString(),
    repositoryRevision = revision.value,
    synchronization = synchronization.toGenerated(),
    readinessSummary = readinessSummary.toGenerated(),
    pullRequests = pullRequests.map { it.toGenerated() },
)

private fun ReadinessSummaryProjection.toGenerated(): ReadinessSummary = ReadinessSummary(
    readyPullRequestCount = readyPullRequestCount,
    availablePullRequestCount = availablePullRequestCount,
    unavailablePullRequestCount = unavailablePullRequestCount,
)

private fun PullRequestCardProjection.toGenerated(): PullRequestCard = PullRequestCard(
    pullRequestId = id.value,
    repositoryId = repositoryId.value,
    upstreamNumber = upstreamNumber,
    title = title,
    author = author.toGenerated(),
    draft = draft,
    createdAt = createdAt.toString(),
    updatedAt = updatedAt.toString(),
    webUrl = webUrl.toString(),
    readiness = readiness.toGenerated(),
    buildState = buildState.toGenerated(),
    actionableItemCount = actionableItemCount,
    acknowledgedItemCount = acknowledgedItemCount,
    actionItems = actionItems.map { it.toGenerated() },
)

private fun PullRequestDetailProjection.toGenerated(): PullRequestDetail = PullRequestDetail(
    pullRequest = pullRequest.toGenerated(),
    headCommit = headCommit,
    builds = builds.map { it.toGenerated() },
    freshness = freshness.toGenerated(),
)

private fun ActorProjection.toGenerated(): Actor = Actor(
    stableId = stableId,
    displayName = displayName,
)

private fun ReadinessProjection.toGenerated(): GeneratedReadiness = when (this) {
    is ReadinessProjection.Available -> ReadinessAvailable(
        passed = passed,
        total = total,
        checks = checks.map { it.toGenerated() },
    )
    is ReadinessProjection.Unavailable -> ReadinessUnavailable(safeReason)
}

private fun ReadinessCheckProjection.toGenerated(): ReadinessCheck = ReadinessCheck(
    name = name,
    passed = passed,
    safeReason = safeReason,
)

private fun BuildProjection.toGenerated(): Build = Build(
    key = key,
    state = state.toGenerated(),
)

private fun ActionItemProjection.toGenerated(): ActionItem = ActionItem(
    actionItemId = id.value,
    pullRequestId = pullRequestId.value,
    repositoryId = repositoryId.value,
    repositoryDisplayName = repositoryDisplayName,
    pullRequestNumber = pullRequestNumber,
    pullRequestTitle = pullRequestTitle,
    activityVersion = activityVersion.value,
    kind = kind,
    actor = actor.toGenerated(),
    activityAt = activityAt.toString(),
    state = state.toGenerated(),
    acknowledgedAt = acknowledgedAt?.toString(),
    webUrl = webUrl.toString(),
)

private fun InboxProjection.toGenerated(): Inbox = Inbox(items.map { it.toGenerated() })

private fun SynchronizationProjection.toGenerated(): Synchronization = Synchronization(
    repositoryId = repositoryId.value,
    activity = activity.toGenerated(),
    lastAttemptAt = lastAttemptAt?.toString(),
    lastAttemptOutcome = lastAttemptOutcome?.toGenerated(),
    lastSuccessAt = lastSuccessAt?.toString(),
    freshness = freshness.toGenerated(),
    problem = problem.toGenerated(),
)

private fun Freshness.toGenerated(): GeneratedFreshness = when (this) {
    Freshness.NeverSynchronized -> FreshnessNeverSynchronized()
    is Freshness.Fresh -> FreshnessFresh(snapshotAt.toString(), age.toMillis())
    is Freshness.Stale -> FreshnessStale(snapshotAt.toString(), age.toMillis(), staleSince.toString())
}

private fun SynchronizationProblem.toGenerated(): GeneratedSynchronizationProblem = when (this) {
    SynchronizationProblem.None -> SynchronizationProblemNone()
    is SynchronizationProblem.Present -> SynchronizationProblemPresent(metadata.toGenerated())
}

private fun PartialFailureMetadata.toGenerated(): PartialFailure = PartialFailure(
    attemptedCount = attemptedCount,
    succeededCount = succeededCount,
    failedCount = failedCount,
    failures = failures.map { it.toGenerated() },
)

private fun SynchronizationFailure.toGenerated(): GeneratedSynchronizationFailure = GeneratedSynchronizationFailure(
    category = category.toGenerated(),
    retryable = retryable,
    retryAt = retryAt?.toString(),
)

private fun DashboardPolling.toGenerated(): GeneratedPolling = when (this) {
    DashboardPolling.Idle -> PollingIdle()
    is DashboardPolling.Active -> PollingActive(afterMilliseconds)
}

private fun ActionItemState.toGenerated(): GeneratedActionItemState = when (this) {
    ActionItemState.OPEN -> GeneratedActionItemState.`open`
    ActionItemState.ACKNOWLEDGED -> GeneratedActionItemState.acknowledged
    ActionItemState.CLOSED -> GeneratedActionItemState.closed
}

private fun BuildState.toGenerated(): GeneratedBuildState = when (this) {
    BuildState.NO_BUILDS -> GeneratedBuildState.noBuilds
    BuildState.IN_PROGRESS -> GeneratedBuildState.inProgress
    BuildState.SUCCESSFUL -> GeneratedBuildState.successful
    BuildState.FAILED -> GeneratedBuildState.failed
    BuildState.UNKNOWN -> GeneratedBuildState.unknown
}

private fun SynchronizationActivity.toGenerated(): GeneratedSynchronizationActivity = when (this) {
    SynchronizationActivity.IDLE -> GeneratedSynchronizationActivity.idle
    SynchronizationActivity.QUEUED -> GeneratedSynchronizationActivity.queued
    SynchronizationActivity.RUNNING -> GeneratedSynchronizationActivity.running
}

private fun SynchronizationAttemptOutcome.toGenerated(): GeneratedSynchronizationAttemptOutcome = when (this) {
    SynchronizationAttemptOutcome.SUCCEEDED -> GeneratedSynchronizationAttemptOutcome.succeeded
    SynchronizationAttemptOutcome.PARTIAL_FAILURE -> GeneratedSynchronizationAttemptOutcome.partialFailure
    SynchronizationAttemptOutcome.FAILED -> GeneratedSynchronizationAttemptOutcome.failed
}

private fun SynchronizationFailureCategory.toGenerated(): GeneratedSynchronizationFailureCategory = when (this) {
    SynchronizationFailureCategory.AUTHENTICATION -> GeneratedSynchronizationFailureCategory.authentication
    SynchronizationFailureCategory.AUTHORIZATION -> GeneratedSynchronizationFailureCategory.authorization
    SynchronizationFailureCategory.RATE_LIMITED -> GeneratedSynchronizationFailureCategory.rateLimited
    SynchronizationFailureCategory.TIMEOUT -> GeneratedSynchronizationFailureCategory.timeout
    SynchronizationFailureCategory.NETWORK -> GeneratedSynchronizationFailureCategory.network
    SynchronizationFailureCategory.UPSTREAM -> GeneratedSynchronizationFailureCategory.upstream
    SynchronizationFailureCategory.MALFORMED_UPSTREAM -> GeneratedSynchronizationFailureCategory.malformedUpstream
}

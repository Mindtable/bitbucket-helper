package com.mindtable.bitbuckethelper.adapter.inbound.http

import com.mindtable.bitbuckethelper.application.model.AcknowledgeActionItemResult
import com.mindtable.bitbuckethelper.application.model.ActionItemProjection
import com.mindtable.bitbuckethelper.application.model.ActionItemState
import com.mindtable.bitbuckethelper.application.model.AddRepositoryResult
import com.mindtable.bitbuckethelper.application.model.ConfigureWorkspaceResult
import com.mindtable.bitbuckethelper.application.model.ConfiguredRepositoryProjection
import com.mindtable.bitbuckethelper.application.model.GatewayFailure
import com.mindtable.bitbuckethelper.application.model.GatewayFailureCategory
import com.mindtable.bitbuckethelper.application.model.GetRefreshRunResult
import com.mindtable.bitbuckethelper.application.model.GetWorkspaceConfigurationResult
import com.mindtable.bitbuckethelper.application.model.LiveActivityContentResult
import com.mindtable.bitbuckethelper.application.model.LiveContentUnavailableReason
import com.mindtable.bitbuckethelper.application.model.PartialFailureMetadata
import com.mindtable.bitbuckethelper.application.model.RefreshRegistrationDisposition
import com.mindtable.bitbuckethelper.application.model.RefreshRunRepositoryEntry
import com.mindtable.bitbuckethelper.application.model.RefreshRunSnapshot
import com.mindtable.bitbuckethelper.application.model.RemoveRepositoryResult
import com.mindtable.bitbuckethelper.application.model.StartRefreshRunResult
import com.mindtable.bitbuckethelper.application.model.SynchronizationFailure
import com.mindtable.bitbuckethelper.application.model.SynchronizationFailureCategory
import com.mindtable.bitbuckethelper.application.model.WorkspaceConfigurationProjection
import com.mindtable.bitbuckethelper.generated.api.v1.model.AcknowledgeActionItemResponse
import com.mindtable.bitbuckethelper.generated.api.v1.model.AcknowledgedResult
import com.mindtable.bitbuckethelper.generated.api.v1.model.AcknowledgmentRejectedResult
import com.mindtable.bitbuckethelper.generated.api.v1.model.AcknowledgmentStaleActivityVersionResult
import com.mindtable.bitbuckethelper.generated.api.v1.model.ActionItem
import com.mindtable.bitbuckethelper.generated.api.v1.model.ActionItemNotFoundResult
import com.mindtable.bitbuckethelper.generated.api.v1.model.ActivePollingAdvice
import com.mindtable.bitbuckethelper.generated.api.v1.model.Actor
import com.mindtable.bitbuckethelper.generated.api.v1.model.AddRepositoryResponse
import com.mindtable.bitbuckethelper.generated.api.v1.model.AlreadyAcknowledgedResult
import com.mindtable.bitbuckethelper.generated.api.v1.model.ApiVersion
import com.mindtable.bitbuckethelper.generated.api.v1.model.ConfigureWorkspaceResponse
import com.mindtable.bitbuckethelper.generated.api.v1.model.ConfiguredRepository
import com.mindtable.bitbuckethelper.generated.api.v1.model.ContentAvailableResult
import com.mindtable.bitbuckethelper.generated.api.v1.model.ContentUnavailableResult
import com.mindtable.bitbuckethelper.generated.api.v1.model.GetRefreshRunResponse
import com.mindtable.bitbuckethelper.generated.api.v1.model.GetWorkspaceConfigurationResponse
import com.mindtable.bitbuckethelper.generated.api.v1.model.LiveActivityContentResponse
import com.mindtable.bitbuckethelper.generated.api.v1.model.LiveStaleActivityVersionResult
import com.mindtable.bitbuckethelper.generated.api.v1.model.NewerActivityObservedResult
import com.mindtable.bitbuckethelper.generated.api.v1.model.NoRepositoriesConfiguredResult
import com.mindtable.bitbuckethelper.generated.api.v1.model.PartialFailure
import com.mindtable.bitbuckethelper.generated.api.v1.model.RefreshDeferredByBackoffDisposition
import com.mindtable.bitbuckethelper.generated.api.v1.model.RefreshDeferredByBackoffRepository
import com.mindtable.bitbuckethelper.generated.api.v1.model.RefreshFailedRepository
import com.mindtable.bitbuckethelper.generated.api.v1.model.RefreshJoinedExistingDisposition
import com.mindtable.bitbuckethelper.generated.api.v1.model.RefreshPartialFailureRepository
import com.mindtable.bitbuckethelper.generated.api.v1.model.RefreshQueuedRepository
import com.mindtable.bitbuckethelper.generated.api.v1.model.RefreshRepositoryNotConfiguredDisposition
import com.mindtable.bitbuckethelper.generated.api.v1.model.RefreshRun
import com.mindtable.bitbuckethelper.generated.api.v1.model.RefreshRunCompletedResult
import com.mindtable.bitbuckethelper.generated.api.v1.model.RefreshRunInProgressResult
import com.mindtable.bitbuckethelper.generated.api.v1.model.RefreshRunRegisteredResult
import com.mindtable.bitbuckethelper.generated.api.v1.model.RefreshRunUnavailableResult
import com.mindtable.bitbuckethelper.generated.api.v1.model.RefreshRunningRepository
import com.mindtable.bitbuckethelper.generated.api.v1.model.RefreshStartedDisposition
import com.mindtable.bitbuckethelper.generated.api.v1.model.RefreshSucceededRepository
import com.mindtable.bitbuckethelper.generated.api.v1.model.RemoveRepositoryResponse
import com.mindtable.bitbuckethelper.generated.api.v1.model.RepositoryAddedResult
import com.mindtable.bitbuckethelper.generated.api.v1.model.RepositoryAlreadyConfiguredResult
import com.mindtable.bitbuckethelper.generated.api.v1.model.RepositoryNotConfiguredResult
import com.mindtable.bitbuckethelper.generated.api.v1.model.RepositoryNotFoundResult
import com.mindtable.bitbuckethelper.generated.api.v1.model.RepositoryRemovedResult
import com.mindtable.bitbuckethelper.generated.api.v1.model.RepositoryResolutionUnavailableResult
import com.mindtable.bitbuckethelper.generated.api.v1.model.StartRefreshRunResponse
import com.mindtable.bitbuckethelper.generated.api.v1.model.WorkspaceAlreadyConfiguredResult
import com.mindtable.bitbuckethelper.generated.api.v1.model.WorkspaceConfiguration
import com.mindtable.bitbuckethelper.generated.api.v1.model.WorkspaceConfigurationAvailableResult
import com.mindtable.bitbuckethelper.generated.api.v1.model.WorkspaceConfiguredResult
import com.mindtable.bitbuckethelper.generated.api.v1.model.WorkspaceIdentityMismatchResult
import com.mindtable.bitbuckethelper.generated.api.v1.model.WorkspaceNotConfiguredResult
import com.mindtable.bitbuckethelper.generated.api.v1.model.WorkspaceNotFoundResult
import com.mindtable.bitbuckethelper.generated.api.v1.model.WorkspaceResolutionUnavailableResult
import com.mindtable.bitbuckethelper.generated.api.v1.model.ActionItemState as GeneratedActionItemState
import com.mindtable.bitbuckethelper.generated.api.v1.model.GatewayFailure as GeneratedGatewayFailure
import com.mindtable.bitbuckethelper.generated.api.v1.model.GatewayFailureCategory as GeneratedGatewayFailureCategory
import com.mindtable.bitbuckethelper.generated.api.v1.model.LiveContentUnavailableReason as GeneratedLiveContentUnavailableReason
import com.mindtable.bitbuckethelper.generated.api.v1.model.SynchronizationFailure as GeneratedSynchronizationFailure
import com.mindtable.bitbuckethelper.generated.api.v1.model.SynchronizationFailureCategory as GeneratedSynchronizationFailureCategory

internal fun LiveActivityContentResult.toLiveActivityContentResponse(requestId: String): LiveActivityContentResponse =
    LiveActivityContentResponse(
        apiVersion = ApiVersion._1,
        requestId = requestId,
        result = when (this) {
            is LiveActivityContentResult.ContentAvailable -> ContentAvailableResult(
                actionItemId = actionItemId.value,
                requestedVersion = requestedVersion.value,
                markdown = markdown,
                fetchedAt = fetchedAt.toString(),
            )
            is LiveActivityContentResult.StaleActivityVersion -> LiveStaleActivityVersionResult(
                actionItemId = actionItemId.value,
                requestedVersion = requestedVersion.value,
                current = current.toGenerated(),
            )
            is LiveActivityContentResult.NewerActivityObserved -> NewerActivityObservedResult(
                actionItemId = actionItemId.value,
                requestedVersion = requestedVersion.value,
                observedVersion = observedVersion.value,
                repositoryId = repositoryId.value,
            )
            is LiveActivityContentResult.ContentUnavailable -> ContentUnavailableResult(
                actionItemId = actionItemId.value,
                requestedVersion = requestedVersion.value,
                reason = reason.toGenerated(),
                retryable = retryable,
                retryAt = retryAt?.toString(),
            )
            is LiveActivityContentResult.ActionItemNotFound -> ActionItemNotFoundResult(
                actionItemId.value,
                requestedVersion.value,
            )
        },
    )

internal fun AcknowledgeActionItemResult.toAcknowledgeActionItemResponse(
    requestId: String,
): AcknowledgeActionItemResponse = AcknowledgeActionItemResponse(
    apiVersion = ApiVersion._1,
    requestId = requestId,
    result = when (this) {
        is AcknowledgeActionItemResult.Acknowledged -> AcknowledgedResult(
            actionItemId.value,
            requestedVersion.value,
            acknowledgedAt.toString(),
        )
        is AcknowledgeActionItemResult.AlreadyAcknowledged -> AlreadyAcknowledgedResult(
            actionItemId.value,
            requestedVersion.value,
        )
        is AcknowledgeActionItemResult.StaleActivityVersion -> AcknowledgmentStaleActivityVersionResult(
            actionItemId.value,
            requestedVersion.value,
            hasNewerActivity,
            current.toGenerated(),
        )
        is AcknowledgeActionItemResult.AcknowledgmentRejected -> AcknowledgmentRejectedResult(
            actionItemId.value,
            requestedVersion.value,
        )
        is AcknowledgeActionItemResult.ActionItemNotFound -> ActionItemNotFoundResult(
            actionItemId.value,
            requestedVersion.value,
        )
    },
)

internal fun StartRefreshRunResult.toStartRefreshRunResponse(requestId: String): StartRefreshRunResponse =
    StartRefreshRunResponse(
        apiVersion = ApiVersion._1,
        requestId = requestId,
        result = when (this) {
            StartRefreshRunResult.WorkspaceNotConfigured -> workspaceNotConfigured()
            StartRefreshRunResult.NoRepositoriesConfigured -> NoRepositoriesConfiguredResult()
            is StartRefreshRunResult.RefreshRunRegistered -> RefreshRunRegisteredResult(
                refreshRun.toGenerated(),
                dispositions.map { it.toGenerated() },
            )
        },
    )

internal fun GetRefreshRunResult.toGetRefreshRunResponse(requestId: String): GetRefreshRunResponse =
    GetRefreshRunResponse(
        apiVersion = ApiVersion._1,
        requestId = requestId,
        result = when (this) {
            is GetRefreshRunResult.RefreshRunInProgress -> RefreshRunInProgressResult(
                refreshRun = refreshRun.toGenerated(),
                polling = ActivePollingAdvice(
                    type = ActivePollingAdvice.Type.active,
                    afterMilliseconds = polling.afterMilliseconds,
                ),
            )
            is GetRefreshRunResult.RefreshRunCompleted -> RefreshRunCompletedResult(refreshRun.toGenerated())
            is GetRefreshRunResult.RefreshRunUnavailable -> RefreshRunUnavailableResult(refreshRunId.value)
        },
    )

internal fun GetWorkspaceConfigurationResult.toGetWorkspaceConfigurationResponse(
    requestId: String,
): GetWorkspaceConfigurationResponse = GetWorkspaceConfigurationResponse(
    apiVersion = ApiVersion._1,
    requestId = requestId,
    result = when (this) {
        is GetWorkspaceConfigurationResult.Configured ->
            WorkspaceConfigurationAvailableResult(configuration.toGenerated())
        GetWorkspaceConfigurationResult.WorkspaceNotConfigured -> workspaceNotConfigured()
    },
)

internal fun ConfigureWorkspaceResult.toConfigureWorkspaceResponse(requestId: String): ConfigureWorkspaceResponse =
    ConfigureWorkspaceResponse(
        apiVersion = ApiVersion._1,
        requestId = requestId,
        result = when (this) {
            is ConfigureWorkspaceResult.WorkspaceConfigured -> WorkspaceConfiguredResult(configuration.toGenerated())
            is ConfigureWorkspaceResult.WorkspaceAlreadyConfigured ->
                WorkspaceAlreadyConfiguredResult(configuration.toGenerated())
            is ConfigureWorkspaceResult.WorkspaceIdentityMismatch ->
                WorkspaceIdentityMismatchResult(current.toGenerated())
            ConfigureWorkspaceResult.WorkspaceNotFound -> WorkspaceNotFoundResult()
            is ConfigureWorkspaceResult.WorkspaceResolutionUnavailable ->
                WorkspaceResolutionUnavailableResult(failure.toGenerated())
        },
    )

internal fun AddRepositoryResult.toAddRepositoryResponse(requestId: String): AddRepositoryResponse =
    AddRepositoryResponse(
        apiVersion = ApiVersion._1,
        requestId = requestId,
        result = when (this) {
            is AddRepositoryResult.RepositoryAdded -> RepositoryAddedResult(repository.toGenerated())
            is AddRepositoryResult.RepositoryAlreadyConfigured ->
                RepositoryAlreadyConfiguredResult(repository.toGenerated())
            AddRepositoryResult.RepositoryNotFound -> RepositoryNotFoundResult()
            is AddRepositoryResult.RepositoryResolutionUnavailable ->
                RepositoryResolutionUnavailableResult(failure.toGenerated())
            AddRepositoryResult.WorkspaceNotConfigured -> workspaceNotConfigured()
        },
    )

internal fun RemoveRepositoryResult.toRemoveRepositoryResponse(requestId: String): RemoveRepositoryResponse =
    RemoveRepositoryResponse(
        apiVersion = ApiVersion._1,
        requestId = requestId,
        result = when (this) {
            is RemoveRepositoryResult.RepositoryRemoved -> RepositoryRemovedResult(repositoryId.value)
            is RemoveRepositoryResult.RepositoryNotConfigured -> RepositoryNotConfiguredResult(repositoryId.value)
        },
    )

private fun workspaceNotConfigured() = WorkspaceNotConfiguredResult(
    WorkspaceNotConfiguredResult.SetupCommand.bitbucketMinusHelper_workspace_configure,
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
    actor = Actor(actor.stableId, actor.displayName),
    activityAt = activityAt.toString(),
    state = state.toGenerated(),
    acknowledgedAt = acknowledgedAt?.toString(),
    webUrl = webUrl.toString(),
)

private fun RefreshRunSnapshot.toGenerated(): RefreshRun = RefreshRun(
    refreshRunId = id.value,
    createdAt = createdAt.toString(),
    expiresAt = expiresAt.toString(),
    repositories = repositories.map { it.toGenerated() },
)

private fun RefreshRunRepositoryEntry.toGenerated() = when (this) {
    is RefreshRunRepositoryEntry.Queued -> RefreshQueuedRepository(repositoryId.value)
    is RefreshRunRepositoryEntry.Running -> RefreshRunningRepository(repositoryId.value)
    is RefreshRunRepositoryEntry.Succeeded -> RefreshSucceededRepository(repositoryId.value, completedAt.toString())
    is RefreshRunRepositoryEntry.PartiallySucceeded -> RefreshPartialFailureRepository(
        repositoryId.value,
        completedAt.toString(),
        partialFailure.toGenerated(),
    )
    is RefreshRunRepositoryEntry.Failed -> RefreshFailedRepository(
        repositoryId.value,
        completedAt.toString(),
        failure.toGenerated(),
    )
    is RefreshRunRepositoryEntry.DeferredByBackoff ->
        RefreshDeferredByBackoffRepository(repositoryId.value, retryAt.toString())
}

private fun RefreshRegistrationDisposition.toGenerated() = when (this) {
    is RefreshRegistrationDisposition.Started -> RefreshStartedDisposition(repositoryId.value)
    is RefreshRegistrationDisposition.JoinedExisting -> RefreshJoinedExistingDisposition(repositoryId.value)
    is RefreshRegistrationDisposition.DeferredByBackoff ->
        RefreshDeferredByBackoffDisposition(repositoryId.value, retryAt.toString())
    is RefreshRegistrationDisposition.RepositoryNotConfigured ->
        RefreshRepositoryNotConfiguredDisposition(repositoryId.value)
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

private fun GatewayFailure.toGenerated(): GeneratedGatewayFailure = GeneratedGatewayFailure(
    category = category.toGenerated(),
    retryable = retryable,
    retryAt = retryAt?.toString(),
)

private fun ActionItemState.toGenerated(): GeneratedActionItemState = when (this) {
    ActionItemState.OPEN -> GeneratedActionItemState.`open`
    ActionItemState.ACKNOWLEDGED -> GeneratedActionItemState.acknowledged
    ActionItemState.CLOSED -> GeneratedActionItemState.closed
}

private fun LiveContentUnavailableReason.toGenerated(): GeneratedLiveContentUnavailableReason = when (this) {
    LiveContentUnavailableReason.AUTHENTICATION -> GeneratedLiveContentUnavailableReason.authentication
    LiveContentUnavailableReason.AUTHORIZATION -> GeneratedLiveContentUnavailableReason.authorization
    LiveContentUnavailableReason.RATE_LIMITED -> GeneratedLiveContentUnavailableReason.rateLimited
    LiveContentUnavailableReason.TIMEOUT -> GeneratedLiveContentUnavailableReason.timeout
    LiveContentUnavailableReason.NETWORK -> GeneratedLiveContentUnavailableReason.network
    LiveContentUnavailableReason.UPSTREAM -> GeneratedLiveContentUnavailableReason.upstream
    LiveContentUnavailableReason.MALFORMED_UPSTREAM -> GeneratedLiveContentUnavailableReason.malformedUpstream
    LiveContentUnavailableReason.DELETED -> GeneratedLiveContentUnavailableReason.deleted
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

private fun GatewayFailureCategory.toGenerated(): GeneratedGatewayFailureCategory = when (this) {
    GatewayFailureCategory.AUTHENTICATION -> GeneratedGatewayFailureCategory.authentication
    GatewayFailureCategory.AUTHORIZATION -> GeneratedGatewayFailureCategory.authorization
    GatewayFailureCategory.RATE_LIMITED -> GeneratedGatewayFailureCategory.rateLimited
    GatewayFailureCategory.TIMEOUT -> GeneratedGatewayFailureCategory.timeout
    GatewayFailureCategory.NETWORK -> GeneratedGatewayFailureCategory.network
    GatewayFailureCategory.UPSTREAM -> GeneratedGatewayFailureCategory.upstream
    GatewayFailureCategory.MALFORMED_RESPONSE -> GeneratedGatewayFailureCategory.malformedResponse
    GatewayFailureCategory.UNSAFE_PAGINATION -> GeneratedGatewayFailureCategory.unsafePagination
}

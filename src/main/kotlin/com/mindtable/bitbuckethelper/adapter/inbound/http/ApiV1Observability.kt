package com.mindtable.bitbuckethelper.adapter.inbound.http

import com.mindtable.bitbuckethelper.application.model.AcknowledgeActionItemResult
import com.mindtable.bitbuckethelper.application.model.AddRepositoryResult
import com.mindtable.bitbuckethelper.application.model.ConfigureWorkspaceResult
import com.mindtable.bitbuckethelper.application.model.DashboardResult
import com.mindtable.bitbuckethelper.application.model.GetInboxResult
import com.mindtable.bitbuckethelper.application.model.GetPullRequestResult
import com.mindtable.bitbuckethelper.application.model.GetRefreshRunResult
import com.mindtable.bitbuckethelper.application.model.GetSynchronizationStatusResult
import com.mindtable.bitbuckethelper.application.model.GetWorkspaceConfigurationResult
import com.mindtable.bitbuckethelper.application.model.HealthSnapshot
import com.mindtable.bitbuckethelper.application.model.ListPullRequestsResult
import com.mindtable.bitbuckethelper.application.model.LiveActivityContentResult
import com.mindtable.bitbuckethelper.application.model.RemoveRepositoryResult
import com.mindtable.bitbuckethelper.application.model.StartRefreshRunResult
import com.mindtable.bitbuckethelper.domain.shared.ActionItemId
import com.mindtable.bitbuckethelper.domain.shared.PullRequestId
import com.mindtable.bitbuckethelper.domain.shared.RefreshRunId
import com.mindtable.bitbuckethelper.domain.shared.RepositoryId
import com.mindtable.bitbuckethelper.observability.BackendEventRecorder
import com.mindtable.bitbuckethelper.observability.BackendLogEvent
import com.mindtable.bitbuckethelper.observability.MonotonicTimeSource
import io.ktor.http.HttpMethod
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.call
import io.ktor.server.application.hooks.ResponseSent
import io.ktor.server.request.httpMethod
import io.ktor.server.request.path
import io.ktor.util.AttributeKey

/** Fixed operation names for the public API V1 route surface. */
internal enum class ApiOperation(
    val wireName: String,
    val mutation: Boolean,
) {
    BROWSER_SESSION("browser_session", mutation = false),
    HEALTH("health", mutation = false),
    GET_DASHBOARD("get_dashboard", mutation = false),
    LIST_PULL_REQUESTS("list_pull_requests", mutation = false),
    GET_PULL_REQUEST("get_pull_request", mutation = false),
    GET_INBOX("get_inbox", mutation = false),
    GET_SYNCHRONIZATION("get_synchronization", mutation = false),
    GET_LIVE_ACTIVITY_CONTENT("get_live_activity_content", mutation = false),
    ACKNOWLEDGE_ACTION_ITEM("acknowledge_action_item", mutation = true),
    START_REFRESH_RUN("start_refresh_run", mutation = true),
    GET_REFRESH_RUN("get_refresh_run", mutation = false),
    GET_WORKSPACE_CONFIGURATION("get_workspace_configuration", mutation = false),
    CONFIGURE_WORKSPACE("configure_workspace", mutation = true),
    ADD_REPOSITORY("add_repository", mutation = true),
    REMOVE_REPOSITORY("remove_repository", mutation = true),
    ROUTE_NOT_FOUND("route_not_found", mutation = false),
}

/** Fixed result names matching the generated API V1 discriminators. */
internal enum class ApiOutcome(val wireName: String) {
    BROWSER_SESSION("browser_session"),
    HEALTH_SNAPSHOT("health_snapshot"),
    SNAPSHOT_CHANGED("snapshot_changed"),
    SNAPSHOT_UNCHANGED("snapshot_unchanged"),
    PULL_REQUESTS_AVAILABLE("pull_requests_available"),
    PULL_REQUEST_FOUND("pull_request_found"),
    PULL_REQUEST_NOT_FOUND("pull_request_not_found"),
    INBOX_AVAILABLE("inbox_available"),
    SYNCHRONIZATION_AVAILABLE("synchronization_available"),
    CONTENT_AVAILABLE("content_available"),
    STALE_ACTIVITY_VERSION("stale_activity_version"),
    NEWER_ACTIVITY_OBSERVED("newer_activity_observed"),
    CONTENT_UNAVAILABLE("content_unavailable"),
    ACTION_ITEM_NOT_FOUND("action_item_not_found"),
    ACKNOWLEDGED("acknowledged"),
    ALREADY_ACKNOWLEDGED("already_acknowledged"),
    ACKNOWLEDGMENT_REJECTED("acknowledgment_rejected"),
    WORKSPACE_NOT_CONFIGURED("workspace_not_configured"),
    NO_REPOSITORIES_CONFIGURED("no_repositories_configured"),
    REFRESH_RUN_REGISTERED("refresh_run_registered"),
    REFRESH_RUN_IN_PROGRESS("refresh_run_in_progress"),
    REFRESH_RUN_COMPLETED("refresh_run_completed"),
    REFRESH_RUN_UNAVAILABLE("refresh_run_unavailable"),
    WORKSPACE_CONFIGURATION_AVAILABLE("workspace_configuration_available"),
    WORKSPACE_CONFIGURED("workspace_configured"),
    WORKSPACE_ALREADY_CONFIGURED("workspace_already_configured"),
    WORKSPACE_IDENTITY_MISMATCH("workspace_identity_mismatch"),
    WORKSPACE_NOT_FOUND("workspace_not_found"),
    WORKSPACE_RESOLUTION_UNAVAILABLE("workspace_resolution_unavailable"),
    REPOSITORY_ADDED("repository_added"),
    REPOSITORY_ALREADY_CONFIGURED("repository_already_configured"),
    REPOSITORY_NOT_FOUND("repository_not_found"),
    REPOSITORY_RESOLUTION_UNAVAILABLE("repository_resolution_unavailable"),
    REPOSITORY_REMOVED("repository_removed"),
    REPOSITORY_NOT_CONFIGURED("repository_not_configured"),
}

internal data class ApiV1OutcomeData(
    val outcome: ApiOutcome,
    val refreshRunId: RefreshRunId? = null,
    val repositoryId: RepositoryId? = null,
    val pullRequestId: PullRequestId? = null,
    val actionItemId: ActionItemId? = null,
)

private data class ApiV1CorrelationIds(
    val refreshRunId: RefreshRunId? = null,
    val repositoryId: RepositoryId? = null,
    val pullRequestId: PullRequestId? = null,
    val actionItemId: ActionItemId? = null,
)

private data class ApiV1Observation(
    val startNanos: Long,
    val transport: String,
    val method: String,
    val operation: ApiOperation,
    val outcome: ApiOutcome? = null,
    val requestErrorCode: String? = null,
    val failure: Throwable? = null,
    val correlationIds: ApiV1CorrelationIds = ApiV1CorrelationIds(),
    val terminalRecorded: Boolean = false,
)

private val ApiV1ObservationAttribute = AttributeKey<ApiV1Observation>("api-v1-observation")

internal fun Application.installApiV1Observability(
    transportKind: TransportKind,
    backendEventRecorder: BackendEventRecorder,
    monotonicTimeSource: MonotonicTimeSource,
) {
    intercept(ApplicationCallPipeline.Monitoring) {
        if (call.isApiV1Call()) {
            call.beginApiV1Observation(transportKind, monotonicTimeSource.nanoTime())
        }
        proceed()
    }

    // StatusPages handles exceptions while the call pipeline unwinds.  The
    // response-sent hook runs after that handler and the send engine, so it is
    // the terminal point shared by successful and translated error responses.
    ResponseSent.install(this) { call ->
        call.finishApiV1Observation(backendEventRecorder, monotonicTimeSource.nanoTime())
    }
}

private fun ApplicationCall.beginApiV1Observation(
    transportKind: TransportKind,
    startNanos: Long,
) {
    attributes.put(
        ApiV1ObservationAttribute,
        ApiV1Observation(
            startNanos = startNanos,
            transport = transportKind.wireName,
            method = request.httpMethod.value,
            operation = inferApiOperation(request.httpMethod, request.path()),
        ),
    )
}

internal fun ApplicationCall.observeApiOperation(operation: ApiOperation) {
    updateApiV1Observation { copy(operation = operation) }
}

internal fun ApplicationCall.observeApiOutcome(outcome: ApiV1OutcomeData) {
    updateApiV1Observation {
        copy(
            outcome = outcome.outcome,
            correlationIds = ApiV1CorrelationIds(
                refreshRunId = outcome.refreshRunId,
                repositoryId = outcome.repositoryId,
                pullRequestId = outcome.pullRequestId,
                actionItemId = outcome.actionItemId,
            ),
        )
    }
}

internal fun ApplicationCall.observeApiOutcome(
    outcome: ApiOutcome,
    refreshRunId: RefreshRunId? = null,
    repositoryId: RepositoryId? = null,
    pullRequestId: PullRequestId? = null,
    actionItemId: ActionItemId? = null,
) {
    observeApiOutcome(
        ApiV1OutcomeData(
            outcome = outcome,
            refreshRunId = refreshRunId,
            repositoryId = repositoryId,
            pullRequestId = pullRequestId,
            actionItemId = actionItemId,
        ),
    )
}

internal fun ApplicationCall.observeApiRequestError(requestErrorCode: String) {
    updateApiV1Observation {
        copy(requestErrorCode = requestErrorCode)
    }
}

internal fun ApplicationCall.observeApiFailure(failure: Throwable) {
    updateApiV1Observation {
        copy(failure = failure, requestErrorCode = null)
    }
}

private fun ApplicationCall.finishApiV1Observation(
    backendEventRecorder: BackendEventRecorder,
    endNanos: Long,
) {
    if (!isApiV1Call() || !attributes.contains(ApiV1ObservationAttribute)) return
    val observation = attributes[ApiV1ObservationAttribute]
    if (observation.terminalRecorded) return
    attributes.put(ApiV1ObservationAttribute, observation.copy(terminalRecorded = true))

    val status = response.status()?.value ?: 500
    val durationMilliseconds = ((endNanos - observation.startNanos).coerceAtLeast(0L)) / NANOS_PER_MILLISECOND
    val ids = observation.correlationIds
    val event = when {
        observation.failure != null -> BackendLogEvent.HttpRequestFailed(
            requestId = apiV1RequestId(),
            transport = observation.transport,
            method = observation.method,
            operation = observation.operation.wireName,
            status = status,
            durationMilliseconds = durationMilliseconds,
            failure = observation.failure,
            refreshRunId = ids.refreshRunId?.value,
            repositoryId = ids.repositoryId?.value,
            pullRequestId = ids.pullRequestId?.value,
            actionItemId = ids.actionItemId?.value,
        )
        observation.requestErrorCode != null || status >= 400 -> BackendLogEvent.HttpRequestRejected(
            requestId = apiV1RequestId(),
            transport = observation.transport,
            method = observation.method,
            operation = observation.operation.wireName,
            status = status,
            requestErrorCode = observation.requestErrorCode ?: fallbackRequestErrorCode(status),
            durationMilliseconds = durationMilliseconds,
            refreshRunId = ids.refreshRunId?.value,
            repositoryId = ids.repositoryId?.value,
            pullRequestId = ids.pullRequestId?.value,
            actionItemId = ids.actionItemId?.value,
        )
        else -> BackendLogEvent.HttpRequestCompleted(
            requestId = apiV1RequestId(),
            transport = observation.transport,
            method = observation.method,
            operation = observation.operation.wireName,
            status = status,
            outcome = (observation.outcome ?: ApiOutcome.WORKSPACE_NOT_CONFIGURED).wireName,
            durationMilliseconds = durationMilliseconds,
            mutation = observation.operation.mutation,
            refreshRunId = ids.refreshRunId?.value,
            repositoryId = ids.repositoryId?.value,
            pullRequestId = ids.pullRequestId?.value,
            actionItemId = ids.actionItemId?.value,
        )
    }
    backendEventRecorder.record(event)
}

private fun ApplicationCall.updateApiV1Observation(update: ApiV1Observation.() -> ApiV1Observation) {
    if (!attributes.contains(ApiV1ObservationAttribute)) return
    attributes.put(ApiV1ObservationAttribute, update(attributes[ApiV1ObservationAttribute]))
}

private fun inferApiOperation(method: HttpMethod, path: String): ApiOperation {
    val operationPath = path.removePrefix(API_V1_PREFIX).removePrefix("/")
    return when {
        operationPath == "browser-session" -> ApiOperation.BROWSER_SESSION
        operationPath == "health" -> ApiOperation.HEALTH
        operationPath == "dashboard" -> ApiOperation.GET_DASHBOARD
        operationPath == "pull-requests" -> ApiOperation.LIST_PULL_REQUESTS
        operationPath.singleSegmentAfter("pull-requests/") -> ApiOperation.GET_PULL_REQUEST
        operationPath == "inbox" -> ApiOperation.GET_INBOX
        operationPath == "synchronization" -> ApiOperation.GET_SYNCHRONIZATION
        operationPath.singleSegmentBetween("action-items/", "/content") ->
            ApiOperation.GET_LIVE_ACTIVITY_CONTENT
        operationPath.singleSegmentBetween("action-items/", "/acknowledgment") ->
            ApiOperation.ACKNOWLEDGE_ACTION_ITEM
        operationPath == "refresh-runs" -> ApiOperation.START_REFRESH_RUN
        operationPath.singleSegmentAfter("refresh-runs/") -> ApiOperation.GET_REFRESH_RUN
        operationPath == "configuration/workspace" && method == HttpMethod.Get ->
            ApiOperation.GET_WORKSPACE_CONFIGURATION
        operationPath == "configuration/workspace" -> ApiOperation.CONFIGURE_WORKSPACE
        operationPath == "configuration/workspace/repositories" -> ApiOperation.ADD_REPOSITORY
        operationPath.singleSegmentAfter("configuration/workspace/repositories/") ->
            ApiOperation.REMOVE_REPOSITORY
        else -> ApiOperation.ROUTE_NOT_FOUND
    }
}

private fun String.singleSegmentAfter(prefix: String): Boolean {
    if (!startsWith(prefix)) return false
    val remainder = removePrefix(prefix)
    return remainder.isNotEmpty() && !remainder.contains('/')
}

private fun String.singleSegmentBetween(prefix: String, suffix: String): Boolean {
    if (!startsWith(prefix) || !endsWith(suffix)) return false
    val remainder = removePrefix(prefix).removeSuffix(suffix)
    return remainder.isNotEmpty() && !remainder.contains('/')
}

private fun fallbackRequestErrorCode(status: Int): String = when (status) {
    400 -> "INVALID_REQUEST"
    401 -> "FORBIDDEN"
    403 -> "FORBIDDEN"
    404 -> "ROUTE_NOT_FOUND"
    405 -> "METHOD_NOT_ALLOWED"
    415 -> "UNSUPPORTED_CONTENT_TYPE"
    500 -> "INTERNAL_SERVER_ERROR"
    else -> "INVALID_REQUEST"
}

private val TransportKind.wireName: String
    get() = name.lowercase()

internal fun DashboardResult.toApiV1Outcome() = when (this) {
    is DashboardResult.SnapshotChanged -> ApiV1OutcomeData(ApiOutcome.SNAPSHOT_CHANGED)
    is DashboardResult.SnapshotUnchanged -> ApiV1OutcomeData(ApiOutcome.SNAPSHOT_UNCHANGED)
    DashboardResult.WorkspaceNotConfigured -> ApiV1OutcomeData(ApiOutcome.WORKSPACE_NOT_CONFIGURED)
}

internal fun ListPullRequestsResult.toApiV1Outcome() = when (this) {
    is ListPullRequestsResult.Available -> ApiV1OutcomeData(ApiOutcome.PULL_REQUESTS_AVAILABLE)
    ListPullRequestsResult.WorkspaceNotConfigured -> ApiV1OutcomeData(ApiOutcome.WORKSPACE_NOT_CONFIGURED)
}

internal fun GetPullRequestResult.toApiV1Outcome() = when (this) {
    is GetPullRequestResult.Found -> ApiV1OutcomeData(
        outcome = ApiOutcome.PULL_REQUEST_FOUND,
        pullRequestId = pullRequest.pullRequest.id,
    )
    is GetPullRequestResult.PullRequestNotFound -> ApiV1OutcomeData(
        outcome = ApiOutcome.PULL_REQUEST_NOT_FOUND,
        pullRequestId = pullRequestId,
    )
    GetPullRequestResult.WorkspaceNotConfigured -> ApiV1OutcomeData(ApiOutcome.WORKSPACE_NOT_CONFIGURED)
}

internal fun GetInboxResult.toApiV1Outcome() = when (this) {
    is GetInboxResult.Available -> ApiV1OutcomeData(ApiOutcome.INBOX_AVAILABLE)
    GetInboxResult.WorkspaceNotConfigured -> ApiV1OutcomeData(ApiOutcome.WORKSPACE_NOT_CONFIGURED)
}

internal fun GetSynchronizationStatusResult.toApiV1Outcome() = when (this) {
    is GetSynchronizationStatusResult.Available -> ApiV1OutcomeData(ApiOutcome.SYNCHRONIZATION_AVAILABLE)
    GetSynchronizationStatusResult.WorkspaceNotConfigured -> ApiV1OutcomeData(ApiOutcome.WORKSPACE_NOT_CONFIGURED)
}

internal fun LiveActivityContentResult.toApiV1Outcome() = when (this) {
    is LiveActivityContentResult.ContentAvailable -> ApiV1OutcomeData(
        outcome = ApiOutcome.CONTENT_AVAILABLE,
        actionItemId = actionItemId,
    )
    is LiveActivityContentResult.StaleActivityVersion ->
        ApiV1OutcomeData(ApiOutcome.STALE_ACTIVITY_VERSION, actionItemId = actionItemId)
    is LiveActivityContentResult.NewerActivityObserved -> ApiV1OutcomeData(
        outcome = ApiOutcome.NEWER_ACTIVITY_OBSERVED,
        repositoryId = repositoryId,
        actionItemId = actionItemId,
    )
    is LiveActivityContentResult.ContentUnavailable ->
        ApiV1OutcomeData(ApiOutcome.CONTENT_UNAVAILABLE, actionItemId = actionItemId)
    is LiveActivityContentResult.ActionItemNotFound ->
        ApiV1OutcomeData(ApiOutcome.ACTION_ITEM_NOT_FOUND, actionItemId = actionItemId)
}

internal fun AcknowledgeActionItemResult.toApiV1Outcome() = when (this) {
    is AcknowledgeActionItemResult.Acknowledged ->
        ApiV1OutcomeData(ApiOutcome.ACKNOWLEDGED, actionItemId = actionItemId)
    is AcknowledgeActionItemResult.AlreadyAcknowledged ->
        ApiV1OutcomeData(ApiOutcome.ALREADY_ACKNOWLEDGED, actionItemId = actionItemId)
    is AcknowledgeActionItemResult.StaleActivityVersion ->
        ApiV1OutcomeData(ApiOutcome.STALE_ACTIVITY_VERSION, actionItemId = actionItemId)
    is AcknowledgeActionItemResult.AcknowledgmentRejected ->
        ApiV1OutcomeData(ApiOutcome.ACKNOWLEDGMENT_REJECTED, actionItemId = actionItemId)
    is AcknowledgeActionItemResult.ActionItemNotFound ->
        ApiV1OutcomeData(ApiOutcome.ACTION_ITEM_NOT_FOUND, actionItemId = actionItemId)
}

internal fun StartRefreshRunResult.toApiV1Outcome() = when (this) {
    StartRefreshRunResult.WorkspaceNotConfigured -> ApiV1OutcomeData(ApiOutcome.WORKSPACE_NOT_CONFIGURED)
    StartRefreshRunResult.NoRepositoriesConfigured -> ApiV1OutcomeData(ApiOutcome.NO_REPOSITORIES_CONFIGURED)
    is StartRefreshRunResult.RefreshRunRegistered -> ApiV1OutcomeData(
        outcome = ApiOutcome.REFRESH_RUN_REGISTERED,
        refreshRunId = refreshRun.id,
    )
}

internal fun GetRefreshRunResult.toApiV1Outcome() = when (this) {
    is GetRefreshRunResult.RefreshRunInProgress ->
        ApiV1OutcomeData(ApiOutcome.REFRESH_RUN_IN_PROGRESS, refreshRunId = refreshRun.id)
    is GetRefreshRunResult.RefreshRunCompleted ->
        ApiV1OutcomeData(ApiOutcome.REFRESH_RUN_COMPLETED, refreshRunId = refreshRun.id)
    is GetRefreshRunResult.RefreshRunUnavailable ->
        ApiV1OutcomeData(ApiOutcome.REFRESH_RUN_UNAVAILABLE, refreshRunId = refreshRunId)
}

internal fun GetWorkspaceConfigurationResult.toApiV1Outcome() = when (this) {
    is GetWorkspaceConfigurationResult.Configured ->
        ApiV1OutcomeData(ApiOutcome.WORKSPACE_CONFIGURATION_AVAILABLE)
    GetWorkspaceConfigurationResult.WorkspaceNotConfigured -> ApiV1OutcomeData(ApiOutcome.WORKSPACE_NOT_CONFIGURED)
}

internal fun ConfigureWorkspaceResult.toApiV1Outcome() = when (this) {
    is ConfigureWorkspaceResult.WorkspaceConfigured -> ApiV1OutcomeData(ApiOutcome.WORKSPACE_CONFIGURED)
    is ConfigureWorkspaceResult.WorkspaceAlreadyConfigured ->
        ApiV1OutcomeData(ApiOutcome.WORKSPACE_ALREADY_CONFIGURED)
    is ConfigureWorkspaceResult.WorkspaceIdentityMismatch ->
        ApiV1OutcomeData(ApiOutcome.WORKSPACE_IDENTITY_MISMATCH)
    ConfigureWorkspaceResult.WorkspaceNotFound -> ApiV1OutcomeData(ApiOutcome.WORKSPACE_NOT_FOUND)
    is ConfigureWorkspaceResult.WorkspaceResolutionUnavailable ->
        ApiV1OutcomeData(ApiOutcome.WORKSPACE_RESOLUTION_UNAVAILABLE)
}

internal fun AddRepositoryResult.toApiV1Outcome() = when (this) {
    is AddRepositoryResult.RepositoryAdded ->
        ApiV1OutcomeData(ApiOutcome.REPOSITORY_ADDED, repositoryId = repository.repositoryId)
    is AddRepositoryResult.RepositoryAlreadyConfigured ->
        ApiV1OutcomeData(ApiOutcome.REPOSITORY_ALREADY_CONFIGURED, repositoryId = repository.repositoryId)
    AddRepositoryResult.RepositoryNotFound -> ApiV1OutcomeData(ApiOutcome.REPOSITORY_NOT_FOUND)
    is AddRepositoryResult.RepositoryResolutionUnavailable ->
        ApiV1OutcomeData(ApiOutcome.REPOSITORY_RESOLUTION_UNAVAILABLE)
    AddRepositoryResult.WorkspaceNotConfigured -> ApiV1OutcomeData(ApiOutcome.WORKSPACE_NOT_CONFIGURED)
}

internal fun RemoveRepositoryResult.toApiV1Outcome() = when (this) {
    is RemoveRepositoryResult.RepositoryRemoved ->
        ApiV1OutcomeData(ApiOutcome.REPOSITORY_REMOVED, repositoryId = repositoryId)
    is RemoveRepositoryResult.RepositoryNotConfigured ->
        ApiV1OutcomeData(ApiOutcome.REPOSITORY_NOT_CONFIGURED, repositoryId = repositoryId)
}

internal fun HealthSnapshot.toApiV1Outcome() = ApiV1OutcomeData(ApiOutcome.HEALTH_SNAPSHOT)

private const val NANOS_PER_MILLISECOND = 1_000_000L

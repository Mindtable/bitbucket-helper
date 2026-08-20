package com.mindtable.bitbuckethelper.adapter.inbound.http

import com.mindtable.bitbuckethelper.application.model.GetDashboardSnapshotQuery
import com.mindtable.bitbuckethelper.application.model.GetPullRequestQuery
import com.mindtable.bitbuckethelper.application.port.inbound.GetDashboardSnapshot
import com.mindtable.bitbuckethelper.application.port.inbound.GetInbox
import com.mindtable.bitbuckethelper.application.port.inbound.GetPullRequest
import com.mindtable.bitbuckethelper.application.port.inbound.GetSynchronizationStatus
import com.mindtable.bitbuckethelper.application.port.inbound.ListPullRequests
import com.mindtable.bitbuckethelper.domain.shared.DashboardRevision
import com.mindtable.bitbuckethelper.domain.shared.PullRequestId
import io.ktor.server.application.call
import io.ktor.server.routing.Route
import io.ktor.server.routing.get

data class ReadApiV1Dependencies(
    val getDashboardSnapshot: GetDashboardSnapshot,
    val listPullRequests: ListPullRequests,
    val getPullRequest: GetPullRequest,
    val getInbox: GetInbox,
    val getSynchronizationStatus: GetSynchronizationStatus,
)

fun Route.installReadRoutes(dependencies: ReadApiV1Dependencies) {
    get("/dashboard") {
        call.observeApiOperation(ApiOperation.GET_DASHBOARD)
        val afterRevision = call.request.queryParameters["afterRevision"]?.toDashboardRevision()
        val result = dependencies.getDashboardSnapshot(GetDashboardSnapshotQuery(afterRevision))
        call.respondApiV1(result.toApiV1Outcome()) { requestId -> result.toDashboardResponse(requestId) }
    }
    get("/pull-requests") {
        call.observeApiOperation(ApiOperation.LIST_PULL_REQUESTS)
        val result = dependencies.listPullRequests()
        call.respondApiV1(result.toApiV1Outcome()) { requestId -> result.toPullRequestListResponse(requestId) }
    }
    get("/pull-requests/{pullRequestId}") {
        call.observeApiOperation(ApiOperation.GET_PULL_REQUEST)
        val pullRequestId = checkNotNull(call.parameters["pullRequestId"]).toPullRequestId()
        val result = dependencies.getPullRequest(GetPullRequestQuery(pullRequestId))
        call.respondApiV1(result.toApiV1Outcome()) { requestId -> result.toPullRequestDetailResponse(requestId) }
    }
    get("/inbox") {
        call.observeApiOperation(ApiOperation.GET_INBOX)
        val result = dependencies.getInbox()
        call.respondApiV1(result.toApiV1Outcome()) { requestId -> result.toInboxResponse(requestId) }
    }
    get("/synchronization") {
        call.observeApiOperation(ApiOperation.GET_SYNCHRONIZATION)
        val result = dependencies.getSynchronizationStatus()
        call.respondApiV1(result.toApiV1Outcome()) { requestId -> result.toSynchronizationResponse(requestId) }
    }
}

private fun String.toDashboardRevision(): DashboardRevision = try {
    DashboardRevision(this)
} catch (_: IllegalArgumentException) {
    throw InvalidApiRequestException(listOf(ApiRequestViolation.INVALID_AFTER_REVISION))
}

private fun String.toPullRequestId(): PullRequestId = try {
    PullRequestId(this)
} catch (_: IllegalArgumentException) {
    throw InvalidApiRequestException(listOf(ApiRequestViolation.INVALID_PULL_REQUEST_ID))
}

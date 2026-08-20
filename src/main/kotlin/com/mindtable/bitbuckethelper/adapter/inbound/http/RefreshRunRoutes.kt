package com.mindtable.bitbuckethelper.adapter.inbound.http

import com.mindtable.bitbuckethelper.application.model.RefreshTarget
import com.mindtable.bitbuckethelper.application.model.StartRefreshRunCommand
import com.mindtable.bitbuckethelper.application.port.inbound.GetRefreshRun
import com.mindtable.bitbuckethelper.application.port.inbound.StartRefreshRun
import com.mindtable.bitbuckethelper.domain.shared.RefreshRunId
import com.mindtable.bitbuckethelper.domain.shared.RepositoryId
import com.mindtable.bitbuckethelper.generated.api.v1.model.AllConfiguredRepositoriesTarget
import com.mindtable.bitbuckethelper.generated.api.v1.model.RepositoriesTarget
import com.mindtable.bitbuckethelper.generated.api.v1.model.StartRefreshRunRequest
import io.ktor.server.application.call
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post

data class RefreshRunApiV1Dependencies(
    val startRefreshRun: StartRefreshRun,
    val getRefreshRun: GetRefreshRun,
)

fun Route.installRefreshRunRoutes(dependencies: RefreshRunApiV1Dependencies) {
    post("/refresh-runs") {
        call.observeApiOperation(ApiOperation.START_REFRESH_RUN)
        val request = call.receiveApiV1<StartRefreshRunRequest>()
        val target = request.target.toApplicationTarget()
        val result = dependencies.startRefreshRun(StartRefreshRunCommand(target))
        call.respondApiV1(result.toApiV1Outcome()) { requestId -> result.toStartRefreshRunResponse(requestId) }
    }
    get("/refresh-runs/{refreshRunId}") {
        call.observeApiOperation(ApiOperation.GET_REFRESH_RUN)
        val refreshRunId = checkNotNull(call.parameters["refreshRunId"]).toRefreshRunId()
        val result = dependencies.getRefreshRun(refreshRunId)
        call.respondApiV1(result.toApiV1Outcome()) { requestId -> result.toGetRefreshRunResponse(requestId) }
    }
}

private fun com.mindtable.bitbuckethelper.generated.api.v1.model.RefreshTarget.toApplicationTarget(): RefreshTarget =
    when (this) {
        is AllConfiguredRepositoriesTarget -> RefreshTarget.AllConfiguredRepositories
        is RepositoriesTarget -> {
            if (repositoryIds.isEmpty()) {
                throw InvalidApiRequestException(listOf(ApiRequestViolation.EMPTY_REFRESH_REPOSITORY_IDS))
            }
            RefreshTarget.Repositories(repositoryIds.map { it.toRefreshRepositoryId() })
        }
    }

private fun String.toRefreshRepositoryId(): RepositoryId = try {
    RepositoryId(this)
} catch (_: IllegalArgumentException) {
    throw InvalidApiRequestException(listOf(ApiRequestViolation.INVALID_REFRESH_REPOSITORY_ID))
}

private fun String.toRefreshRunId(): RefreshRunId = try {
    RefreshRunId(this)
} catch (_: IllegalArgumentException) {
    throw InvalidApiRequestException(listOf(ApiRequestViolation.INVALID_REFRESH_RUN_ID))
}

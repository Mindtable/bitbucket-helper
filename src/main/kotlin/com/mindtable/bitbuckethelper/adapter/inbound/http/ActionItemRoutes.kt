package com.mindtable.bitbuckethelper.adapter.inbound.http

import com.mindtable.bitbuckethelper.application.model.AcknowledgeActionItemCommand
import com.mindtable.bitbuckethelper.application.model.GetLiveActivityContentCommand
import com.mindtable.bitbuckethelper.application.port.inbound.AcknowledgeActionItem
import com.mindtable.bitbuckethelper.application.port.inbound.GetLiveActivityContent
import com.mindtable.bitbuckethelper.domain.shared.ActionItemId
import com.mindtable.bitbuckethelper.domain.shared.ActivityVersion
import com.mindtable.bitbuckethelper.generated.api.v1.model.AcknowledgeActionItemRequest
import io.ktor.server.application.call
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.put

data class ActionItemApiV1Dependencies(
    val getLiveActivityContent: GetLiveActivityContent,
    val acknowledgeActionItem: AcknowledgeActionItem,
)

fun Route.installActionItemRoutes(dependencies: ActionItemApiV1Dependencies) {
    get("/action-items/{actionItemId}/content") {
        call.observeApiOperation(ApiOperation.GET_LIVE_ACTIVITY_CONTENT)
        val actionItemId = checkNotNull(call.parameters["actionItemId"]).toActionItemId()
        val activityVersion = call.request.queryParameters["activityVersion"]
            ?.toActivityVersion()
            ?: throw InvalidApiRequestException(listOf(ApiRequestViolation.MISSING_ACTIVITY_VERSION))
        val result = dependencies.getLiveActivityContent(
            GetLiveActivityContentCommand(actionItemId, activityVersion),
        )
        call.respondApiV1(result.toApiV1Outcome()) { requestId -> result.toLiveActivityContentResponse(requestId) }
    }
    put("/action-items/{actionItemId}/acknowledgment") {
        call.observeApiOperation(ApiOperation.ACKNOWLEDGE_ACTION_ITEM)
        val actionItemId = checkNotNull(call.parameters["actionItemId"]).toActionItemId()
        val request = call.receiveApiV1<AcknowledgeActionItemRequest>()
        val activityVersion = request.activityVersion.toActivityVersion()
        val result = dependencies.acknowledgeActionItem(
            AcknowledgeActionItemCommand(actionItemId, activityVersion),
        )
        call.respondApiV1(result.toApiV1Outcome()) { requestId -> result.toAcknowledgeActionItemResponse(requestId) }
    }
}

private fun String.toActionItemId(): ActionItemId = try {
    ActionItemId(this)
} catch (_: IllegalArgumentException) {
    throw InvalidApiRequestException(listOf(ApiRequestViolation.INVALID_ACTION_ITEM_ID))
}

private fun String.toActivityVersion(): ActivityVersion = try {
    ActivityVersion(this)
} catch (_: IllegalArgumentException) {
    throw InvalidApiRequestException(listOf(ApiRequestViolation.INVALID_ACTIVITY_VERSION))
}

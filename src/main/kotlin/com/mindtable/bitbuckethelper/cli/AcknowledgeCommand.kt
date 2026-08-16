package com.mindtable.bitbuckethelper.cli

import com.mindtable.bitbuckethelper.generated.api.v1.model.AcknowledgeActionItemRequest
import com.mindtable.bitbuckethelper.generated.api.v1.model.AcknowledgeActionItemResponse
import com.mindtable.bitbuckethelper.generated.api.v1.model.AcknowledgedResult
import com.mindtable.bitbuckethelper.generated.api.v1.model.AcknowledgmentRejectedResult
import com.mindtable.bitbuckethelper.generated.api.v1.model.AcknowledgmentStaleActivityVersionResult
import com.mindtable.bitbuckethelper.generated.api.v1.model.ActionItemNotFoundResult
import com.mindtable.bitbuckethelper.generated.api.v1.model.AlreadyAcknowledgedResult
import com.mindtable.bitbuckethelper.generated.api.v1.model.ApiVersion
import io.ktor.http.HttpStatusCode
import java.io.IOException
import kotlinx.serialization.SerializationException

/** Acknowledges exactly the opaque activity version supplied by the user. */
class AcknowledgeCommand(
    private val client: LocalApiClient,
    private val output: CliOutput,
) {
    suspend fun acknowledge(
        actionItemId: String,
        activityVersion: String,
        mode: OutputMode,
    ): CliExit {
        if (!ACTION_ITEM_ID.matches(actionItemId) || !ACTIVITY_VERSION.matches(activityVersion)) {
            return CliExit.USAGE_ERROR
        }

        return try {
            val response = client.put(
                "$ACTION_ITEMS_PATH/$actionItemId/acknowledgment",
                AcknowledgeActionItemRequest(ApiVersion._1, activityVersion),
                AcknowledgeActionItemRequest.serializer(),
                AcknowledgeActionItemResponse.serializer(),
            )
            if (response.status != HttpStatusCode.OK) {
                output.render(mode, CliOutcome.serviceUnavailable())
            } else {
                renderResult(response, actionItemId, activityVersion, mode)
            }
        } catch (_: IOException) {
            output.render(mode, CliOutcome.serviceUnavailable())
        } catch (_: LocalApiResponseTooLargeException) {
            output.render(mode, CliOutcome.serviceUnavailable())
        } catch (_: SerializationException) {
            output.render(mode, CliOutcome.serviceUnavailable())
        }
    }

    private fun renderResult(
        response: LocalApiResponse<AcknowledgeActionItemResponse>,
        actionItemId: String,
        activityVersion: String,
        mode: OutputMode,
    ): CliExit = when (response.value?.result) {
        is AcknowledgedResult -> output.render(
            mode,
            CliOutcome.api(response) {
                "Acknowledged action item $actionItemId at activity version $activityVersion."
            },
        )

        is AlreadyAcknowledgedResult -> output.render(
            mode,
            CliOutcome.api(response) {
                "Action item $actionItemId is already acknowledged at activity version $activityVersion."
            },
        )

        is AcknowledgmentStaleActivityVersionResult -> output.render(
            mode,
            CliOutcome.api(response, CliExit.BUSINESS_NOT_ACHIEVED) {
                "Activity version $activityVersion is stale for action item $actionItemId."
            },
        )

        is AcknowledgmentRejectedResult -> output.render(
            mode,
            CliOutcome.api(response, CliExit.BUSINESS_NOT_ACHIEVED) {
                "Action item $actionItemId cannot be acknowledged at activity version $activityVersion."
            },
        )

        is ActionItemNotFoundResult -> output.render(
            mode,
            CliOutcome.api(response, CliExit.BUSINESS_NOT_ACHIEVED) {
                "Action item $actionItemId was not found at activity version $activityVersion."
            },
        )

        null -> output.render(mode, CliOutcome.serviceUnavailable())
    }

    private companion object {
        const val ACTION_ITEMS_PATH = "/api/v1/action-items"
        val ACTION_ITEM_ID = Regex("^ai_[A-Za-z0-9_-]+$")
        val ACTIVITY_VERSION = Regex("^av_[A-Za-z0-9_-]+$")
    }
}

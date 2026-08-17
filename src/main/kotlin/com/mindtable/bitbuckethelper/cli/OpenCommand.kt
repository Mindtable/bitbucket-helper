package com.mindtable.bitbuckethelper.cli

import com.mindtable.bitbuckethelper.generated.api.v1.model.PullRequestDetailResponse
import com.mindtable.bitbuckethelper.generated.api.v1.model.PullRequestFoundResult
import com.mindtable.bitbuckethelper.generated.api.v1.model.PullRequestNotFoundResult
import com.mindtable.bitbuckethelper.generated.api.v1.model.WorkspaceNotConfiguredResult
import io.ktor.http.HttpStatusCode
import java.io.IOException
import java.net.URI
import java.net.URISyntaxException
import kotlinx.serialization.SerializationException

/** Opens the server-supplied web link for one pull request. */
class OpenCommand(
    private val client: LocalApiClient,
    private val output: CliOutput,
    private val openUrl: OpenUrl,
) {
    suspend fun open(pullRequestId: String, mode: OutputMode): CliExit {
        if (!PULL_REQUEST_ID.matches(pullRequestId)) {
            return CliExit.USAGE_ERROR
        }

        return try {
            val response = client.get(
                "$PULL_REQUESTS_PATH/$pullRequestId",
                PullRequestDetailResponse.serializer(),
            )
            if (response.status != HttpStatusCode.OK) {
                output.renderApiError(mode, response)
                    ?: output.render(mode, CliOutcome.serviceUnavailable())
            } else {
                renderResult(response, pullRequestId, mode)
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
        response: LocalApiResponse<PullRequestDetailResponse>,
        requestedPullRequestId: String,
        mode: OutputMode,
    ): CliExit = when (val result = response.value?.result) {
        is PullRequestFoundResult -> {
            if (result.pullRequest.pullRequest.pullRequestId != requestedPullRequestId) {
                return output.render(mode, CliOutcome.serviceUnavailable())
            }
            val url = result.pullRequest.pullRequest.webUrl
            if (!url.isSafeBitbucketHttpsLink()) {
                output.render(
                    mode,
                    CliOutcome.api(response, CliExit.BUSINESS_NOT_ACHIEVED) {
                        "Pull request $requestedPullRequestId has no safe Bitbucket HTTPS link."
                    },
                )
            } else if (openUrl.open(url)) {
                output.render(
                    mode,
                    CliOutcome.api(response) { "Opened pull request $requestedPullRequestId." },
                )
            } else {
                output.render(mode, CliOutcome.unexpectedFailure(OPEN_FAILURE_MESSAGE))
            }
        }

        is PullRequestNotFoundResult -> if (result.pullRequestId == requestedPullRequestId) {
            output.render(
                mode,
                CliOutcome.api(response, CliExit.BUSINESS_NOT_ACHIEVED) {
                    "Pull request $requestedPullRequestId was not found."
                },
            )
        } else {
            output.render(mode, CliOutcome.serviceUnavailable())
        }

        is WorkspaceNotConfiguredResult -> output.render(
            mode,
            CliOutcome.api(response, CliExit.BUSINESS_NOT_ACHIEVED) {
                "Workspace is not configured. Run ${result.setupCommand.toString().humanEscaped()}."
            },
        )

        null -> output.render(mode, CliOutcome.serviceUnavailable())
    }

    private companion object {
        const val PULL_REQUESTS_PATH = "/api/v1/pull-requests"
        const val OPEN_FAILURE_MESSAGE = "Unable to open pull request in the browser."
        val PULL_REQUEST_ID = Regex("^pr_[A-Za-z0-9_-]+$")
    }
}

private fun String.isSafeBitbucketHttpsLink(): Boolean = try {
    val uri = URI(this)
    uri.isAbsolute &&
        uri.scheme.equals("https", ignoreCase = true) &&
        uri.host.equals("bitbucket.org", ignoreCase = true) &&
        uri.userInfo == null &&
        (uri.port == -1 || uri.port == 443)
} catch (_: URISyntaxException) {
    false
}

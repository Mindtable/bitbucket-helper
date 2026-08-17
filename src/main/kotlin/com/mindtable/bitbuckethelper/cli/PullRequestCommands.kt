package com.mindtable.bitbuckethelper.cli

import com.mindtable.bitbuckethelper.generated.api.v1.model.ActionItem
import com.mindtable.bitbuckethelper.generated.api.v1.model.Freshness
import com.mindtable.bitbuckethelper.generated.api.v1.model.FreshnessFresh
import com.mindtable.bitbuckethelper.generated.api.v1.model.FreshnessNeverSynchronized
import com.mindtable.bitbuckethelper.generated.api.v1.model.FreshnessStale
import com.mindtable.bitbuckethelper.generated.api.v1.model.PullRequestCard
import com.mindtable.bitbuckethelper.generated.api.v1.model.PullRequestDetail
import com.mindtable.bitbuckethelper.generated.api.v1.model.PullRequestDetailResponse
import com.mindtable.bitbuckethelper.generated.api.v1.model.PullRequestFoundResult
import com.mindtable.bitbuckethelper.generated.api.v1.model.PullRequestListResponse
import com.mindtable.bitbuckethelper.generated.api.v1.model.PullRequestNotFoundResult
import com.mindtable.bitbuckethelper.generated.api.v1.model.PullRequestsAvailableResult
import com.mindtable.bitbuckethelper.generated.api.v1.model.Readiness
import com.mindtable.bitbuckethelper.generated.api.v1.model.ReadinessAvailable
import com.mindtable.bitbuckethelper.generated.api.v1.model.ReadinessUnavailable
import com.mindtable.bitbuckethelper.generated.api.v1.model.RepositoryGroup
import com.mindtable.bitbuckethelper.generated.api.v1.model.WorkspaceNotConfiguredResult
import java.io.IOException
import io.ktor.http.HttpStatusCode
import kotlinx.serialization.SerializationException

/** Service-backed read operations for the pull-request command family. */
class PullRequestCommands(
    private val client: LocalApiClient,
    private val output: CliOutput,
) {
    suspend fun list(mode: OutputMode): CliExit = executeRead(
        output = output,
        mode = mode,
        request = { client.get(PULL_REQUESTS_PATH, PullRequestListResponse.serializer()) },
    ) { response, terminal ->
        when (val result = response?.result) {
            is PullRequestsAvailableResult -> renderList(result.repositoryGroups, terminal)
            is WorkspaceNotConfiguredResult -> workspaceNotConfigured(result)
            null -> "The service returned an invalid response."
        }
    }

    suspend fun show(pullRequestId: String, mode: OutputMode): CliExit {
        if (!PULL_REQUEST_ID.matches(pullRequestId)) {
            return CliExit.USAGE_ERROR
        }
        return executeRead(
            output = output,
            mode = mode,
            request = { client.get("$PULL_REQUESTS_PATH/$pullRequestId", PullRequestDetailResponse.serializer()) },
        ) { response, terminal ->
            when (val result = response?.result) {
                is PullRequestFoundResult -> renderDetail(result.pullRequest, terminal)
                is PullRequestNotFoundResult -> "Pull request ${result.pullRequestId.humanEscaped()} was not found."
                is WorkspaceNotConfiguredResult -> workspaceNotConfigured(result)
                null -> "The service returned an invalid response."
            }
        }
    }

    private fun renderList(groups: List<RepositoryGroup>, terminal: TerminalCapability): String {
        if (groups.isEmpty()) return "No pull requests."
        return buildString {
            appendLine(terminal.bold("Pull requests"))
            groups.forEachIndexed { groupIndex, group ->
                if (groupIndex > 0) appendLine()
                appendLine("Repository: ${group.displayName.humanEscaped()}")
                appendLine("  ID: ${group.repositoryId.humanEscaped()}")
                appendLine("  Slug: ${group.slug.humanEscaped()}")
                appendLine("  URL: ${group.webUrl.humanEscaped()}")
                appendLine("  Revision: ${group.repositoryRevision.humanEscaped()}")
                appendLine(
                    "  Readiness summary: ready=${group.readinessSummary.readyPullRequestCount}; " +
                        "available=${group.readinessSummary.availablePullRequestCount}; " +
                        "unavailable=${group.readinessSummary.unavailablePullRequestCount}",
                )
                if (group.pullRequests.isEmpty()) {
                    appendLine("  No pull requests.")
                } else {
                    group.pullRequests.forEach { pullRequest -> appendCard(pullRequest, "  ") }
                }
            }
        }.trimEnd()
    }

    private fun renderDetail(detail: PullRequestDetail, terminal: TerminalCapability): String = buildString {
        appendLine(terminal.bold("Pull request"))
        appendCard(detail.pullRequest, "")
        appendLine("Head commit: ${detail.headCommit.humanEscaped()}")
        appendLine("Freshness: ${renderFreshness(detail.freshness)}")
        appendLine("Builds:")
        if (detail.builds.isEmpty()) {
            appendLine("  none")
        } else {
            detail.builds.forEach { build ->
                appendLine("  ${build.key.humanEscaped()}: ${build.state.toString().humanEscaped()}")
            }
        }
        appendLine("Action items:")
        if (detail.pullRequest.actionItems.isEmpty()) {
            appendLine("  none")
        } else {
            detail.pullRequest.actionItems.forEach { action -> append(renderActionItem(action, "  Action")) }
        }
    }.trimEnd()

    private fun StringBuilder.appendCard(pullRequest: PullRequestCard, indentation: String) {
        appendLine("${indentation}PR ${pullRequest.pullRequestId.humanEscaped()} (#${pullRequest.upstreamNumber}): ${pullRequest.title.humanEscaped()}")
        appendLine("${indentation}  Repository ID: ${pullRequest.repositoryId.humanEscaped()}")
        appendLine("${indentation}  Author: ${pullRequest.author.displayName.humanEscaped()} (${pullRequest.author.stableId.humanEscaped()})")
        appendLine("${indentation}  Draft: ${pullRequest.draft}")
        appendLine("${indentation}  Created at: ${pullRequest.createdAt.humanEscaped()}")
        appendLine("${indentation}  Updated at: ${pullRequest.updatedAt.humanEscaped()}")
        appendLine("${indentation}  URL: ${pullRequest.webUrl.humanEscaped()}")
        appendLine("${indentation}  Readiness: ${renderReadiness(pullRequest.readiness)}")
        if (pullRequest.readiness is ReadinessAvailable) {
            appendLine("${indentation}  Checks:")
            pullRequest.readiness.checks.forEach { check ->
                appendLine(
                    "${indentation}    ${check.name.humanEscaped()}: " +
                        "${if (check.passed) "passed" else "failed"}" +
                        check.safeReason?.let { " (${it.humanEscaped()})" }.orEmpty(),
                )
            }
        }
        appendLine("${indentation}  Build state: ${pullRequest.buildState.toString().humanEscaped()}")
        appendLine("${indentation}  Actionable items: ${pullRequest.actionableItemCount}")
        appendLine("${indentation}  Acknowledged items: ${pullRequest.acknowledgedItemCount}")
    }

    private fun workspaceNotConfigured(result: WorkspaceNotConfiguredResult): String =
        "Workspace is not configured. Run ${result.setupCommand.toString().humanEscaped()}."

    private companion object {
        const val PULL_REQUESTS_PATH = "/api/v1/pull-requests"
        val PULL_REQUEST_ID = Regex("^pr_[A-Za-z0-9_-]+$")
    }
}

/**
 * Read commands accept only the contract's normal HTTP 200 transport status.
 * Other statuses and known client protocol failures have no business result.
 */
internal suspend fun <Response> executeRead(
    output: CliOutput,
    mode: OutputMode,
    request: suspend () -> LocalApiResponse<Response>,
    humanRenderer: (Response?, TerminalCapability) -> String,
): CliExit = try {
    val response = request()
    if (response.status != HttpStatusCode.OK) {
        output.renderApiError(mode, response)
            ?: output.render(mode, CliOutcome.serviceUnavailable())
    } else {
        output.render(mode, CliOutcome.api(response) { terminal -> humanRenderer(response.value, terminal) })
    }
} catch (_: IOException) {
    output.render(mode, CliOutcome.serviceUnavailable())
} catch (_: LocalApiResponseTooLargeException) {
    output.render(mode, CliOutcome.serviceUnavailable())
} catch (_: SerializationException) {
    output.render(mode, CliOutcome.serviceUnavailable())
}

internal fun renderReadiness(readiness: Readiness): String = when (readiness) {
    is ReadinessAvailable -> "${readiness.passed} of ${readiness.total} checks"
    is ReadinessUnavailable -> "unavailable (${readiness.safeReason.humanEscaped()})"
}

internal fun renderFreshness(freshness: Freshness): String = when (freshness) {
    is FreshnessNeverSynchronized -> "never synchronized"
    is FreshnessFresh -> "fresh; snapshot at ${freshness.snapshotAt.humanEscaped()}; age ${freshness.ageMilliseconds}ms"
    is FreshnessStale ->
        "stale; snapshot at ${freshness.snapshotAt.humanEscaped()}; age ${freshness.ageMilliseconds}ms; " +
            "stale since ${freshness.staleSince.humanEscaped()}"
}

internal fun renderActionItem(action: ActionItem, heading: String): String = buildString {
    appendLine("$heading ${action.actionItemId.humanEscaped()}: ${action.kind.humanEscaped()}")
    appendLine("  Kind: ${action.kind.humanEscaped()}")
    appendLine(
        "  Pull request: #${action.pullRequestNumber} ${action.pullRequestTitle.humanEscaped()} " +
            "(${action.pullRequestId.humanEscaped()})",
    )
    appendLine("  Repository: ${action.repositoryDisplayName.humanEscaped()} (${action.repositoryId.humanEscaped()})")
    appendLine("  Activity version: ${action.activityVersion.humanEscaped()}")
    appendLine("  Actor: ${action.actor.displayName.humanEscaped()} (${action.actor.stableId.humanEscaped()})")
    appendLine("  Activity at: ${action.activityAt.humanEscaped()}")
    appendLine("  State: ${action.state.toString().humanEscaped()}")
    appendLine("  Acknowledged at: ${action.acknowledgedAt?.humanEscaped() ?: "none"}")
    appendLine("  URL: ${action.webUrl.humanEscaped()}")
}

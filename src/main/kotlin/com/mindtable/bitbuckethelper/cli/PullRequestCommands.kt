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

/** Service-backed read operations for the pull-request command family. */
class PullRequestCommands(
    private val client: LocalApiClient,
    private val output: CliOutput,
) {
    suspend fun list(mode: OutputMode): CliExit = try {
        val response = client.get(PULL_REQUESTS_PATH, PullRequestListResponse.serializer())
        output.render(mode, CliOutcome.api(response) { terminal ->
            when (val result = response.value?.result) {
                is PullRequestsAvailableResult -> renderList(result.repositoryGroups, terminal)
                is WorkspaceNotConfiguredResult -> workspaceNotConfigured(result)
                null -> "The service returned an invalid response."
            }
        })
    } catch (_: IOException) {
        output.render(mode, CliOutcome.serviceUnavailable())
    }

    suspend fun show(pullRequestId: String, mode: OutputMode): CliExit {
        if (!PULL_REQUEST_ID.matches(pullRequestId)) {
            return CliExit.USAGE_ERROR
        }
        return try {
            val response = client.get("$PULL_REQUESTS_PATH/$pullRequestId", PullRequestDetailResponse.serializer())
            output.render(mode, CliOutcome.api(response) { terminal ->
                when (val result = response.value?.result) {
                    is PullRequestFoundResult -> renderDetail(result.pullRequest, terminal)
                    is PullRequestNotFoundResult -> "Pull request ${result.pullRequestId} was not found."
                    is WorkspaceNotConfiguredResult -> workspaceNotConfigured(result)
                    null -> "The service returned an invalid response."
                }
            })
        } catch (_: IOException) {
            output.render(mode, CliOutcome.serviceUnavailable())
        }
    }

    private fun renderList(groups: List<RepositoryGroup>, terminal: TerminalCapability): String {
        if (groups.isEmpty()) return "No pull requests."
        return buildString {
            appendLine(terminal.bold("Pull requests"))
            groups.forEachIndexed { groupIndex, group ->
                if (groupIndex > 0) appendLine()
                appendLine("Repository: ${group.displayName}")
                appendLine("  ID: ${group.repositoryId}")
                appendLine("  Slug: ${group.slug}")
                appendLine("  URL: ${group.webUrl}")
                appendLine("  Revision: ${group.repositoryRevision}")
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
        appendLine("Head commit: ${detail.headCommit}")
        appendLine("Freshness: ${renderFreshness(detail.freshness)}")
        appendLine("Builds:")
        if (detail.builds.isEmpty()) {
            appendLine("  none")
        } else {
            detail.builds.forEach { build -> appendLine("  ${build.key}: ${build.state}") }
        }
        appendLine("Action items:")
        if (detail.pullRequest.actionItems.isEmpty()) {
            appendLine("  none")
        } else {
            detail.pullRequest.actionItems.forEach { action -> append(renderActionItem(action, "  Action")) }
        }
    }.trimEnd()

    private fun StringBuilder.appendCard(pullRequest: PullRequestCard, indentation: String) {
        appendLine("${indentation}PR ${pullRequest.pullRequestId} (#${pullRequest.upstreamNumber}): ${pullRequest.title}")
        appendLine("${indentation}  Repository ID: ${pullRequest.repositoryId}")
        appendLine("${indentation}  Author: ${pullRequest.author.displayName} (${pullRequest.author.stableId})")
        appendLine("${indentation}  Draft: ${pullRequest.draft}")
        appendLine("${indentation}  Created at: ${pullRequest.createdAt}")
        appendLine("${indentation}  Updated at: ${pullRequest.updatedAt}")
        appendLine("${indentation}  URL: ${pullRequest.webUrl}")
        appendLine("${indentation}  Readiness: ${renderReadiness(pullRequest.readiness)}")
        if (pullRequest.readiness is ReadinessAvailable) {
            appendLine("${indentation}  Checks:")
            pullRequest.readiness.checks.forEach { check ->
                appendLine("${indentation}    ${check.name}: ${if (check.passed) "passed" else "failed"}${check.safeReason?.let { " ($it)" }.orEmpty()}")
            }
        }
        appendLine("${indentation}  Build state: ${pullRequest.buildState}")
        appendLine("${indentation}  Actionable items: ${pullRequest.actionableItemCount}")
        appendLine("${indentation}  Acknowledged items: ${pullRequest.acknowledgedItemCount}")
    }

    private fun workspaceNotConfigured(result: WorkspaceNotConfiguredResult): String =
        "Workspace is not configured. Run ${result.setupCommand}."

    private companion object {
        const val PULL_REQUESTS_PATH = "/api/v1/pull-requests"
        val PULL_REQUEST_ID = Regex("^pr_[A-Za-z0-9_-]+$")
    }
}

internal fun renderReadiness(readiness: Readiness): String = when (readiness) {
    is ReadinessAvailable -> "${readiness.passed} of ${readiness.total} checks"
    is ReadinessUnavailable -> "unavailable (${readiness.safeReason})"
}

internal fun renderFreshness(freshness: Freshness): String = when (freshness) {
    is FreshnessNeverSynchronized -> "never synchronized"
    is FreshnessFresh -> "fresh; snapshot at ${freshness.snapshotAt}; age ${freshness.ageMilliseconds}ms"
    is FreshnessStale ->
        "stale; snapshot at ${freshness.snapshotAt}; age ${freshness.ageMilliseconds}ms; stale since ${freshness.staleSince}"
}

internal fun renderActionItem(action: ActionItem, heading: String): String = buildString {
    appendLine("$heading ${action.actionItemId}: ${action.kind}")
    appendLine("  Kind: ${action.kind}")
    appendLine("  Pull request: #${action.pullRequestNumber} ${action.pullRequestTitle} (${action.pullRequestId})")
    appendLine("  Repository: ${action.repositoryDisplayName} (${action.repositoryId})")
    appendLine("  Activity version: ${action.activityVersion}")
    appendLine("  Actor: ${action.actor.displayName} (${action.actor.stableId})")
    appendLine("  Activity at: ${action.activityAt}")
    appendLine("  State: ${action.state}")
    appendLine("  Acknowledged at: ${action.acknowledgedAt ?: "none"}")
    appendLine("  URL: ${action.webUrl}")
}

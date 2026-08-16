package com.mindtable.bitbuckethelper.application.service

import com.mindtable.bitbuckethelper.application.model.PruneInactivePullRequestsResult
import com.mindtable.bitbuckethelper.application.port.inbound.PruneInactivePullRequests
import com.mindtable.bitbuckethelper.application.port.outbound.ApplicationTransactionRunner
import java.time.Clock
import java.time.Duration
import java.time.Instant

class PruneInactivePullRequestsService(
    private val transactions: ApplicationTransactionRunner,
    private val clock: Clock,
) : PruneInactivePullRequests {
    override suspend fun invoke(): PruneInactivePullRequestsResult {
        val completedAt = clock.instant()
        val pruned = transactions.inTransaction {
            val configuration = configurationStore.find() ?: return@inTransaction 0
            val cutoff = completedAt.safeMinus(Duration.ofDays(configuration.retentionDays.toLong()))
            val candidates = pullRequestStore.listInactiveBefore(cutoff)
                .asSequence()
                .filter { pullRequest ->
                    !pullRequest.active && pullRequest.inactiveAt?.let { it < cutoff } == true
                }
                .sortedBy { it.id.value }
                .toList()
            var count = 0
            candidates.forEach { pullRequest ->
                val hasActionableItem = actionItemStore.listByPullRequest(pullRequest.id)
                    .any { it.isCurrentlyActionable() }
                if (!hasActionableItem) {
                    actionItemStore.deleteByPullRequest(pullRequest.id)
                    pullRequestStore.delete(pullRequest.id)
                    count++
                }
            }
            count
        }
        return PruneInactivePullRequestsResult(pruned, completedAt)
    }
}

private fun Instant.safeMinus(duration: Duration): Instant =
    runCatching { minus(duration) }.getOrDefault(Instant.MIN)

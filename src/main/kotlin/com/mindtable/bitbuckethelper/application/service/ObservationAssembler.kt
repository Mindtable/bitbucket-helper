package com.mindtable.bitbuckethelper.application.service

import com.mindtable.bitbuckethelper.application.model.*
import com.mindtable.bitbuckethelper.domain.pullrequest.*
import com.mindtable.bitbuckethelper.domain.shared.PullRequestId
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Instant
import java.util.Base64

class ObservationAssembler {
    fun assemble(detail: GatewayPullRequestDetail, reviewers: List<GatewayUserObservation>, builds: List<GatewayBuildObservation>, tasks: List<GatewayTaskObservation>, observedAt: Instant): PullRequestObservation {
        val mappedBuilds = builds.map { BuildObservation(it.key, it.status.domain(), it.observedAt) }
        return PullRequestObservation(
            idFor(detail.repositoryId.value, detail.upstreamNumber), detail.repositoryId, detail.upstreamNumber, detail.title,
            detail.authorStableId, detail.authorDisplayName, detail.draft, detail.headCommit, detail.webUrl,
            detail.createdAt, detail.updatedAt, observedAt,
            SevenCheckReadiness.assess(detail.approvalCount, tasks.count { !it.resolved }, detail.unresolvedCommentCount,
                detail.destinationBranchIsCurrent, detail.hasMergeConflicts, mappedBuilds.map { it.status },
                reviewers.mapTo(mutableSetOf()) { it.stableId }, detail.approvedByStableIds), mappedBuilds,
        )
    }

    companion object {
        fun idFor(repository: String, number: Long): PullRequestId = PullRequestId("pr_" + digest("$repository|$number"))
        internal fun digest(value: String): String = Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(value.toByteArray(StandardCharsets.UTF_8)))
    }
}

private fun GatewayBuildStatus.domain() = when (this) {
    GatewayBuildStatus.SUCCESSFUL -> BuildStatus.SUCCESSFUL
    GatewayBuildStatus.FAILED -> BuildStatus.FAILED
    GatewayBuildStatus.STOPPED -> BuildStatus.STOPPED
    GatewayBuildStatus.IN_PROGRESS -> BuildStatus.IN_PROGRESS
    GatewayBuildStatus.UNKNOWN -> BuildStatus.UNKNOWN
}

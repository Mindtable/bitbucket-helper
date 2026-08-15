package com.mindtable.bitbuckethelper.domain.pullrequest

import com.mindtable.bitbuckethelper.domain.shared.PullRequestId

sealed interface PullRequestEvent {
    val pullRequestId: PullRequestId
}

data class ReadinessChanged(
    override val pullRequestId: PullRequestId,
    val previous: ReadinessAssessment,
    val current: ReadinessAssessment,
) : PullRequestEvent

data class BuildsBecameGreen(
    override val pullRequestId: PullRequestId,
) : PullRequestEvent

data class PullRequestDeactivated(
    override val pullRequestId: PullRequestId,
) : PullRequestEvent

data class PullRequestReactivated(
    override val pullRequestId: PullRequestId,
) : PullRequestEvent

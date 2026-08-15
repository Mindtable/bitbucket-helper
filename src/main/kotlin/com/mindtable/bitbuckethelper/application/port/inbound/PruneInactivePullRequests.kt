package com.mindtable.bitbuckethelper.application.port.inbound

import com.mindtable.bitbuckethelper.application.model.PruneInactivePullRequestsResult

fun interface PruneInactivePullRequests {
    suspend operator fun invoke(): PruneInactivePullRequestsResult
}

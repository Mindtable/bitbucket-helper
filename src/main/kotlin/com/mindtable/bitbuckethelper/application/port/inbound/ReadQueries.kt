package com.mindtable.bitbuckethelper.application.port.inbound

import com.mindtable.bitbuckethelper.application.model.DashboardResult
import com.mindtable.bitbuckethelper.application.model.GetDashboardSnapshotQuery
import com.mindtable.bitbuckethelper.application.model.GetInboxResult
import com.mindtable.bitbuckethelper.application.model.GetPullRequestQuery
import com.mindtable.bitbuckethelper.application.model.GetPullRequestResult
import com.mindtable.bitbuckethelper.application.model.GetSynchronizationStatusResult
import com.mindtable.bitbuckethelper.application.model.ListPullRequestsResult

fun interface GetDashboardSnapshot {
    suspend operator fun invoke(query: GetDashboardSnapshotQuery): DashboardResult
}

fun interface ListPullRequests {
    suspend operator fun invoke(): ListPullRequestsResult
}

fun interface GetPullRequest {
    suspend operator fun invoke(query: GetPullRequestQuery): GetPullRequestResult
}

fun interface GetInbox {
    suspend operator fun invoke(): GetInboxResult
}

fun interface GetSynchronizationStatus {
    suspend operator fun invoke(): GetSynchronizationStatusResult
}

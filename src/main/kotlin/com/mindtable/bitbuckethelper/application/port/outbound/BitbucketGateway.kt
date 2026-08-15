package com.mindtable.bitbuckethelper.application.port.outbound

import com.mindtable.bitbuckethelper.application.model.GatewayActivityObservation
import com.mindtable.bitbuckethelper.application.model.GatewayBuildObservation
import com.mindtable.bitbuckethelper.application.model.GatewayLiveActivityContent
import com.mindtable.bitbuckethelper.application.model.GatewayPullRequestDetail
import com.mindtable.bitbuckethelper.application.model.GatewayPullRequestSummary
import com.mindtable.bitbuckethelper.application.model.GatewayRepositoryAddress
import com.mindtable.bitbuckethelper.application.model.GatewayRepositoryObservation
import com.mindtable.bitbuckethelper.application.model.GatewayResult
import com.mindtable.bitbuckethelper.application.model.GatewayTaskObservation
import com.mindtable.bitbuckethelper.application.model.GatewayUserObservation
import com.mindtable.bitbuckethelper.application.model.GatewayWorkspaceObservation
import java.net.URI

interface BitbucketGateway {
    suspend fun currentUser(apiBaseUrl: URI): GatewayResult<GatewayUserObservation>
    suspend fun resolveWorkspace(apiBaseUrl: URI, workspaceSlug: String): GatewayResult<GatewayWorkspaceObservation>
    suspend fun resolveRepository(
        apiBaseUrl: URI,
        workspaceSlug: String,
        repositorySlug: String,
    ): GatewayResult<GatewayRepositoryObservation>
    suspend fun listAuthoredOpenPullRequests(
        repository: GatewayRepositoryAddress,
        currentUserStableId: String,
    ): GatewayResult<List<GatewayPullRequestSummary>>
    suspend fun getPullRequest(
        repository: GatewayRepositoryAddress,
        upstreamNumber: Long,
    ): GatewayResult<GatewayPullRequestDetail>
    suspend fun getEffectiveDefaultReviewers(
        repository: GatewayRepositoryAddress,
        upstreamNumber: Long,
    ): GatewayResult<List<GatewayUserObservation>>
    suspend fun listBuilds(
        repository: GatewayRepositoryAddress,
        upstreamNumber: Long,
    ): GatewayResult<List<GatewayBuildObservation>>
    suspend fun listTasks(
        repository: GatewayRepositoryAddress,
        upstreamNumber: Long,
    ): GatewayResult<List<GatewayTaskObservation>>
    suspend fun listActivity(
        repository: GatewayRepositoryAddress,
        upstreamNumber: Long,
    ): GatewayResult<List<GatewayActivityObservation>>
    suspend fun getLiveActivityContent(
        repository: GatewayRepositoryAddress,
        upstreamNumber: Long,
        sourceId: String,
    ): GatewayResult<GatewayLiveActivityContent>
}

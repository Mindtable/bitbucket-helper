package com.mindtable.bitbuckethelper.application.service

import com.mindtable.bitbuckethelper.application.model.*
import com.mindtable.bitbuckethelper.domain.actionitem.*
import com.mindtable.bitbuckethelper.domain.shared.PullRequestId
import java.time.Instant

class ActionObservationAssembler {
    fun assemble(pullRequestId: PullRequestId, activities: List<GatewayActivityObservation>, observedAt: Instant): List<ActionObservation> =
        activities.map { activity ->
            ActionObservation(pullRequestId, when (activity.sourceKind) {
                GatewayActivityKind.COMMENT -> ActionSourceKind.COMMENT
                GatewayActivityKind.REPLY -> ActionSourceKind.THREAD
                GatewayActivityKind.CHANGES_REQUESTED -> ActionSourceKind.COMMENT
            }, activity.sourceId, activity.activityVersion, activity.actorStableId, activity.actorDisplayName,
                activity.activityAt, observedAt, activity.webUrl, when {
                    activity.deleted -> ActionObservationState.DELETED
                    activity.resolved -> ActionObservationState.RESOLVED
                    else -> ActionObservationState.ACTIONABLE
                })
        }
}

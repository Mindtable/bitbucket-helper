package com.mindtable.bitbuckethelper.application.service

import com.mindtable.bitbuckethelper.application.model.*
import com.mindtable.bitbuckethelper.domain.actionitem.*
import com.mindtable.bitbuckethelper.domain.shared.PullRequestId
import java.time.Instant

class ActionObservationAssembler {
    fun assemble(
        pullRequestId: PullRequestId,
        activities: List<GatewayActivityObservation>,
        observedAt: Instant,
        currentUserStableId: String,
    ): List<ActionObservation> {
        val threads = linkedMapOf<String, ActionObservation>()
        val standalone = mutableListOf<ActionObservation>()
        activities.sortedWith(ACTIVITY_ORDER).forEach { activity ->
            when (activity.sourceKind) {
                GatewayActivityKind.CHANGES_REQUESTED -> if (activity.actorStableId != currentUserStableId) {
                    standalone += activity.observation(
                        pullRequestId,
                        observedAt,
                        ActionSourceKind.COMMENT,
                    )
                }
                GatewayActivityKind.COMMENT -> {
                    if (activity.actorStableId != currentUserStableId) {
                        threads[activity.sourceId] = activity.observation(
                            pullRequestId,
                            observedAt,
                            ActionSourceKind.THREAD,
                        )
                    }
                }
                GatewayActivityKind.REPLY -> {
                    if (activity.actorStableId == currentUserStableId) {
                        threads[activity.sourceId]?.let { external ->
                            threads[activity.sourceId] = external.copy(
                                acknowledgedAt = minOf(external.acknowledgedAt ?: activity.activityAt, activity.activityAt),
                            )
                        }
                    } else {
                        threads[activity.sourceId] = activity.observation(
                            pullRequestId,
                            observedAt,
                            ActionSourceKind.THREAD,
                        )
                    }
                }
            }
        }

        return (standalone + threads.values).sortedWith(
            compareBy<ActionObservation> { it.sourceKind.name }
                .thenBy(ActionObservation::upstreamSourceId),
        )
    }

    private fun GatewayActivityObservation.observation(
        pullRequestId: PullRequestId,
        observedAt: Instant,
        sourceKind: ActionSourceKind,
    ) = ActionObservation(
        pullRequestId = pullRequestId,
        sourceKind = sourceKind,
        upstreamSourceId = sourceId,
        activityVersion = activityVersion,
        authorStableId = actorStableId,
        authorDisplayName = actorDisplayName,
        activityAt = activityAt,
        observedAt = observedAt,
        webUrl = webUrl,
        state = when {
            deleted -> ActionObservationState.DELETED
            resolved -> ActionObservationState.RESOLVED
            else -> ActionObservationState.ACTIONABLE
        },
    )

    private companion object {
        val ACTIVITY_ORDER = compareBy<GatewayActivityObservation>(GatewayActivityObservation::activityAt)
            .thenBy { it.webUrl.rawFragment?.removePrefix("comment-")?.toLongOrNull() ?: Long.MAX_VALUE }
            .thenBy { it.sourceKind.name }
            .thenBy(GatewayActivityObservation::sourceId)
            .thenBy { it.activityVersion.value }
            .thenBy { it.actorStableId }
    }
}

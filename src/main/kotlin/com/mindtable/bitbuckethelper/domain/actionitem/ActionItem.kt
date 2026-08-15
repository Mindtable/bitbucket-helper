package com.mindtable.bitbuckethelper.domain.actionitem

import com.mindtable.bitbuckethelper.domain.shared.ActionItemId
import com.mindtable.bitbuckethelper.domain.shared.ActivityVersion
import com.mindtable.bitbuckethelper.domain.shared.PullRequestId
import java.net.URI
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Instant
import java.util.Base64

enum class ActionSourceKind { COMMENT, TASK, THREAD }

enum class ActionObservationState { ACTIONABLE, RESOLVED, DELETED }

data class ActionObservation(
    val pullRequestId: PullRequestId,
    val sourceKind: ActionSourceKind,
    val upstreamSourceId: String,
    val activityVersion: ActivityVersion,
    val authorStableId: String,
    val authorDisplayName: String,
    val activityAt: Instant,
    val observedAt: Instant,
    val webUrl: URI,
    val state: ActionObservationState,
)

enum class ActionItemTransitionDisposition { APPLIED, IGNORED_IDENTICAL, IGNORED_STALE, REJECTED_CONFLICTING_TIMESTAMP }

data class ActionItemTransition(
    val actionItem: ActionItem,
    val facts: List<ActionItemEvent>,
    val disposition: ActionItemTransitionDisposition,
)

data class AcknowledgmentTransition(val actionItem: ActionItem, val result: AcknowledgmentResult)

sealed interface AcknowledgmentResult {
    data class Acknowledged(val activityVersion: ActivityVersion, val acknowledgedAt: Instant) : AcknowledgmentResult
    data class AlreadyAcknowledged(val activityVersion: ActivityVersion, val acknowledgedAt: Instant) : AcknowledgmentResult
    data class StaleActivityVersion(val requested: ActivityVersion, val current: ActivityVersion) : AcknowledgmentResult
    data class NotActionable(val current: ActivityVersion) : AcknowledgmentResult
    data object Missing : AcknowledgmentResult
}

data class ActionItem(
    val id: ActionItemId,
    val pullRequestId: PullRequestId,
    val sourceKind: ActionSourceKind,
    val upstreamSourceId: String,
    val activityVersion: ActivityVersion,
    val authorStableId: String,
    val authorDisplayName: String,
    val activityAt: Instant,
    val observedAt: Instant,
    val webUrl: URI,
    val sourceState: ActionObservationState,
    val acknowledgedVersion: ActivityVersion?,
    val acknowledgedAt: Instant?,
) {
    val actionable: Boolean get() = sourceState == ActionObservationState.ACTIONABLE && acknowledgedVersion != activityVersion

    fun observe(observation: ActionObservation): ActionItemTransition {
        requireStableIdentity(observation)
        if (observation.observedAt < observedAt) return transition(ActionItemTransitionDisposition.IGNORED_STALE)
        if (observation.observedAt == observedAt) {
            return if (matches(observation)) transition(ActionItemTransitionDisposition.IGNORED_IDENTICAL)
            else transition(ActionItemTransitionDisposition.REJECTED_CONFLICTING_TIMESTAMP)
        }

        val updated = copy(
            activityVersion = observation.activityVersion,
            authorStableId = observation.authorStableId,
            authorDisplayName = observation.authorDisplayName,
            activityAt = observation.activityAt,
            observedAt = observation.observedAt,
            webUrl = observation.webUrl,
            sourceState = observation.state,
            acknowledgedVersion = if (observation.activityVersion == activityVersion) acknowledgedVersion else null,
            acknowledgedAt = if (observation.activityVersion == activityVersion) acknowledgedAt else null,
        )
        return ActionItemTransition(updated, updated.transitionFactsFrom(this), ActionItemTransitionDisposition.APPLIED)
    }

    fun acknowledge(requestedVersion: ActivityVersion, acknowledgedAt: Instant): AcknowledgmentTransition = when {
        requestedVersion != activityVersion -> AcknowledgmentTransition(this, AcknowledgmentResult.StaleActivityVersion(requestedVersion, activityVersion))
        sourceState != ActionObservationState.ACTIONABLE -> AcknowledgmentTransition(this, AcknowledgmentResult.NotActionable(activityVersion))
        acknowledgedVersion == activityVersion -> AcknowledgmentTransition(this, AcknowledgmentResult.AlreadyAcknowledged(activityVersion, requireNotNull(this.acknowledgedAt)))
        else -> {
            val updated = copy(acknowledgedVersion = activityVersion, acknowledgedAt = acknowledgedAt)
            AcknowledgmentTransition(updated, AcknowledgmentResult.Acknowledged(activityVersion, acknowledgedAt))
        }
    }

    private fun transitionFactsFrom(previous: ActionItem): List<ActionItemEvent> = buildList {
        if (previous.activityVersion != activityVersion && sourceState == ActionObservationState.ACTIONABLE) {
            add(ActionItemVersionAdvanced(id, previous.activityVersion, activityVersion))
        }
        if (previous.sourceState == ActionObservationState.ACTIONABLE && sourceState != ActionObservationState.ACTIONABLE) {
            add(ActionItemClosed(id, sourceState))
        }
        if (previous.sourceState != ActionObservationState.ACTIONABLE && sourceState == ActionObservationState.ACTIONABLE) {
            add(ActionItemReopened(id, activityVersion))
        }
    }

    private fun requireStableIdentity(observation: ActionObservation) {
        require(pullRequestId == observation.pullRequestId) { "Pull request id cannot change" }
        require(sourceKind == observation.sourceKind) { "Action source kind cannot change" }
        require(upstreamSourceId == observation.upstreamSourceId) { "Upstream source id cannot change" }
    }

    private fun matches(observation: ActionObservation): Boolean =
        activityVersion == observation.activityVersion && authorStableId == observation.authorStableId &&
            authorDisplayName == observation.authorDisplayName && activityAt == observation.activityAt &&
            webUrl == observation.webUrl && sourceState == observation.state

    private fun transition(disposition: ActionItemTransitionDisposition) = ActionItemTransition(this, emptyList(), disposition)

    companion object {
        fun from(observation: ActionObservation): ActionItemTransition {
            val item = ActionItem(
                id = idFor(observation.pullRequestId, observation.sourceKind, observation.upstreamSourceId),
                pullRequestId = observation.pullRequestId,
                sourceKind = observation.sourceKind,
                upstreamSourceId = observation.upstreamSourceId,
                activityVersion = observation.activityVersion,
                authorStableId = observation.authorStableId,
                authorDisplayName = observation.authorDisplayName,
                activityAt = observation.activityAt,
                observedAt = observation.observedAt,
                webUrl = observation.webUrl,
                sourceState = observation.state,
                acknowledgedVersion = null,
                acknowledgedAt = null,
            )
            val facts = if (item.actionable) listOf(ActionItemOpened(item.id, item.activityVersion)) else emptyList()
            return ActionItemTransition(item, facts, ActionItemTransitionDisposition.APPLIED)
        }

        fun idFor(pullRequestId: PullRequestId, sourceKind: ActionSourceKind, upstreamSourceId: String): ActionItemId {
            require(upstreamSourceId.isNotBlank()) { "Upstream source id cannot be blank" }
            val material = listOf(pullRequestId.value, sourceKind.name, upstreamSourceId).joinToString("|") { "${it.length}:$it" }
            val digest = MessageDigest.getInstance("SHA-256").digest(material.toByteArray(StandardCharsets.UTF_8))
            return ActionItemId("ai_" + Base64.getUrlEncoder().withoutPadding().encodeToString(digest))
        }
    }
}

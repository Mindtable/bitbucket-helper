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
    /** A configured-user reply that acknowledged this exact external activity version. */
    val acknowledgedAt: Instant? = null,
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

class ActionItem private constructor(
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
    init {
        require(id == idFor(pullRequestId, sourceKind, upstreamSourceId)) { "Action item id must match stable identity" }
        require(activityAt <= observedAt) { "Activity time cannot be after observation time" }
        require((acknowledgedVersion == null) == (acknowledgedAt == null)) { "Acknowledgment version and time must be paired" }
        if (acknowledgedVersion != null) {
            require(acknowledgedVersion == activityVersion) { "Acknowledgment must apply to the current activity version" }
            require(requireNotNull(acknowledgedAt) >= activityAt) { "Acknowledgment time cannot be before activity time" }
        }
    }

    val actionable: Boolean get() = sourceState == ActionObservationState.ACTIONABLE && acknowledgedVersion != activityVersion

    fun observe(observation: ActionObservation): ActionItemTransition {
        requireStableIdentity(observation)
        if (observation.observedAt < observedAt) return transition(ActionItemTransitionDisposition.IGNORED_STALE)
        if (observation.observedAt == observedAt) {
            return if (matches(observation)) transition(ActionItemTransitionDisposition.IGNORED_IDENTICAL)
            else transition(ActionItemTransitionDisposition.REJECTED_CONFLICTING_TIMESTAMP)
        }

        val updated = replace(
            activityVersion = observation.activityVersion,
            authorStableId = observation.authorStableId,
            authorDisplayName = observation.authorDisplayName,
            activityAt = observation.activityAt,
            observedAt = observation.observedAt,
            webUrl = observation.webUrl,
            sourceState = observation.state,
            acknowledgedVersion = when {
                observation.acknowledgedAt != null -> observation.activityVersion
                observation.activityVersion == activityVersion -> acknowledgedVersion
                else -> null
            },
            acknowledgedAt = when {
                observation.acknowledgedAt != null && observation.activityVersion == activityVersion && acknowledgedAt != null ->
                    minOf(acknowledgedAt, observation.acknowledgedAt)
                observation.acknowledgedAt != null -> observation.acknowledgedAt
                observation.activityVersion == activityVersion -> acknowledgedAt
                else -> null
            },
        )
        return ActionItemTransition(updated, updated.transitionFactsFrom(this), ActionItemTransitionDisposition.APPLIED)
    }

    fun acknowledge(requestedVersion: ActivityVersion, acknowledgedAt: Instant): AcknowledgmentTransition = when {
        requestedVersion != activityVersion -> AcknowledgmentTransition(this, AcknowledgmentResult.StaleActivityVersion(requestedVersion, activityVersion))
        sourceState != ActionObservationState.ACTIONABLE -> AcknowledgmentTransition(this, AcknowledgmentResult.NotActionable(activityVersion))
        acknowledgedVersion == activityVersion -> AcknowledgmentTransition(this, AcknowledgmentResult.AlreadyAcknowledged(activityVersion, requireNotNull(this.acknowledgedAt)))
        else -> {
            require(acknowledgedAt >= activityAt) { "Acknowledgment time cannot be before activity time" }
            val updated = replace(acknowledgedVersion = activityVersion, acknowledgedAt = acknowledgedAt)
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
            webUrl == observation.webUrl && sourceState == observation.state &&
            (
                observation.acknowledgedAt == null ||
                    acknowledgedVersion == observation.activityVersion &&
                    acknowledgedAt == minOf(acknowledgedAt ?: observation.acknowledgedAt, observation.acknowledgedAt)
            )

    private fun transition(disposition: ActionItemTransitionDisposition) = ActionItemTransition(this, emptyList(), disposition)

    private fun replace(
        activityVersion: ActivityVersion = this.activityVersion,
        authorStableId: String = this.authorStableId,
        authorDisplayName: String = this.authorDisplayName,
        activityAt: Instant = this.activityAt,
        observedAt: Instant = this.observedAt,
        webUrl: URI = this.webUrl,
        sourceState: ActionObservationState = this.sourceState,
        acknowledgedVersion: ActivityVersion? = this.acknowledgedVersion,
        acknowledgedAt: Instant? = this.acknowledgedAt,
    ) = ActionItem(
        id = id,
        pullRequestId = pullRequestId,
        sourceKind = sourceKind,
        upstreamSourceId = upstreamSourceId,
        activityVersion = activityVersion,
        authorStableId = authorStableId,
        authorDisplayName = authorDisplayName,
        activityAt = activityAt,
        observedAt = observedAt,
        webUrl = webUrl,
        sourceState = sourceState,
        acknowledgedVersion = acknowledgedVersion,
        acknowledgedAt = acknowledgedAt,
    )

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
                acknowledgedVersion = observation.acknowledgedAt?.let { observation.activityVersion },
                acknowledgedAt = observation.acknowledgedAt,
            )
            val facts = if (item.actionable) listOf(ActionItemOpened(item.id, item.activityVersion)) else emptyList()
            return ActionItemTransition(item, facts, ActionItemTransitionDisposition.APPLIED)
        }

        fun restore(
            id: ActionItemId,
            pullRequestId: PullRequestId,
            sourceKind: ActionSourceKind,
            upstreamSourceId: String,
            activityVersion: ActivityVersion,
            authorStableId: String,
            authorDisplayName: String,
            activityAt: Instant,
            observedAt: Instant,
            webUrl: URI,
            sourceState: ActionObservationState,
            acknowledgedVersion: ActivityVersion?,
            acknowledgedAt: Instant?,
        ): ActionItem = ActionItem(
            id = id,
            pullRequestId = pullRequestId,
            sourceKind = sourceKind,
            upstreamSourceId = upstreamSourceId,
            activityVersion = activityVersion,
            authorStableId = authorStableId,
            authorDisplayName = authorDisplayName,
            activityAt = activityAt,
            observedAt = observedAt,
            webUrl = webUrl,
            sourceState = sourceState,
            acknowledgedVersion = acknowledgedVersion,
            acknowledgedAt = acknowledgedAt,
        )

        fun idFor(pullRequestId: PullRequestId, sourceKind: ActionSourceKind, upstreamSourceId: String): ActionItemId {
            require(upstreamSourceId.isNotBlank()) { "Upstream source id cannot be blank" }
            val material = listOf(pullRequestId.value, sourceKind.name, upstreamSourceId).joinToString("|") { "${it.length}:$it" }
            val digest = MessageDigest.getInstance("SHA-256").digest(material.toByteArray(StandardCharsets.UTF_8))
            return ActionItemId("ai_" + Base64.getUrlEncoder().withoutPadding().encodeToString(digest))
        }
    }
}

package com.mindtable.bitbuckethelper.domain.actionitem

import com.mindtable.bitbuckethelper.domain.shared.ActivityVersion
import com.mindtable.bitbuckethelper.domain.shared.ActionItemId
import com.mindtable.bitbuckethelper.domain.shared.PullRequestId
import java.net.URI
import java.time.Instant
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ActionItemTest {
    @Test
    fun `first actionable observation opens a deterministic action item`() {
        val transition = ActionItem.from(observation())

        assertTrue(transition.actionItem.actionable)
        assertEquals(ActionItemId("ai_ZD2WnN3cHEqKZMJZtJ5Tyo501ZoDQSMzL9m2VdhnimI"), transition.actionItem.id)
        assertEquals(listOf(ActionItemOpened(transition.actionItem.id, VERSION_1)), transition.facts)
    }

    @Test
    fun `identical replay does not create an actionable transition`() {
        val item = ActionItem.from(observation()).actionItem

        val replay = item.observe(observation())

        assertEquals(ActionItemTransitionDisposition.IGNORED_IDENTICAL, replay.disposition)
        assertEquals(emptyList<ActionItemEvent>(), replay.facts)
    }

    @Test
    fun `later version advances and emits an actionable version fact`() {
        val item = ActionItem.from(observation()).actionItem
        val acknowledged = item.acknowledge(VERSION_1, AT_2).actionItem

        val advanced = acknowledged.observe(observation(observedAt = AT_3, version = VERSION_2))

        assertTrue(advanced.actionItem.actionable)
        assertEquals(VERSION_2, advanced.actionItem.activityVersion)
        assertEquals(listOf(ActionItemVersionAdvanced(advanced.actionItem.id, VERSION_1, VERSION_2)), advanced.facts)
    }

    @Test
    fun `current version can be acknowledged exactly once`() {
        val item = ActionItem.from(observation()).actionItem

        val acknowledged = item.acknowledge(VERSION_1, AT_2)
        val repeated = acknowledged.actionItem.acknowledge(VERSION_1, AT_3)

        assertEquals(AcknowledgmentResult.Acknowledged(VERSION_1, AT_2), acknowledged.result)
        assertFalse(acknowledged.actionItem.actionable)
        assertEquals(AcknowledgmentResult.AlreadyAcknowledged(VERSION_1, AT_2), repeated.result)
    }

    @Test
    fun `upstream self reply acknowledges its exact prior external version without advancing it`() {
        val acknowledged = ActionItem.from(observation(acknowledgedAt = AT_2)).actionItem

        assertEquals(VERSION_1, acknowledged.activityVersion)
        assertEquals(VERSION_1, acknowledged.acknowledgedVersion)
        assertEquals(AT_2, acknowledged.acknowledgedAt)
        assertFalse(acknowledged.actionable)

        val laterExternal = acknowledged.observe(
            observation(observedAt = AT_3, version = VERSION_2),
        )
        assertEquals(VERSION_2, laterExternal.actionItem.activityVersion)
        assertEquals(null, laterExternal.actionItem.acknowledgedVersion)
        assertTrue(laterExternal.actionItem.actionable)
    }

    @Test
    fun `upstream acknowledgment retains an earlier manual acknowledgment of the same version`() {
        val manual = ActionItem.from(observation()).actionItem.acknowledge(VERSION_1, AT_1).actionItem

        val observed = manual.observe(
            observation(observedAt = AT_3, acknowledgedAt = AT_2),
        ).actionItem

        assertEquals(VERSION_1, observed.acknowledgedVersion)
        assertEquals(AT_1, observed.acknowledgedAt)
        assertFalse(observed.actionable)
    }

    @Test
    fun `acknowledging a non-current version is stale`() {
        val item = ActionItem.from(observation()).actionItem

        val result = item.acknowledge(VERSION_2, AT_2).result

        assertEquals(AcknowledgmentResult.StaleActivityVersion(VERSION_2, VERSION_1), result)
    }

    @Test
    fun `resolved observation closes and later unresolved observation reopens`() {
        val item = ActionItem.from(observation()).actionItem

        val closed = item.observe(observation(observedAt = AT_2, state = ActionObservationState.RESOLVED))
        val reopened = closed.actionItem.observe(observation(observedAt = AT_3, state = ActionObservationState.ACTIONABLE))

        assertFalse(closed.actionItem.actionable)
        assertEquals(listOf(ActionItemClosed(closed.actionItem.id, ActionObservationState.RESOLVED)), closed.facts)
        assertTrue(reopened.actionItem.actionable)
        assertEquals(listOf(ActionItemReopened(reopened.actionItem.id, VERSION_1)), reopened.facts)
    }

    @Test
    fun `acknowledged version stays non-actionable when it resolves and reopens unchanged`() {
        val acknowledged = ActionItem.from(observation()).actionItem
            .acknowledge(VERSION_1, AT_2).actionItem

        val closed = acknowledged.observe(
            observation(observedAt = AT_3, state = ActionObservationState.RESOLVED),
        )
        val reopened = closed.actionItem.observe(
            observation(observedAt = AT_3.plusSeconds(60), state = ActionObservationState.ACTIONABLE),
        )

        assertEquals(VERSION_1, reopened.actionItem.acknowledgedVersion)
        assertEquals(AT_2, reopened.actionItem.acknowledgedAt)
        assertFalse(reopened.actionItem.actionable)
        assertEquals(listOf(ActionItemReopened(reopened.actionItem.id, VERSION_1)), reopened.facts)
    }

    @Test
    fun `closed item rejects acknowledgment as not actionable`() {
        val item = ActionItem.from(observation()).actionItem
        val closed = item.observe(observation(observedAt = AT_2, state = ActionObservationState.DELETED)).actionItem

        assertEquals(AcknowledgmentResult.NotActionable(VERSION_1), closed.acknowledge(VERSION_1, AT_3).result)
    }

    @Test
    fun `older observations cannot regress metadata or state`() {
        val item = ActionItem.from(observation(observedAt = AT_2, authorDisplayName = "Current")).actionItem

        val stale = item.observe(observation(observedAt = AT_1, authorDisplayName = "Stale", state = ActionObservationState.RESOLVED))

        assertEquals(ActionItemTransitionDisposition.IGNORED_STALE, stale.disposition)
        assertEquals("Current", stale.actionItem.authorDisplayName)
        assertTrue(stale.actionItem.actionable)
    }

    @Test
    fun `equal time conflicting observations are explicitly rejected`() {
        val item = ActionItem.from(observation()).actionItem

        val conflict = item.observe(observation(authorDisplayName = "Different"))

        assertEquals(ActionItemTransitionDisposition.REJECTED_CONFLICTING_TIMESTAMP, conflict.disposition)
    }

    @Test
    fun `changing stable source identity is rejected`() {
        val item = ActionItem.from(observation()).actionItem

        assertThrows(IllegalArgumentException::class.java) {
            item.observe(observation(observedAt = AT_2, sourceKind = ActionSourceKind.THREAD))
        }
    }

    @Test
    fun `source kinds derive distinct stable action identities`() {
        val comment = ActionItem.idFor(PULL_REQUEST_ID, ActionSourceKind.COMMENT, "source-1")
        val thread = ActionItem.idFor(PULL_REQUEST_ID, ActionSourceKind.THREAD, "source-1")

        assertFalse(comment == thread)
    }

    @Test
    fun `restore rejects an id that does not correspond to stable identity`() {
        val item = ActionItem.from(observation()).actionItem

        assertThrows(IllegalArgumentException::class.java) {
            restore(item, id = ActionItemId("ai_unrelated"))
        }
    }

    @Test
    fun `restore rejects invalid acknowledgement pairing and timestamp order`() {
        val item = ActionItem.from(observation()).actionItem

        assertThrows(IllegalArgumentException::class.java) {
            restore(item, acknowledgedVersion = VERSION_1, acknowledgedAt = null)
        }
        assertThrows(IllegalArgumentException::class.java) {
            restore(item, activityAt = AT_2, observedAt = AT_1)
        }
    }

    private fun restore(
        item: ActionItem,
        id: ActionItemId = item.id,
        activityAt: Instant = item.activityAt,
        observedAt: Instant = item.observedAt,
        acknowledgedVersion: ActivityVersion? = item.acknowledgedVersion,
        acknowledgedAt: Instant? = item.acknowledgedAt,
    ) = ActionItem.restore(
        id = id,
        pullRequestId = item.pullRequestId,
        sourceKind = item.sourceKind,
        upstreamSourceId = item.upstreamSourceId,
        activityVersion = item.activityVersion,
        authorStableId = item.authorStableId,
        authorDisplayName = item.authorDisplayName,
        activityAt = activityAt,
        observedAt = observedAt,
        webUrl = item.webUrl,
        sourceState = item.sourceState,
        acknowledgedVersion = acknowledgedVersion,
        acknowledgedAt = acknowledgedAt,
    )

    private fun observation(
        observedAt: Instant = AT_1,
        version: ActivityVersion = VERSION_1,
        state: ActionObservationState = ActionObservationState.ACTIONABLE,
        sourceKind: ActionSourceKind = ActionSourceKind.COMMENT,
        authorDisplayName: String = "Ada",
        acknowledgedAt: Instant? = null,
    ) = ActionObservation(
        pullRequestId = PULL_REQUEST_ID,
        sourceKind = sourceKind,
        upstreamSourceId = "comment-1",
        activityVersion = version,
        authorStableId = "{ada}",
        authorDisplayName = authorDisplayName,
        activityAt = AT_0,
        observedAt = observedAt,
        webUrl = URI("https://bitbucket.org/acme/alpha/pull-requests/42#comment-1"),
        state = state,
        acknowledgedAt = acknowledgedAt,
    )

    private companion object {
        val PULL_REQUEST_ID = PullRequestId("pr_alpha-42")
        val VERSION_1 = ActivityVersion("av_comment-1-v1")
        val VERSION_2 = ActivityVersion("av_comment-1-v2")
        val AT_0 = Instant.parse("2026-08-15T10:00:00Z")
        val AT_1 = Instant.parse("2026-08-15T10:01:00Z")
        val AT_2 = Instant.parse("2026-08-15T10:02:00Z")
        val AT_3 = Instant.parse("2026-08-15T10:03:00Z")
    }
}

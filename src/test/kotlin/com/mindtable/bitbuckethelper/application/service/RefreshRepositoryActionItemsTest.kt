package com.mindtable.bitbuckethelper.task7

import com.mindtable.bitbuckethelper.application.model.*
import com.mindtable.bitbuckethelper.application.port.outbound.NotificationIntentPolicy
import com.mindtable.bitbuckethelper.application.port.outbound.PostCommitNotificationDispatcher
import com.mindtable.bitbuckethelper.domain.shared.*
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.net.URI
import java.time.Clock
import java.time.ZoneOffset

class RefreshRepositoryActionItemsTest {
    @Test fun `initial actionable activity and green transition create atomic idempotent intents`() = runTest {
        val f = RefreshFixture(); f.configure(); f.gateway.summaries = listOf(f.summary(1)); f.gateway.activities = mapOf(1L to listOf(activity("comment-1", "av_one")))
        f.gateway.builds = { listOf(GatewayBuildObservation("ci", GatewayBuildStatus.FAILED, now)) }
        val facts = mutableListOf<NotificationTransitionFact>()
        f.policy = recordingPolicy(facts)
        f.service().refresh(RefreshRepositoryCommand(repoId))
        assertEquals(1, f.persistence.inTransaction { actionItemStore.listByPullRequest(com.mindtable.bitbuckethelper.application.service.ObservationAssembler.idFor(repoId.value, 1)).size })
        assertTrue(facts.any { it is NotificationTransitionFact.InitialRepositoryDigest })
        assertTrue(facts.any { it is NotificationTransitionFact.ActionableActivity })
        assertEquals(listOf(now, now), facts.map { it.createdAt })
        assertEquals(2, f.dispatched.single().size)
        f.clock = Clock.fixed(now.plusSeconds(60), ZoneOffset.UTC)
        f.gateway.builds = { listOf(GatewayBuildObservation("ci", GatewayBuildStatus.SUCCESSFUL, now.plusSeconds(60))) }
        f.service().refresh(RefreshRepositoryCommand(repoId))
        assertTrue(facts.any { it is NotificationTransitionFact.BuildsBecameGreen })
        assertEquals(now.plusSeconds(60), facts.filterIsInstance<NotificationTransitionFact.BuildsBecameGreen>().single().createdAt)
        assertEquals(2, f.dispatched.size)
        f.service().refresh(RefreshRepositoryCommand(repoId))
        assertEquals(2, f.dispatched.size, "replay must not dispatch existing intents")
    }

    @Test fun `activity version advance remains actionable and produces a new intent without body reads`() = runTest {
        val f = RefreshFixture(); f.configure(); f.gateway.summaries = listOf(f.summary(1)); val facts = mutableListOf<NotificationTransitionFact>(); f.policy = recordingPolicy(facts)
        f.gateway.activities = mapOf(1L to listOf(activity("comment-1", "av_one"))); f.service().refresh(RefreshRepositoryCommand(repoId))
        f.clock = Clock.fixed(now.plusSeconds(60), ZoneOffset.UTC); f.gateway.activities = mapOf(1L to listOf(activity("comment-1", "av_two"))); f.service().refresh(RefreshRepositoryCommand(repoId))
        val item = f.persistence.inTransaction { actionItemStore.listActionable().single() }
        assertEquals(ActivityVersion("av_two"), item.activityVersion)
        assertEquals(2, facts.count { it is NotificationTransitionFact.ActionableActivity })
    }

    @Test fun `policy failure rolls back pull request action checkpoint and intents`() = runTest {
        val f = RefreshFixture(); f.configure(); f.gateway.summaries = listOf(f.summary(1)); f.gateway.activities = mapOf(1L to listOf(activity("comment-1", "av_one")))
        f.policy = object : NotificationIntentPolicy {
            override fun createIntents(facts: List<NotificationTransitionFact>): List<NewNotificationIntent> = error("policy failed")
            override fun createReminder(fact: ReminderNotificationFact): NewNotificationIntent = error("not used")
        }
        assertThrows(IllegalStateException::class.java) { kotlinx.coroutines.runBlocking { f.service().refresh(RefreshRepositoryCommand(repoId)) } }
        assertTrue(f.persistence.inTransaction { pullRequestStore.listByRepository(repoId, true) }.isEmpty())
        assertTrue(f.persistence.inTransaction { actionItemStore.listActionable() }.isEmpty())
        assertNull(f.persistence.inTransaction { synchronizationCheckpointStore.find(repoId) })
    }

    @Test fun `dispatcher failure occurs after commit and committed state remains`() = runTest {
        val f = RefreshFixture(); f.configure(); f.gateway.summaries = listOf(f.summary(1)); f.gateway.activities = mapOf(1L to listOf(activity("comment-1", "av_one"))); f.policy = recordingPolicy(mutableListOf())
        f.dispatcher = PostCommitNotificationDispatcher { ids -> ids.forEach { assertNotNull(f.persistence.inTransaction { notificationIntentStore.find(it) }) }; error("dispatch failed") }
        assertThrows(IllegalStateException::class.java) { kotlinx.coroutines.runBlocking { f.service().refresh(RefreshRepositoryCommand(repoId)) } }
        assertEquals(1, f.persistence.inTransaction { actionItemStore.listActionable().size })
        assertEquals(SynchronizationAttemptOutcome.SUCCEEDED, f.persistence.inTransaction { synchronizationCheckpointStore.find(repoId) }!!.lastAttemptOutcome)
    }

    @Test fun `failed list before first authoritative snapshot does not consume initial digest`() = runTest {
        val f = RefreshFixture(); f.configure(); f.gateway.listResult = GatewayResult.Failure(networkFailure)
        assertTrue(f.service().refresh(RefreshRepositoryCommand(repoId)) is RefreshRepositoryResult.Failed)
        val facts = mutableListOf<NotificationTransitionFact>(); f.policy = recordingPolicy(facts); f.gateway.listResult = null
        f.service().refresh(RefreshRepositoryCommand(repoId))
        assertEquals(1, facts.count { it is NotificationTransitionFact.InitialRepositoryDigest })
        f.service().refresh(RefreshRepositoryCommand(repoId))
        assertEquals(1, facts.count { it is NotificationTransitionFact.InitialRepositoryDigest })
    }

    @Test fun `empty and only-existing intent sets never invoke dispatcher`() = runTest {
        val empty = RefreshFixture(); empty.configure(); empty.service().refresh(RefreshRepositoryCommand(repoId)); assertTrue(empty.dispatched.isEmpty())
        val replay = RefreshFixture(); replay.configure(); replay.gateway.summaries = listOf(replay.summary(1)); replay.gateway.activities = mapOf(1L to listOf(activity("comment-1", "av_one"))); replay.policy = recordingPolicy(mutableListOf())
        replay.service().refresh(RefreshRepositoryCommand(repoId)); replay.dispatched.clear(); replay.service().refresh(RefreshRepositoryCommand(repoId))
        assertTrue(replay.dispatched.isEmpty())
    }

    @Test fun `transaction rollback restores authoritative deactivation`() = runTest {
        val f = RefreshFixture(); f.configure(); f.gateway.summaries = listOf(f.summary(1)); f.service().refresh(RefreshRepositoryCommand(repoId))
        f.gateway.summaries = emptyList(); f.clock = Clock.fixed(now.plusSeconds(60), ZoneOffset.UTC)
        f.policy = object : NotificationIntentPolicy {
            override fun createIntents(facts: List<NotificationTransitionFact>): List<NewNotificationIntent> = error("policy failed")
            override fun createReminder(fact: ReminderNotificationFact): NewNotificationIntent = error("not used")
        }
        assertThrows(IllegalStateException::class.java) { kotlinx.coroutines.runBlocking { f.service().refresh(RefreshRepositoryCommand(repoId)) } }
        assertTrue(f.persistence.inTransaction { pullRequestStore.listByRepository(repoId, true) }.single().active)
    }

    @Test fun `stopped build normalizes to failed and still permits later green transition`() = runTest {
        val f = RefreshFixture(); f.configure(); f.gateway.summaries = listOf(f.summary(1)); val facts = mutableListOf<NotificationTransitionFact>(); f.policy = recordingPolicy(facts)
        f.gateway.builds = { listOf(GatewayBuildObservation("ci", GatewayBuildStatus.STOPPED, now)) }; f.service().refresh(RefreshRepositoryCommand(repoId))
        assertEquals(BuildState.FAILED, f.persistence.inTransaction { pullRequestStore.listByRepository(repoId, true) }.single().builds.single().state)
        f.clock = Clock.fixed(now.plusSeconds(60), ZoneOffset.UTC); f.gateway.builds = { listOf(GatewayBuildObservation("ci", GatewayBuildStatus.SUCCESSFUL, now.plusSeconds(60))) }; f.service().refresh(RefreshRepositoryCommand(repoId))
        assertTrue(facts.any { it is NotificationTransitionFact.BuildsBecameGreen })
    }

    @Test fun `deleted and resolved activity normalize closed and can reopen actionable`() = runTest {
        for (terminal in listOf(
            GatewayActivityObservation(GatewayActivityKind.COMMENT, "comment", "reviewer", "Reviewer", now.minusSeconds(5), ActivityVersion("av_one"), false, true, URI("https://example.test/comment")),
            GatewayActivityObservation(GatewayActivityKind.COMMENT, "comment", "reviewer", "Reviewer", now.minusSeconds(5), ActivityVersion("av_one"), true, false, URI("https://example.test/comment")),
        )) {
            val f = RefreshFixture(); f.configure(); f.gateway.summaries = listOf(f.summary(1)); f.gateway.activities = mapOf(1L to listOf(terminal)); val facts = mutableListOf<NotificationTransitionFact>(); f.policy = recordingPolicy(facts); f.service().refresh(RefreshRepositoryCommand(repoId))
            assertEquals(ActionItemState.CLOSED, f.persistence.inTransaction { actionItemStore.listByPullRequest(com.mindtable.bitbuckethelper.application.service.ObservationAssembler.idFor(repoId.value, 1)).single().state })
            f.clock = Clock.fixed(now.plusSeconds(60), ZoneOffset.UTC); f.gateway.activities = mapOf(1L to listOf(activity("comment", "av_two"))); f.service().refresh(RefreshRepositoryCommand(repoId))
            assertEquals(1, f.persistence.inTransaction { actionItemStore.listActionable() }.size)
            assertTrue(facts.any { it is NotificationTransitionFact.ActionableActivity })
        }
    }

    @Test fun `activity failure preserves every existing pull request and action field`() = runTest {
        val f = RefreshFixture(); f.configure(); f.gateway.summaries = listOf(f.summary(1)); f.gateway.activities = mapOf(1L to listOf(activity("comment", "av_one"))); f.service().refresh(RefreshRepositoryCommand(repoId))
        val prId = com.mindtable.bitbuckethelper.application.service.ObservationAssembler.idFor(repoId.value, 1)
        val beforePr = f.persistence.inTransaction { pullRequestStore.find(prId) }
        val beforeAction = f.persistence.inTransaction { actionItemStore.listByPullRequest(prId).single() }
        f.clock = Clock.fixed(now.plusSeconds(60), ZoneOffset.UTC); f.gateway.detailOverrides[1] = f.gateway.detail(1).copy(title = "changed")
        f.gateway.activities = mapOf(1L to listOf(activity("comment", "av_two"))); f.gateway.endpointFailures["activity" to 1L] = GatewayResult.Failure(networkFailure)
        assertTrue(f.service().refresh(RefreshRepositoryCommand(repoId)) is RefreshRepositoryResult.PartiallySucceeded)
        assertEquals(beforePr, f.persistence.inTransaction { pullRequestStore.find(prId) })
        assertEquals(beforeAction, f.persistence.inTransaction { actionItemStore.listByPullRequest(prId).single() })
    }

    @Test fun `new fact mapped to existing intent does not dispatch`() = runTest {
        val f = RefreshFixture(); f.configure(); f.gateway.summaries = listOf(f.summary(1)); f.gateway.activities = mapOf(1L to listOf(activity("comment", "av_one")))
        f.policy = constantIntentPolicy(); f.service().refresh(RefreshRepositoryCommand(repoId)); f.dispatched.clear()
        f.clock = Clock.fixed(now.plusSeconds(60), ZoneOffset.UTC); f.gateway.activities = mapOf(1L to listOf(activity("comment", "av_two"))); f.service().refresh(RefreshRepositoryCommand(repoId))
        assertTrue(f.dispatched.isEmpty())
    }

    private fun activity(id: String, version: String) = GatewayActivityObservation(GatewayActivityKind.COMMENT, id, "reviewer", "Reviewer", now.minusSeconds(5), ActivityVersion(version), false, false, URI("https://bitbucket.org/team/repo/pull-requests/1#$id"))
    private fun recordingPolicy(seen: MutableList<NotificationTransitionFact>) = object : NotificationIntentPolicy {
        override fun createIntents(facts: List<NotificationTransitionFact>): List<NewNotificationIntent> {
            seen += facts
            return facts.map { fact -> NewNotificationIntent(NotificationRequest(NotificationDeliveryKey("${fact.javaClass.simpleName}-${fact}"), "Title", "safe summary", fact.repositoryWebUrl, NotificationSound.DEFAULT), fact.createdAt) }
        }
        override fun createReminder(fact: ReminderNotificationFact): NewNotificationIntent = error("not used")
    }
    private fun constantIntentPolicy() = object : NotificationIntentPolicy {
        override fun createIntents(facts: List<NotificationTransitionFact>) = facts.map { fact -> NewNotificationIntent(NotificationRequest(NotificationDeliveryKey("constant"), "Title", "safe summary", null, NotificationSound.DEFAULT), fact.createdAt) }
        override fun createReminder(fact: ReminderNotificationFact): NewNotificationIntent = error("not used")
    }
}

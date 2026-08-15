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
        assertEquals(2, f.dispatched.single().size)
        f.clock = Clock.fixed(now.plusSeconds(60), ZoneOffset.UTC)
        f.gateway.builds = { listOf(GatewayBuildObservation("ci", GatewayBuildStatus.SUCCESSFUL, now.plusSeconds(60))) }
        f.service().refresh(RefreshRepositoryCommand(repoId))
        assertTrue(facts.any { it is NotificationTransitionFact.BuildsBecameGreen })
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

    private fun activity(id: String, version: String) = GatewayActivityObservation(GatewayActivityKind.COMMENT, id, "reviewer", "Reviewer", now.minusSeconds(5), ActivityVersion(version), false, false, URI("https://bitbucket.org/team/repo/pull-requests/1#$id"))
    private fun recordingPolicy(seen: MutableList<NotificationTransitionFact>) = object : NotificationIntentPolicy {
        override fun createIntents(facts: List<NotificationTransitionFact>): List<NewNotificationIntent> {
            seen += facts
            return facts.map { fact -> NewNotificationIntent(NotificationRequest(NotificationDeliveryKey("${fact.javaClass.simpleName}-${fact}"), "Title", "safe summary", fact.repositoryWebUrl, NotificationSound.DEFAULT), now) }
        }
        override fun createReminder(fact: ReminderNotificationFact): NewNotificationIntent = error("not used")
    }
}

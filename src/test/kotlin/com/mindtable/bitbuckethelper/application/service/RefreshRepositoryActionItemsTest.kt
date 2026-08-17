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
    @Test fun `first full sync emits only one initial digest then later transitions notify normally`() = runTest {
        val f = RefreshFixture(); f.configure(); f.gateway.summaries = listOf(f.summary(1)); f.gateway.activities = mapOf(1L to listOf(activity("comment-1", "av_one")))
        val facts = mutableListOf<NotificationTransitionFact>()
        f.policy = recordingPolicy(facts)
        f.service().refresh(RefreshRepositoryCommand(repoId))
        assertEquals(1, f.persistence.inTransaction { actionItemStore.listByPullRequest(com.mindtable.bitbuckethelper.application.service.ObservationAssembler.idFor(repoId.value, 1)).size })
        assertEquals(1, facts.size)
        assertTrue(facts.single() is NotificationTransitionFact.InitialRepositoryDigest)
        assertEquals(listOf(now), facts.map { it.createdAt })
        assertEquals(1, f.dispatched.single().size)
        f.clock = Clock.fixed(now.plusSeconds(60), ZoneOffset.UTC)
        f.gateway.builds = { listOf(GatewayBuildObservation("ci", GatewayBuildStatus.FAILED, now.plusSeconds(60))) }
        f.service().refresh(RefreshRepositoryCommand(repoId))
        f.clock = Clock.fixed(now.plusSeconds(120), ZoneOffset.UTC)
        f.gateway.builds = { listOf(GatewayBuildObservation("ci", GatewayBuildStatus.SUCCESSFUL, now.plusSeconds(120))) }
        f.gateway.activities = mapOf(
            1L to listOf(activity("comment-1", "av_two").copy(activityAt = now.plusSeconds(30))),
        )
        f.service().refresh(RefreshRepositoryCommand(repoId))
        assertTrue(facts.any { it is NotificationTransitionFact.BuildsBecameGreen })
        assertTrue(facts.any { it is NotificationTransitionFact.ActionableActivity })
        assertEquals(now.plusSeconds(120), facts.filterIsInstance<NotificationTransitionFact.BuildsBecameGreen>().single().createdAt)
        assertEquals(2, f.dispatched.size)
        f.service().refresh(RefreshRepositoryCommand(repoId))
        assertEquals(2, f.dispatched.size, "replay must not dispatch existing intents")
    }

    @Test fun `initial partial sync emits nothing and the next full sync emits only its complete digest`() = runTest {
        val f = RefreshFixture(); f.configure(); f.gateway.summaries = listOf(f.summary(1), f.summary(2))
        f.gateway.detailFailures += 2L
        f.gateway.activities = mapOf(1L to listOf(activity("comment-1", "av_one")))
        val facts = mutableListOf<NotificationTransitionFact>(); f.policy = recordingPolicy(facts)

        assertTrue(f.service().refresh(RefreshRepositoryCommand(repoId)) is RefreshRepositoryResult.PartiallySucceeded)
        assertTrue(facts.isEmpty())
        assertTrue(f.dispatched.isEmpty())
        val partial = f.persistence.inTransaction { synchronizationCheckpointStore.find(repoId) }!!
        assertNull(partial.snapshotAt)
        assertNull(partial.lastSuccessAt)

        f.gateway.detailFailures.clear()
        f.clock = Clock.fixed(now.plusSeconds(60), ZoneOffset.UTC)
        assertTrue(f.service().refresh(RefreshRepositoryCommand(repoId)) is RefreshRepositoryResult.Succeeded)
        assertEquals(1, facts.size)
        assertTrue(facts.single() is NotificationTransitionFact.InitialRepositoryDigest)
        assertEquals(1, f.dispatched.single().size)
        val complete = f.persistence.inTransaction { synchronizationCheckpointStore.find(repoId) }!!
        assertEquals(now.plusSeconds(60), complete.snapshotAt)
        assertEquals(now.plusSeconds(60), complete.lastSuccessAt)
    }

    @Test fun `partial sync after a successful baseline publishes transitions from its successful pull requests`() = runTest {
        val f = RefreshFixture(); f.configure(); f.gateway.summaries = listOf(f.summary(1))
        f.gateway.activities = mapOf(1L to listOf(activity("comment-1", "av_one")))
        val facts = mutableListOf<NotificationTransitionFact>(); f.policy = recordingPolicy(facts)
        f.service().refresh(RefreshRepositoryCommand(repoId))

        f.clock = Clock.fixed(now.plusSeconds(60), ZoneOffset.UTC)
        f.gateway.summaries = listOf(f.summary(1), f.summary(2))
        f.gateway.detailFailures += 2L
        f.gateway.activities = mapOf(
            1L to listOf(activity("comment-1", "av_two").copy(activityAt = now.plusSeconds(30))),
        )

        assertTrue(f.service().refresh(RefreshRepositoryCommand(repoId)) is RefreshRepositoryResult.PartiallySucceeded)
        assertEquals(1, facts.count { it is NotificationTransitionFact.InitialRepositoryDigest })
        assertEquals(1, facts.count { it is NotificationTransitionFact.ActionableActivity })
        val partial = f.persistence.inTransaction { synchronizationCheckpointStore.find(repoId) }!!
        assertEquals(now, partial.snapshotAt)
        assertEquals(now, partial.lastSuccessAt)
        assertEquals(now.plusSeconds(60), partial.lastAttemptAt)
    }

    @Test fun `thread root own acknowledgment and later external reply keep one chronological action identity`() = runTest {
        val f = RefreshFixture(); f.configure(); f.gateway.summaries = listOf(f.summary(1))
        val facts = mutableListOf<NotificationTransitionFact>(); f.policy = recordingPolicy(facts)
        val root = threadActivity("501", "501", "reviewer", "av_root", now.minusSeconds(30))
        f.gateway.activities = mapOf(1L to listOf(root))
        f.service().refresh(RefreshRepositoryCommand(repoId))

        val pullRequestId = com.mindtable.bitbuckethelper.application.service.ObservationAssembler.idFor(repoId.value, 1)
        val initial = f.persistence.inTransaction { actionItemStore.listByPullRequest(pullRequestId).single() }
        assertEquals("THREAD", initial.sourceKind)
        assertEquals("501", initial.upstreamSourceId)
        assertEquals(ActionItemState.OPEN, initial.state)

        val externalReply = threadActivity("501", "502", "reviewer", "av_reply_502", now.minusSeconds(20))
        val ownReply = threadActivity("501", "503", "user-1", "av_reply_503", now.minusSeconds(10))
        f.clock = Clock.fixed(now.plusSeconds(60), ZoneOffset.UTC)
        f.gateway.activities = mapOf(1L to listOf(ownReply, root, externalReply))
        f.service().refresh(RefreshRepositoryCommand(repoId))

        val acknowledged = f.persistence.inTransaction { actionItemStore.listByPullRequest(pullRequestId).single() }
        assertEquals(initial.id, acknowledged.id)
        assertEquals(ActionItemState.ACKNOWLEDGED, acknowledged.state)
        assertEquals(ActivityVersion("av_reply_502"), acknowledged.activityVersion)
        assertEquals(ActivityVersion("av_reply_502"), acknowledged.acknowledgedVersion)
        assertEquals(URI("https://bitbucket.org/team/repo/pull-requests/1#comment-502"), acknowledged.webUrl)

        val laterExternal = threadActivity("501", "504", "reviewer", "av_reply_504", now.plusSeconds(10))
        f.clock = Clock.fixed(now.plusSeconds(120), ZoneOffset.UTC)
        f.gateway.activities = mapOf(1L to listOf(laterExternal, ownReply, root, externalReply))
        f.service().refresh(RefreshRepositoryCommand(repoId))

        val reopened = f.persistence.inTransaction { actionItemStore.listByPullRequest(pullRequestId).single() }
        assertEquals(initial.id, reopened.id)
        assertEquals(ActionItemState.OPEN, reopened.state)
        assertEquals(ActivityVersion("av_reply_504"), reopened.activityVersion)
        assertNull(reopened.acknowledgedVersion)
        assertEquals(URI("https://bitbucket.org/team/repo/pull-requests/1#comment-504"), reopened.webUrl)
        assertEquals(1, facts.count { it is NotificationTransitionFact.ActionableActivity })
    }

    @Test fun `shuffled complete thread and incremental observations preserve the same earliest own acknowledgment`() = runTest {
        val root = threadActivity("801", "801", "reviewer", "av_root", now.minusSeconds(30))
        val externalReply = threadActivity("801", "802", "reviewer", "av_external", now.minusSeconds(20))
        val earlierOwnReply = threadActivity("801", "803", "user-1", "av_own_earlier", now.minusSeconds(10))
        val laterOwnReply = threadActivity("801", "804", "user-1", "av_own_later", now.minusSeconds(5))

        val complete = RefreshFixture(); complete.configure(); complete.gateway.summaries = listOf(complete.summary(1))
        complete.gateway.activities = mapOf(1L to listOf(laterOwnReply, root, earlierOwnReply, externalReply))
        complete.service().refresh(RefreshRepositoryCommand(repoId))

        val incremental = RefreshFixture(); incremental.configure(); incremental.gateway.summaries = listOf(incremental.summary(1))
        listOf(
            listOf(root),
            listOf(root, externalReply),
            listOf(root, externalReply, earlierOwnReply),
            listOf(laterOwnReply, root, earlierOwnReply, externalReply),
        ).forEachIndexed { index, observations ->
            incremental.clock = Clock.fixed(now.plusSeconds(index * 60L), ZoneOffset.UTC)
            incremental.gateway.activities = mapOf(1L to observations)
            incremental.service().refresh(RefreshRepositoryCommand(repoId))
        }

        val pullRequestId = com.mindtable.bitbuckethelper.application.service.ObservationAssembler.idFor(repoId.value, 1)
        val completeItem = complete.persistence.inTransaction { actionItemStore.listByPullRequest(pullRequestId).single() }
        val incrementalItem = incremental.persistence.inTransaction { actionItemStore.listByPullRequest(pullRequestId).single() }
        assertEquals(earlierOwnReply.activityAt, completeItem.acknowledgedAt)
        assertEquals(earlierOwnReply.activityAt, incrementalItem.acknowledgedAt)
        assertEquals(completeItem.copy(observedAt = incrementalItem.observedAt), incrementalItem)
    }

    @Test fun `own root comment never creates an actionable item`() = runTest {
        val f = RefreshFixture(); f.configure(); f.gateway.summaries = listOf(f.summary(1))
        val facts = mutableListOf<NotificationTransitionFact>(); f.policy = recordingPolicy(facts)
        f.gateway.activities = mapOf(
            1L to listOf(threadActivity("601", "601", "user-1", "av_own_root", now.minusSeconds(5))),
        )

        f.service().refresh(RefreshRepositoryCommand(repoId))

        assertTrue(f.persistence.inTransaction { actionItemStore.listActionable() }.isEmpty())
        assertTrue(
            f.persistence.inTransaction {
                actionItemStore.listByPullRequest(
                    com.mindtable.bitbuckethelper.application.service.ObservationAssembler.idFor(repoId.value, 1),
                )
            }.isEmpty(),
        )
        assertEquals(0, facts.filterIsInstance<NotificationTransitionFact.InitialRepositoryDigest>().single().actionableItemCount)

        f.clock = Clock.fixed(now.plusSeconds(60), ZoneOffset.UTC)
        f.gateway.activities = mapOf(
            1L to listOf(
                threadActivity("601", "602", "reviewer", "av_external_reply", now.plusSeconds(30)),
                threadActivity("601", "601", "user-1", "av_own_root", now.minusSeconds(5)),
            ),
        )
        f.service().refresh(RefreshRepositoryCommand(repoId))

        val created = f.persistence.inTransaction {
            actionItemStore.listByPullRequest(
                com.mindtable.bitbuckethelper.application.service.ObservationAssembler.idFor(repoId.value, 1),
            ).single()
        }
        assertEquals("THREAD", created.sourceKind)
        assertEquals("601", created.upstreamSourceId)
        assertEquals(ActivityVersion("av_external_reply"), created.activityVersion)
        assertEquals(ActionItemState.OPEN, created.state)
    }

    @Test fun `first snapshot with a later own reply emits a zero-count digest and stores the external version acknowledged`() = runTest {
        val f = RefreshFixture(); f.configure(); f.gateway.summaries = listOf(f.summary(1))
        val facts = mutableListOf<NotificationTransitionFact>(); f.policy = recordingPolicy(facts)
        f.gateway.activities = mapOf(
            1L to listOf(
                threadActivity("701", "702", "user-1", "av_own_reply", now.minusSeconds(5)),
                threadActivity("701", "701", "reviewer", "av_external_root", now.minusSeconds(10)),
            ),
        )

        f.service().refresh(RefreshRepositoryCommand(repoId))

        val item = f.persistence.inTransaction {
            actionItemStore.listByPullRequest(
                com.mindtable.bitbuckethelper.application.service.ObservationAssembler.idFor(repoId.value, 1),
            ).single()
        }
        assertEquals(ActionItemState.ACKNOWLEDGED, item.state)
        assertEquals(ActivityVersion("av_external_root"), item.activityVersion)
        assertEquals(ActivityVersion("av_external_root"), item.acknowledgedVersion)
        val digest = facts.single() as NotificationTransitionFact.InitialRepositoryDigest
        assertEquals(0, digest.actionableItemCount)
        assertEquals(1, f.dispatched.single().size)
    }

    @Test fun `activity version advance remains actionable and produces a new intent without body reads`() = runTest {
        val f = RefreshFixture(); f.configure(); f.gateway.summaries = listOf(f.summary(1)); val facts = mutableListOf<NotificationTransitionFact>(); f.policy = recordingPolicy(facts)
        f.gateway.activities = mapOf(1L to listOf(activity("comment-1", "av_one"))); f.service().refresh(RefreshRepositoryCommand(repoId))
        f.clock = Clock.fixed(now.plusSeconds(60), ZoneOffset.UTC); f.gateway.activities = mapOf(1L to listOf(activity("comment-1", "av_two"))); f.service().refresh(RefreshRepositoryCommand(repoId))
        val item = f.persistence.inTransaction { actionItemStore.listActionable().single() }
        assertEquals(ActivityVersion("av_two"), item.activityVersion)
        assertEquals(1, facts.count { it is NotificationTransitionFact.ActionableActivity })
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

    @Test fun `acknowledged resolved activity reopening at the same version does not notify again`() = runTest {
        val f = RefreshFixture(); f.configure(); f.gateway.summaries = listOf(f.summary(1))
        val facts = mutableListOf<NotificationTransitionFact>(); f.policy = recordingPolicy(facts)
        f.gateway.activities = mapOf(1L to listOf(activity("comment", "av_one")))
        f.service().refresh(RefreshRepositoryCommand(repoId))
        val pullRequestId = com.mindtable.bitbuckethelper.application.service.ObservationAssembler.idFor(repoId.value, 1)
        val initial = f.persistence.inTransaction { actionItemStore.listByPullRequest(pullRequestId).single() }
        f.persistence.inTransaction {
            actionItemStore.acknowledge(initial.id, initial.activityVersion, now)
        }
        val dispatchCountAfterInitial = f.dispatched.size

        f.clock = Clock.fixed(now.plusSeconds(60), ZoneOffset.UTC)
        f.gateway.activities = mapOf(
            1L to listOf(
                GatewayActivityObservation(
                    GatewayActivityKind.COMMENT,
                    "comment",
                    "reviewer",
                    "Reviewer",
                    now.minusSeconds(5),
                    ActivityVersion("av_one"),
                    resolved = true,
                    deleted = false,
                    URI("https://bitbucket.org/team/repo/pull-requests/1#comment"),
                ),
            ),
        )
        f.service().refresh(RefreshRepositoryCommand(repoId))

        f.clock = Clock.fixed(now.plusSeconds(120), ZoneOffset.UTC)
        f.gateway.activities = mapOf(1L to listOf(activity("comment", "av_one")))
        f.service().refresh(RefreshRepositoryCommand(repoId))

        val reopened = f.persistence.inTransaction { actionItemStore.listByPullRequest(pullRequestId).single() }
        assertEquals(ActionItemState.ACKNOWLEDGED, reopened.state)
        assertEquals(ActivityVersion("av_one"), reopened.acknowledgedVersion)
        assertTrue(f.persistence.inTransaction { actionItemStore.listActionable() }.isEmpty())
        assertEquals(0, facts.count { it is NotificationTransitionFact.ActionableActivity })
        assertEquals(dispatchCountAfterInitial, f.dispatched.size)
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
    private fun threadActivity(rootId: String, commentId: String, actor: String, version: String, at: java.time.Instant) =
        GatewayActivityObservation(
            if (rootId == commentId) GatewayActivityKind.COMMENT else GatewayActivityKind.REPLY,
            rootId,
            actor,
            if (actor == "user-1") "Current User" else "Reviewer",
            at,
            ActivityVersion(version),
            false,
            false,
            URI("https://bitbucket.org/team/repo/pull-requests/1#comment-$commentId"),
        )
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

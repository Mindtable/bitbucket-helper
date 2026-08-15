package com.mindtable.bitbuckethelper.task7

import com.mindtable.bitbuckethelper.adapter.outbound.persistence.memory.InMemoryApplicationPersistence
import com.mindtable.bitbuckethelper.application.model.*
import com.mindtable.bitbuckethelper.application.port.outbound.*
import com.mindtable.bitbuckethelper.application.service.RefreshRepositoryService
import com.mindtable.bitbuckethelper.application.service.ObservationAssembler
import com.mindtable.bitbuckethelper.domain.shared.*
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.net.URI
import java.time.*

class RefreshRepositoryServiceTest {
    @Test fun `successful complete list stores seven-check pull request checkpoint and replay is idempotent`() = runTest {
        val fixture = RefreshFixture(); fixture.configure(); fixture.gateway.summaries = listOf(fixture.summary(1))
        val first = fixture.service().refresh(RefreshRepositoryCommand(repoId))
        val second = fixture.service().refresh(RefreshRepositoryCommand(repoId))
        assertTrue(first is RefreshRepositoryResult.Succeeded); assertTrue(second is RefreshRepositoryResult.Succeeded)
        val prs = fixture.persistence.inTransaction { pullRequestStore.listByRepository(repoId, true) }
        assertEquals(1, prs.size); assertTrue(prs.single().active)
        assertEquals(7, (prs.single().readiness as StoredReadiness.Available).checks.size)
        val sync = fixture.persistence.inTransaction { synchronizationCheckpointStore.find(repoId) }!!
        assertEquals(SynchronizationAttemptOutcome.SUCCEEDED, sync.lastAttemptOutcome); assertEquals(now, sync.lastSuccessAt)
    }

    @Test fun `authoritative empty list deactivates while failed list preserves last known state`() = runTest {
        val fixture = RefreshFixture(); fixture.configure(); fixture.gateway.summaries = listOf(fixture.summary(1)); fixture.service().refresh(RefreshRepositoryCommand(repoId))
        fixture.gateway.summaries = emptyList(); fixture.clock = Clock.fixed(now.plusSeconds(60), ZoneOffset.UTC); fixture.service().refresh(RefreshRepositoryCommand(repoId))
        assertFalse(fixture.persistence.inTransaction { pullRequestStore.listByRepository(repoId, true) }.single().active)
        fixture.gateway.summaries = listOf(fixture.summary(1)); fixture.clock = Clock.fixed(now.plusSeconds(120), ZoneOffset.UTC); fixture.service().refresh(RefreshRepositoryCommand(repoId))
        fixture.gateway.listResult = GatewayResult.Failure(networkFailure); fixture.clock = Clock.fixed(now.plusSeconds(180), ZoneOffset.UTC)
        val result = fixture.service().refresh(RefreshRepositoryCommand(repoId))
        assertTrue(result is RefreshRepositoryResult.Failed)
        assertTrue(fixture.persistence.inTransaction { pullRequestStore.listByRepository(repoId, true) }.single().active)
    }

    @Test fun `one pull request detail failure is partial continues others and preserves failed pull request`() = runTest {
        val fixture = RefreshFixture(); fixture.configure(); fixture.gateway.summaries = listOf(fixture.summary(1)); fixture.service().refresh(RefreshRepositoryCommand(repoId))
        fixture.gateway.summaries = listOf(fixture.summary(1), fixture.summary(2)); fixture.gateway.detailFailures += 1L
        fixture.clock = Clock.fixed(now.plusSeconds(60), ZoneOffset.UTC)
        val result = fixture.service().refresh(RefreshRepositoryCommand(repoId))
        assertTrue(result is RefreshRepositoryResult.PartiallySucceeded)
        val prs = fixture.persistence.inTransaction { pullRequestStore.listByRepository(repoId, true) }
        assertEquals(setOf(1L, 2L), prs.map { it.upstreamNumber }.toSet())
        assertEquals(now, prs.single { it.upstreamNumber == 1L }.observedAt)
        assertTrue(prs.all { it.active })
    }

    @Test fun `missing or removed repository is not refreshed`() = runTest {
        val fixture = RefreshFixture()
        assertEquals(RefreshRepositoryResult.RepositoryNotConfigured(repoId), fixture.service().refresh(RefreshRepositoryCommand(repoId)))
    }

    @Test fun `authoritative list not found is typed failed and never mutates pull requests or calls details`() = runTest {
        val f = RefreshFixture(); f.configure(); f.gateway.listResult = GatewayResult.NotFound
        val result = f.service().refresh(RefreshRepositoryCommand(repoId)) as RefreshRepositoryResult.Failed
        assertEquals(SynchronizationFailure(SynchronizationFailureCategory.UPSTREAM, false, null), result.failure)
        assertTrue(f.gateway.detailRequests.isEmpty())
        assertTrue(f.persistence.inTransaction { pullRequestStore.listByRepository(repoId, true) }.isEmpty())
        assertTrue(f.persistence.inTransaction { actionItemStore.listActionable() }.isEmpty())
    }

    @Test fun `duplicate stable summary is one malformed attempt while distinct pull requests continue`() = runTest {
        val f = RefreshFixture(); f.configure()
        f.gateway.summaries = listOf(f.summary(2), f.summary(1), f.summary(1).copy(title = "conflicting duplicate"))
        val result = f.service().refresh(RefreshRepositoryCommand(repoId)) as RefreshRepositoryResult.PartiallySucceeded
        assertEquals(2, result.partialFailure.attemptedCount)
        assertEquals(1, result.partialFailure.succeededCount)
        assertEquals(listOf(SynchronizationFailure(SynchronizationFailureCategory.MALFORMED_UPSTREAM, false, null)), result.partialFailure.failures)
        assertEquals(listOf(2L), f.gateway.detailRequests)
        val stored = f.persistence.inTransaction { pullRequestStore.listByRepository(repoId, true) }
        assertEquals(listOf(2L), stored.map { it.upstreamNumber })
    }

    @Test fun `all per pull request endpoints map failure and not found to one partial failure without mutation`() = runTest {
        val endpoints = listOf("detail", "reviewers", "builds", "tasks", "activity")
        for (endpoint in endpoints) for (result in listOf<GatewayResult<Nothing>>(GatewayResult.NotFound, GatewayResult.Failure(networkFailure))) {
            val f = RefreshFixture(); f.configure(); f.gateway.summaries = listOf(f.summary(1)); f.gateway.endpointFailures[endpoint to 1L] = result
            val refresh = f.service().refresh(RefreshRepositoryCommand(repoId)) as RefreshRepositoryResult.PartiallySucceeded
            assertEquals(PartialFailureMetadata(1, 0, listOf(if (result is GatewayResult.Failure) SynchronizationFailure(SynchronizationFailureCategory.NETWORK, true, now.plusSeconds(30)) else SynchronizationFailure(SynchronizationFailureCategory.UPSTREAM, false, null))), refresh.partialFailure)
            assertTrue(f.persistence.inTransaction { pullRequestStore.listByRepository(repoId, true) }.isEmpty())
            assertTrue(f.persistence.inTransaction { actionItemStore.listActionable() }.isEmpty())
        }
    }

    @Test fun `summary and detail identities are validated while listed existing pull request stays active`() = runTest {
        val variants = listOf(
            GatewayPullRequestSummary(RepositoryId("repo_other"), 1, "PR 1", "user-1", "User", false, "commit-1", URI("https://example.test/1"), now.minusSeconds(100), now),
            null,
        )
        for (summaryOverride in variants) {
            val f = RefreshFixture(); f.configure(); f.gateway.summaries = listOf(f.summary(1)); f.service().refresh(RefreshRepositoryCommand(repoId))
            f.clock = Clock.fixed(now.plusSeconds(60), ZoneOffset.UTC)
            if (summaryOverride != null) f.gateway.summaries = listOf(summaryOverride)
            else f.gateway.detailOverrides[1] = f.gateway.detail(1).copy(repositoryId = RepositoryId("repo_other"))
            val result = f.service().refresh(RefreshRepositoryCommand(repoId)) as RefreshRepositoryResult.PartiallySucceeded
            assertEquals(SynchronizationFailureCategory.MALFORMED_UPSTREAM, result.partialFailure.failures.single().category)
            assertTrue(f.persistence.inTransaction { pullRequestStore.listByRepository(repoId, true) }.single().active)
        }
        val f = RefreshFixture(); f.configure(); f.gateway.summaries = listOf(f.summary(1)); f.service().refresh(RefreshRepositoryCommand(repoId))
        f.clock = Clock.fixed(now.plusSeconds(60), ZoneOffset.UTC); f.gateway.detailOverrides[1] = f.gateway.detail(2)
        assertTrue(f.service().refresh(RefreshRepositoryCommand(repoId)) is RefreshRepositoryResult.PartiallySucceeded)
        assertTrue(f.persistence.inTransaction { pullRequestStore.listByRepository(repoId, true) }.single().active)
    }

    @Test fun `multiple failed pull requests report exact attempted succeeded and failure counts`() = runTest {
        val f = RefreshFixture(); f.configure(); f.gateway.summaries = listOf(f.summary(1), f.summary(2), f.summary(3)); f.gateway.detailFailures += setOf(1, 2)
        val result = f.service().refresh(RefreshRepositoryCommand(repoId)) as RefreshRepositoryResult.PartiallySucceeded
        assertEquals(3, result.partialFailure.attemptedCount); assertEquals(1, result.partialFailure.succeededCount); assertEquals(2, result.partialFailure.failedCount)
    }

    @Test fun `component framing prevents delimiter collision`() {
        assertNotEquals(ObservationAssembler.framedDigest(listOf("a|b", "c")), ObservationAssembler.framedDigest(listOf("a", "b|c")))
    }
}

internal val now: Instant = Instant.parse("2026-08-15T10:00:00Z")
internal val repoId = RepositoryId("repo_one")
internal val networkFailure = GatewayFailure(GatewayFailureCategory.NETWORK, true, now.plusSeconds(30))

internal class RefreshFixture {
    val persistence = InMemoryApplicationPersistence()
    val gateway = RefreshGateway()
    var clock: Clock = Clock.fixed(now, ZoneOffset.UTC)
    val dispatched = mutableListOf<List<NotificationIntentId>>()
    var policy: NotificationIntentPolicy = object : NotificationIntentPolicy {
        override fun createIntents(facts: List<NotificationTransitionFact>) = emptyList<NewNotificationIntent>()
        override fun createReminder(fact: ReminderNotificationFact) = error("not used")
    }
    var dispatcher = PostCommitNotificationDispatcher { dispatched += it }
    fun service() = RefreshRepositoryService(persistence, gateway, policy, dispatcher, clock)
    suspend fun configure() = persistence.inTransaction { configurationStore.save(configuration()) }
    fun configuration() = StoredInstallationConfiguration(WorkspaceId("ws_team"), URI("https://api.bitbucket.org/2.0"), "team", "Team", URI("https://bitbucket.org/team"), "user-1", "User", now, 30,
        listOf(StoredConfiguredRepository(repoId, WorkspaceId("ws_team"), "repo", "Repo", URI("https://bitbucket.org/team/repo"), null)))
    fun summary(number: Long) = GatewayPullRequestSummary(repoId, number, "PR $number", "user-1", "User", false, "commit-$number", URI("https://bitbucket.org/team/repo/pull-requests/$number"), now.minusSeconds(100), now.minusSeconds(10))
}

internal class RefreshGateway : BitbucketGateway {
    var summaries = emptyList<GatewayPullRequestSummary>()
    var listResult: GatewayResult<List<GatewayPullRequestSummary>>? = null
    val detailFailures = mutableSetOf<Long>()
    val detailOverrides = mutableMapOf<Long, GatewayPullRequestDetail>()
    val endpointFailures = mutableMapOf<Pair<String, Long>, GatewayResult<Nothing>>()
    val detailRequests = mutableListOf<Long>()
    var activities = emptyMap<Long, List<GatewayActivityObservation>>()
    var builds: (Long) -> List<GatewayBuildObservation> = { listOf(GatewayBuildObservation("ci", GatewayBuildStatus.SUCCESSFUL, now)) }
    override suspend fun listAuthoredOpenPullRequests(repository: GatewayRepositoryAddress, currentUserStableId: String) = listResult ?: GatewayResult.Success(summaries)
    fun detail(number: Long) = GatewayPullRequestDetail(repoId, number, "PR $number", "user-1", "User", false, "commit-$number", URI("https://bitbucket.org/team/repo/pull-requests/$number"), now.minusSeconds(100), now.minusSeconds(10), 1, setOf("reviewer"), false, 0, true, false)
    @Suppress("UNCHECKED_CAST") private fun <T> endpoint(name: String, number: Long, success: () -> T): GatewayResult<T> = endpointFailures[name to number] as GatewayResult<T>? ?: GatewayResult.Success(success())
    override suspend fun getPullRequest(repository: GatewayRepositoryAddress, upstreamNumber: Long): GatewayResult<GatewayPullRequestDetail> { detailRequests += upstreamNumber; return if (upstreamNumber in detailFailures) GatewayResult.Failure(networkFailure) else endpoint("detail", upstreamNumber) { detailOverrides[upstreamNumber] ?: detail(upstreamNumber) } }
    override suspend fun getEffectiveDefaultReviewers(repository: GatewayRepositoryAddress, upstreamNumber: Long) = endpoint("reviewers", upstreamNumber) { listOf(GatewayUserObservation("reviewer", "Reviewer", null)) }
    override suspend fun listBuilds(repository: GatewayRepositoryAddress, upstreamNumber: Long) = endpoint("builds", upstreamNumber) { builds(upstreamNumber) }
    override suspend fun listTasks(repository: GatewayRepositoryAddress, upstreamNumber: Long) = endpoint("tasks", upstreamNumber) { emptyList<GatewayTaskObservation>() }
    override suspend fun listActivity(repository: GatewayRepositoryAddress, upstreamNumber: Long) = endpoint("activity", upstreamNumber) { activities[upstreamNumber].orEmpty() }
    override suspend fun currentUser(apiBaseUrl: URI) = error("not used")
    override suspend fun resolveWorkspace(apiBaseUrl: URI, workspaceSlug: String) = error("not used")
    override suspend fun resolveRepository(apiBaseUrl: URI, workspaceSlug: String, repositorySlug: String) = error("not used")
    override suspend fun getLiveActivityContent(repository: GatewayRepositoryAddress, upstreamNumber: Long, sourceId: String): GatewayResult<GatewayLiveActivityContent> = error("live content must never be requested")
}

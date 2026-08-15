package com.mindtable.bitbuckethelper.task7

import com.mindtable.bitbuckethelper.adapter.outbound.persistence.memory.InMemoryApplicationPersistence
import com.mindtable.bitbuckethelper.application.model.*
import com.mindtable.bitbuckethelper.application.port.outbound.*
import com.mindtable.bitbuckethelper.application.service.RefreshRepositoryService
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
    var activities = emptyMap<Long, List<GatewayActivityObservation>>()
    var builds: (Long) -> List<GatewayBuildObservation> = { listOf(GatewayBuildObservation("ci", GatewayBuildStatus.SUCCESSFUL, now)) }
    override suspend fun listAuthoredOpenPullRequests(repository: GatewayRepositoryAddress, currentUserStableId: String) = listResult ?: GatewayResult.Success(summaries)
    override suspend fun getPullRequest(repository: GatewayRepositoryAddress, upstreamNumber: Long): GatewayResult<GatewayPullRequestDetail> = if (upstreamNumber in detailFailures) GatewayResult.Failure(networkFailure) else GatewayResult.Success(GatewayPullRequestDetail(repoId, upstreamNumber, "PR $upstreamNumber", "user-1", "User", false, "commit-$upstreamNumber", URI("https://bitbucket.org/team/repo/pull-requests/$upstreamNumber"), now.minusSeconds(100), now.minusSeconds(10), 1, setOf("reviewer"), false, 0, true, false))
    override suspend fun getEffectiveDefaultReviewers(repository: GatewayRepositoryAddress, upstreamNumber: Long) = GatewayResult.Success(listOf(GatewayUserObservation("reviewer", "Reviewer", null)))
    override suspend fun listBuilds(repository: GatewayRepositoryAddress, upstreamNumber: Long) = GatewayResult.Success(builds(upstreamNumber))
    override suspend fun listTasks(repository: GatewayRepositoryAddress, upstreamNumber: Long) = GatewayResult.Success(emptyList<GatewayTaskObservation>())
    override suspend fun listActivity(repository: GatewayRepositoryAddress, upstreamNumber: Long) = GatewayResult.Success(activities[upstreamNumber].orEmpty())
    override suspend fun currentUser(apiBaseUrl: URI) = error("not used")
    override suspend fun resolveWorkspace(apiBaseUrl: URI, workspaceSlug: String) = error("not used")
    override suspend fun resolveRepository(apiBaseUrl: URI, workspaceSlug: String, repositorySlug: String) = error("not used")
    override suspend fun getLiveActivityContent(repository: GatewayRepositoryAddress, upstreamNumber: Long, sourceId: String): GatewayResult<GatewayLiveActivityContent> = error("live content must never be requested")
}

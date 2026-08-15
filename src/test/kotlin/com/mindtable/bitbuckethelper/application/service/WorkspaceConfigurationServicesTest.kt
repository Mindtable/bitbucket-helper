package com.mindtable.bitbuckethelper.task7

import com.mindtable.bitbuckethelper.adapter.outbound.persistence.memory.InMemoryApplicationPersistence
import com.mindtable.bitbuckethelper.application.model.*
import com.mindtable.bitbuckethelper.application.port.outbound.BitbucketGateway
import com.mindtable.bitbuckethelper.application.service.WorkspaceConfigurationServices
import com.mindtable.bitbuckethelper.domain.shared.*
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.net.URI
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class WorkspaceConfigurationServicesTest {
    private val now = Instant.parse("2026-08-15T10:00:00Z")
    private val api = URI("https://api.bitbucket.org/2.0")

    @Test fun `configuration resolves user and workspace then persists retention thirty`() = runTest {
        val persistence = InMemoryApplicationPersistence()
        val gateway = ConfigurationGateway()
        val service = WorkspaceConfigurationServices(persistence, gateway, Clock.fixed(now, ZoneOffset.UTC))

        val result = service.configure(ConfigureWorkspaceCommand(api, "team"))

        assertTrue(result is ConfigureWorkspaceResult.WorkspaceConfigured)
        val stored = persistence.inTransaction { configurationStore.find() }!!
        assertEquals(WorkspaceId("ws_team"), stored.workspaceId)
        assertEquals("user-1", stored.currentUserStableId)
        assertEquals(30, stored.retentionDays)
        assertEquals(now, stored.configuredAt)
        assertEquals(listOf("user", "workspace"), gateway.resolutions)
    }

    @Test fun `same stable workspace is idempotent while mismatch preserves configuration`() = runTest {
        val persistence = InMemoryApplicationPersistence(); val gateway = ConfigurationGateway()
        val service = WorkspaceConfigurationServices(persistence, gateway, Clock.fixed(now, ZoneOffset.UTC))
        service.configure(ConfigureWorkspaceCommand(api, "team"))
        gateway.workspace = gateway.workspace.copy(displayName = "Renamed")
        assertTrue(service.configure(ConfigureWorkspaceCommand(api, "team")) is ConfigureWorkspaceResult.WorkspaceAlreadyConfigured)
        gateway.workspace = gateway.workspace.copy(id = WorkspaceId("ws_other"))
        assertTrue(service.configure(ConfigureWorkspaceCommand(api, "team")) is ConfigureWorkspaceResult.WorkspaceIdentityMismatch)
        assertEquals("Renamed", persistence.inTransaction { configurationStore.find() }!!.workspaceDisplayName)
    }

    @Test fun `add remove and re-add uses stable repository identity and retains removal timestamp until re-add`() = runTest {
        val persistence = InMemoryApplicationPersistence(); val gateway = ConfigurationGateway()
        val service = WorkspaceConfigurationServices(persistence, gateway, Clock.fixed(now, ZoneOffset.UTC))
        service.configure(ConfigureWorkspaceCommand(api, "team"))
        assertTrue(service.add(AddRepositoryCommand("repo")) is AddRepositoryResult.RepositoryAdded)
        assertTrue(service.add(AddRepositoryCommand("repo")) is AddRepositoryResult.RepositoryAlreadyConfigured)
        assertTrue(service.remove(RemoveRepositoryCommand(RepositoryId("repo_one"))) is RemoveRepositoryResult.RepositoryRemoved)
        assertEquals(now, persistence.inTransaction { configurationStore.find() }!!.repositories.single().removedAt)
        assertTrue(service.add(AddRepositoryCommand("repo")) is AddRepositoryResult.RepositoryAdded)
        assertNull(persistence.inTransaction { configurationStore.find() }!!.repositories.single().removedAt)
    }

    @Test fun `upstream not found and failures leave zero configuration state`() = runTest {
        val persistence = InMemoryApplicationPersistence(); val gateway = ConfigurationGateway()
        val service = WorkspaceConfigurationServices(persistence, gateway, Clock.fixed(now, ZoneOffset.UTC))
        gateway.userResult = GatewayResult.NotFound
        assertTrue(service.configure(ConfigureWorkspaceCommand(api, "team")) is ConfigureWorkspaceResult.WorkspaceNotFound)
        assertNull(persistence.inTransaction { configurationStore.find() })
        gateway.userResult = GatewayResult.Success(gateway.user); gateway.workspaceResult = GatewayResult.Failure(failure)
        assertTrue(service.configure(ConfigureWorkspaceCommand(api, "team")) is ConfigureWorkspaceResult.WorkspaceResolutionUnavailable)
        assertNull(persistence.inTransaction { configurationStore.find() })
    }

    @Test fun `repository resolution failures and unknown removals do not mutate configured state`() = runTest {
        val persistence = InMemoryApplicationPersistence(); val gateway = ConfigurationGateway()
        val service = WorkspaceConfigurationServices(persistence, gateway, Clock.fixed(now, ZoneOffset.UTC))
        service.configure(ConfigureWorkspaceCommand(api, "team")); val before = persistence.inTransaction { configurationStore.find() }
        gateway.repositoryResult = GatewayResult.NotFound
        assertEquals(AddRepositoryResult.RepositoryNotFound, service.add(AddRepositoryCommand("missing")))
        gateway.repositoryResult = GatewayResult.Failure(failure)
        assertTrue(service.add(AddRepositoryCommand("broken")) is AddRepositoryResult.RepositoryResolutionUnavailable)
        assertTrue(service.remove(RemoveRepositoryCommand(RepositoryId("repo_missing"))) is RemoveRepositoryResult.RepositoryNotConfigured)
        assertEquals(before, persistence.inTransaction { configurationStore.find() })
    }

    @Test fun `repository resolved outside configured workspace is typed not found and leaves configuration unchanged`() = runTest {
        val persistence = InMemoryApplicationPersistence(); val gateway = ConfigurationGateway()
        val service = WorkspaceConfigurationServices(persistence, gateway, Clock.fixed(now, ZoneOffset.UTC))
        service.configure(ConfigureWorkspaceCommand(api, "team")); val before = persistence.inTransaction { configurationStore.find() }
        gateway.repositoryResult = GatewayResult.Success(gateway.repository.copy(workspaceId = WorkspaceId("ws_other")))

        assertEquals(AddRepositoryResult.RepositoryNotFound, service.add(AddRepositoryCommand("repo")))
        assertEquals(before, persistence.inTransaction { configurationStore.find() })
    }

    @Test fun `get and repository mutation report unconfigured workspace`() = runTest {
        val service = WorkspaceConfigurationServices(InMemoryApplicationPersistence(), ConfigurationGateway(), Clock.fixed(now, ZoneOffset.UTC))
        assertEquals(GetWorkspaceConfigurationResult.WorkspaceNotConfigured, service.get())
        assertEquals(AddRepositoryResult.WorkspaceNotConfigured, service.add(AddRepositoryCommand("repo")))
    }

    private val failure = GatewayFailure(GatewayFailureCategory.NETWORK, true, now.plusSeconds(60))
    private inner class ConfigurationGateway : BitbucketGateway {
        val user = GatewayUserObservation("user-1", "User One", null)
        var workspace = GatewayWorkspaceObservation(WorkspaceId("ws_team"), "team", "Team", URI("https://bitbucket.org/team"))
        val repository = GatewayRepositoryObservation(RepositoryId("repo_one"), WorkspaceId("ws_team"), "repo", "Repository", URI("https://bitbucket.org/team/repo"))
        var userResult: GatewayResult<GatewayUserObservation> = GatewayResult.Success(user)
        var workspaceResult: GatewayResult<GatewayWorkspaceObservation>? = null
        var repositoryResult: GatewayResult<GatewayRepositoryObservation>? = null
        val resolutions = mutableListOf<String>()
        override suspend fun currentUser(apiBaseUrl: URI) = userResult.also { resolutions += "user" }
        override suspend fun resolveWorkspace(apiBaseUrl: URI, workspaceSlug: String) = (workspaceResult ?: GatewayResult.Success(workspace)).also { resolutions += "workspace" }
        override suspend fun resolveRepository(apiBaseUrl: URI, workspaceSlug: String, repositorySlug: String) = repositoryResult ?: GatewayResult.Success(repository)
        override suspend fun listAuthoredOpenPullRequests(repository: GatewayRepositoryAddress, currentUserStableId: String) = unsupported<List<GatewayPullRequestSummary>>()
        override suspend fun getPullRequest(repository: GatewayRepositoryAddress, upstreamNumber: Long) = unsupported<GatewayPullRequestDetail>()
        override suspend fun getEffectiveDefaultReviewers(repository: GatewayRepositoryAddress, upstreamNumber: Long) = unsupported<List<GatewayUserObservation>>()
        override suspend fun listBuilds(repository: GatewayRepositoryAddress, upstreamNumber: Long) = unsupported<List<GatewayBuildObservation>>()
        override suspend fun listTasks(repository: GatewayRepositoryAddress, upstreamNumber: Long) = unsupported<List<GatewayTaskObservation>>()
        override suspend fun listActivity(repository: GatewayRepositoryAddress, upstreamNumber: Long) = unsupported<List<GatewayActivityObservation>>()
        override suspend fun getLiveActivityContent(repository: GatewayRepositoryAddress, upstreamNumber: Long, sourceId: String) = unsupported<GatewayLiveActivityContent>()
        private fun <T> unsupported(): GatewayResult<T> = error("not used")
    }
}

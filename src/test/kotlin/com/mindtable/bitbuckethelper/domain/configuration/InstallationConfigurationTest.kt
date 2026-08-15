package com.mindtable.bitbuckethelper.domain.configuration

import com.mindtable.bitbuckethelper.domain.shared.RepositoryId
import com.mindtable.bitbuckethelper.domain.shared.WorkspaceId
import java.net.URI
import java.time.Instant
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class InstallationConfigurationTest {
    @Test
    fun `normalizes a HTTPS API base URL while retaining its 2 point 0 path`() {
        val configuration = configuration(apiBaseUrl = URI("HTTPS://API.Bitbucket.ORG/2.0///"))

        assertEquals(URI("https://api.bitbucket.org/2.0"), configuration.bitbucketApiBaseUrl)
    }

    @Test
    fun `rejects unsafe or incomplete production API base URLs`() {
        listOf(
            URI("https://user:secret@api.bitbucket.org/2.0"),
            URI("https://api.bitbucket.org/2.0?token=secret"),
            URI("https://api.bitbucket.org/2.0#fragment"),
            URI("http://api.bitbucket.org/2.0"),
            URI("https:/2.0"),
        ).forEach { url ->
            assertThrows(IllegalArgumentException::class.java) { configuration(apiBaseUrl = url) }
        }
    }

    @Test
    fun `test-only HTTP factory permits HTTP after applying the remaining URL rules`() {
        val configuration = InstallationConfiguration.createForTestAllowingHttp(
            bitbucketApiBaseUrl = URI("HTTP://LOCALHOST:8080/2.0/"),
            workspace = workspace(),
            configuredAt = configuredAt,
            retentionDays = 30,
        )

        assertEquals(URI("http://localhost:8080/2.0"), configuration.bitbucketApiBaseUrl)
    }

    @Test
    fun `workspace identity is retained and stable identity replacement is rejected`() {
        val originalWorkspace = workspace()
        val configuration = configuration(workspace = originalWorkspace)

        assertSame(originalWorkspace, configuration.workspace)
        assertEquals(
            InstallationConfiguration.WorkspaceIdentityReplacementResult.Rejected(configuration),
            configuration.replaceWorkspaceIdentity(workspace(id = WorkspaceId("ws_other"))),
        )
    }

    @Test
    fun `adding an active repository with the same stable ID is idempotent`() {
        val repository = repository()
        val configuration = configuration(repositories = listOf(repository))

        val result = configuration.addRepository(repository)

        assertEquals(
            InstallationConfiguration.AddRepositoryResult.AlreadyConfigured(configuration, repository),
            result,
        )
    }

    @Test
    fun `removing then re-adding the same stable repository clears its removal time`() {
        val repository = repository()
        val configuration = configuration(repositories = listOf(repository))
        val removedAt = Instant.parse("2026-08-15T09:30:00Z")
        val removed = (configuration.removeRepository(repository.id, removedAt)
            as InstallationConfiguration.RemoveRepositoryResult.Removed).configuration

        val readded = (removed.addRepository(repository)
            as InstallationConfiguration.AddRepositoryResult.Readded).configuration

        assertNull(readded.repositories.single().removedAt)
        assertEquals(repository, readded.repositories.single())
    }

    @Test
    fun `adding a different stable repository with a retained slug reports a collision`() {
        val existing = repository(id = RepositoryId("repo_existing"), slug = "payments")
        val configuration = configuration(repositories = listOf(existing))

        val result = configuration.addRepository(repository(id = RepositoryId("repo_other"), slug = "payments"))

        assertEquals(
            InstallationConfiguration.AddRepositoryResult.SlugCollision(existing),
            result,
        )
    }

    @Test
    fun `re-adding a retained repository into another retained slug reports a collision`() {
        val removedAt = Instant.parse("2026-08-15T09:30:00Z")
        val readded = repository(id = RepositoryId("repo_readded"), slug = "legacy").copy(removedAt = removedAt)
        val conflicting = repository(id = RepositoryId("repo_conflicting"), slug = "payments").copy(removedAt = removedAt)
        val configuration = configuration(repositories = listOf(readded, conflicting))

        val result = configuration.addRepository(readded.copy(slug = "payments"))

        assertEquals(
            InstallationConfiguration.AddRepositoryResult.SlugCollision(conflicting),
            result,
        )
    }

    private fun configuration(
        apiBaseUrl: URI = URI("https://api.bitbucket.org/2.0"),
        workspace: WorkspaceIdentity = workspace(),
        repositories: List<ConfiguredRepository> = emptyList(),
    ) = InstallationConfiguration.create(
        bitbucketApiBaseUrl = apiBaseUrl,
        workspace = workspace,
        configuredAt = configuredAt,
        retentionDays = 30,
        repositories = repositories,
    )

    private fun workspace(id: WorkspaceId = WorkspaceId("ws_primary")) = WorkspaceIdentity(
        id = id,
        slug = "mindtable",
        displayName = "Mindtable",
        webUrl = URI("https://bitbucket.org/mindtable"),
        currentUserStableId = "user-123",
        currentUserDisplayName = "Ada Lovelace",
    )

    private fun repository(
        id: RepositoryId = RepositoryId("repo_payments"),
        slug: String = "payments",
    ) = ConfiguredRepository(
        id = id,
        workspaceId = WorkspaceId("ws_primary"),
        slug = slug,
        displayName = "Payments",
        webUrl = URI("https://bitbucket.org/mindtable/$slug"),
    )

    private companion object {
        val configuredAt: Instant = Instant.parse("2026-08-15T09:00:00Z")
    }
}

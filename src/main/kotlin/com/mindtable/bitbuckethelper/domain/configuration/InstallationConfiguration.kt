package com.mindtable.bitbuckethelper.domain.configuration

import com.mindtable.bitbuckethelper.domain.shared.RepositoryId
import com.mindtable.bitbuckethelper.domain.shared.WorkspaceId
import java.net.URI
import java.time.Instant

data class WorkspaceIdentity(
    val id: WorkspaceId,
    val slug: String,
    val displayName: String,
    val webUrl: URI,
    val currentUserStableId: String,
    val currentUserDisplayName: String,
)

data class ConfiguredRepository(
    val id: RepositoryId,
    val workspaceId: WorkspaceId,
    val slug: String,
    val displayName: String,
    val webUrl: URI,
    val removedAt: Instant? = null,
)

class InstallationConfiguration private constructor(
    val bitbucketApiBaseUrl: URI,
    val workspace: WorkspaceIdentity,
    val configuredAt: Instant,
    val retentionDays: Int,
    val repositories: List<ConfiguredRepository>,
) {
    init {
        require(retentionDays >= 0) { "Retention days cannot be negative" }
        require(repositories.all { it.workspaceId == workspace.id }) {
            "Configured repositories must belong to the workspace"
        }
        require(repositories.map { it.id }.distinct().size == repositories.size) {
            "Configured repository IDs must be unique"
        }
        require(repositories.map { it.slug }.distinct().size == repositories.size) {
            "Configured repository slugs must be unique"
        }
    }

    fun addRepository(repository: ConfiguredRepository): AddRepositoryResult {
        require(repository.workspaceId == workspace.id) { "Repository must belong to the workspace" }

        val existingBySlug = repositories.singleOrNull { it.slug == repository.slug && it.id != repository.id }
        if (existingBySlug != null) {
            return AddRepositoryResult.SlugCollision(existingBySlug)
        }

        val existingById = repositories.singleOrNull { it.id == repository.id }
        if (existingById != null) {
            return if (existingById.removedAt == null) {
                AddRepositoryResult.AlreadyConfigured(this, existingById)
            } else {
                AddRepositoryResult.Readded(replaceRepository(existingById, repository.copy(removedAt = null)))
            }
        }

        return AddRepositoryResult.Added(withRepositories(repositories + repository))
    }

    fun removeRepository(repositoryId: RepositoryId, removedAt: Instant): RemoveRepositoryResult {
        val existing = repositories.singleOrNull { it.id == repositoryId }
            ?: return RemoveRepositoryResult.NotConfigured(this, repositoryId)
        if (existing.removedAt != null) return RemoveRepositoryResult.NotConfigured(this, repositoryId)

        return RemoveRepositoryResult.Removed(
            replaceRepository(existing, existing.copy(removedAt = removedAt)),
            repositoryId,
        )
    }

    fun replaceWorkspaceIdentity(identity: WorkspaceIdentity): WorkspaceIdentityReplacementResult =
        if (identity.id != workspace.id) {
            WorkspaceIdentityReplacementResult.Rejected(this)
        } else {
            WorkspaceIdentityReplacementResult.Accepted(
                InstallationConfiguration(bitbucketApiBaseUrl, identity, configuredAt, retentionDays, repositories),
            )
        }

    private fun replaceRepository(
        existing: ConfiguredRepository,
        replacement: ConfiguredRepository,
    ): InstallationConfiguration = withRepositories(repositories.map { if (it == existing) replacement else it })

    private fun withRepositories(repositories: List<ConfiguredRepository>): InstallationConfiguration =
        InstallationConfiguration(bitbucketApiBaseUrl, workspace, configuredAt, retentionDays, ordered(repositories))

    sealed interface AddRepositoryResult {
        data class Added(val configuration: InstallationConfiguration) : AddRepositoryResult
        data class AlreadyConfigured(
            val configuration: InstallationConfiguration,
            val repository: ConfiguredRepository,
        ) : AddRepositoryResult
        data class Readded(val configuration: InstallationConfiguration) : AddRepositoryResult
        data class SlugCollision(val existing: ConfiguredRepository) : AddRepositoryResult
    }

    sealed interface RemoveRepositoryResult {
        data class Removed(
            val configuration: InstallationConfiguration,
            val repositoryId: RepositoryId,
        ) : RemoveRepositoryResult
        data class NotConfigured(
            val configuration: InstallationConfiguration,
            val repositoryId: RepositoryId,
        ) : RemoveRepositoryResult
    }

    sealed interface WorkspaceIdentityReplacementResult {
        data class Accepted(val configuration: InstallationConfiguration) : WorkspaceIdentityReplacementResult
        data class Rejected(val configuration: InstallationConfiguration) : WorkspaceIdentityReplacementResult
    }

    companion object {
        fun create(
            bitbucketApiBaseUrl: URI,
            workspace: WorkspaceIdentity,
            configuredAt: Instant,
            retentionDays: Int,
            repositories: List<ConfiguredRepository> = emptyList(),
        ): InstallationConfiguration = create(bitbucketApiBaseUrl, workspace, configuredAt, retentionDays, repositories, false)

        fun normalizeApiBaseUrl(uri: URI): URI = normalizeApiBaseUrl(uri, allowHttp = false)

        fun createForTestAllowingHttp(
            bitbucketApiBaseUrl: URI,
            workspace: WorkspaceIdentity,
            configuredAt: Instant,
            retentionDays: Int,
            repositories: List<ConfiguredRepository> = emptyList(),
        ): InstallationConfiguration = create(bitbucketApiBaseUrl, workspace, configuredAt, retentionDays, repositories, true)

        private fun create(
            bitbucketApiBaseUrl: URI,
            workspace: WorkspaceIdentity,
            configuredAt: Instant,
            retentionDays: Int,
            repositories: List<ConfiguredRepository>,
            allowHttp: Boolean,
        ) = InstallationConfiguration(
            bitbucketApiBaseUrl = normalizeApiBaseUrl(bitbucketApiBaseUrl, allowHttp),
            workspace = workspace,
            configuredAt = configuredAt,
            retentionDays = retentionDays,
            repositories = ordered(repositories),
        )

        private fun normalizeApiBaseUrl(uri: URI, allowHttp: Boolean): URI {
            require(uri.userInfo == null) { "API base URL must not include credentials" }
            require(uri.query == null) { "API base URL must not include a query" }
            require(uri.fragment == null) { "API base URL must not include a fragment" }
            val scheme = uri.scheme?.lowercase() ?: throw IllegalArgumentException("API base URL must include a scheme")
            require(scheme == "https" || (allowHttp && scheme == "http")) { "API base URL must use HTTPS" }
            val host = uri.host?.lowercase() ?: throw IllegalArgumentException("API base URL must include a host")
            val path = uri.path.trimEnd('/').ifEmpty { "/" }
            require(path == "/2.0") { "API base URL path must be /2.0" }
            return URI(scheme, null, host, uri.port, path, null, null)
        }

        private fun ordered(repositories: List<ConfiguredRepository>): List<ConfiguredRepository> =
            java.util.List.copyOf(repositories.sortedWith(compareBy<ConfiguredRepository> { it.slug }.thenBy { it.id.value }))
    }
}

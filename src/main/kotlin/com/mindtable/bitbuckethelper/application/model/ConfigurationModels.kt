package com.mindtable.bitbuckethelper.application.model

import com.mindtable.bitbuckethelper.domain.shared.RepositoryId
import com.mindtable.bitbuckethelper.domain.shared.WorkspaceId
import java.net.URI
import java.time.Instant

data class ConfigureWorkspaceCommand(
    val bitbucketApiBaseUrl: URI,
    val workspaceSlug: String,
)

data class AddRepositoryCommand(val repositorySlug: String)

data class RemoveRepositoryCommand(val repositoryId: RepositoryId)

data class StoredConfiguredRepository(
    val id: RepositoryId,
    val workspaceId: WorkspaceId,
    val slug: String,
    val displayName: String,
    val webUrl: URI,
    val removedAt: Instant?,
)

data class StoredInstallationConfiguration(
    val workspaceId: WorkspaceId,
    val bitbucketApiBaseUrl: URI,
    val workspaceSlug: String,
    val workspaceDisplayName: String,
    val workspaceWebUrl: URI,
    val currentUserStableId: String,
    val currentUserDisplayName: String,
    val configuredAt: Instant,
    val retentionDays: Int,
    val repositories: List<StoredConfiguredRepository>,
)

data class WorkspaceConfigurationProjection(
    val workspaceId: WorkspaceId,
    val bitbucketApiBaseUrl: URI,
    val workspaceSlug: String,
    val workspaceDisplayName: String,
    val workspaceWebUrl: URI,
    val retentionDays: Int,
    val repositories: List<ConfiguredRepositoryProjection>,
)

data class ConfiguredRepositoryProjection(
    val repositoryId: RepositoryId,
    val slug: String,
    val displayName: String,
    val webUrl: URI,
)

sealed interface GetWorkspaceConfigurationResult {
    data class Configured(val configuration: WorkspaceConfigurationProjection) : GetWorkspaceConfigurationResult
    data object WorkspaceNotConfigured : GetWorkspaceConfigurationResult
}

sealed interface ConfigureWorkspaceResult {
    data class WorkspaceConfigured(val configuration: WorkspaceConfigurationProjection) : ConfigureWorkspaceResult
    data class WorkspaceAlreadyConfigured(val configuration: WorkspaceConfigurationProjection) : ConfigureWorkspaceResult
    data class WorkspaceIdentityMismatch(val current: WorkspaceConfigurationProjection) : ConfigureWorkspaceResult
    data object WorkspaceNotFound : ConfigureWorkspaceResult
    data class WorkspaceResolutionUnavailable(val failure: GatewayFailure) : ConfigureWorkspaceResult
}

sealed interface AddRepositoryResult {
    data class RepositoryAdded(val repository: ConfiguredRepositoryProjection) : AddRepositoryResult
    data class RepositoryAlreadyConfigured(val repository: ConfiguredRepositoryProjection) : AddRepositoryResult
    data object RepositoryNotFound : AddRepositoryResult
    data class RepositoryResolutionUnavailable(val failure: GatewayFailure) : AddRepositoryResult
    data object WorkspaceNotConfigured : AddRepositoryResult
}

sealed interface RemoveRepositoryResult {
    data class RepositoryRemoved(val repositoryId: RepositoryId) : RemoveRepositoryResult
    data class RepositoryNotConfigured(val repositoryId: RepositoryId) : RemoveRepositoryResult
}

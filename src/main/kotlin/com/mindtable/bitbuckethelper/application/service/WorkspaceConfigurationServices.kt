package com.mindtable.bitbuckethelper.application.service

import com.mindtable.bitbuckethelper.application.model.*
import com.mindtable.bitbuckethelper.application.port.outbound.ApplicationTransactionRunner
import com.mindtable.bitbuckethelper.application.port.outbound.BitbucketGateway
import com.mindtable.bitbuckethelper.domain.configuration.*
import java.time.Clock

class WorkspaceConfigurationServices(
    private val transactions: ApplicationTransactionRunner,
    private val gateway: BitbucketGateway,
    private val clock: Clock,
) {
    suspend fun get(): GetWorkspaceConfigurationResult = transactions.inTransaction {
        configurationStore.find()?.let { GetWorkspaceConfigurationResult.Configured(it.projection()) }
            ?: GetWorkspaceConfigurationResult.WorkspaceNotConfigured
    }

    suspend fun configure(command: ConfigureWorkspaceCommand): ConfigureWorkspaceResult {
        val apiBaseUrl = InstallationConfiguration.normalizeApiBaseUrl(command.bitbucketApiBaseUrl)
        val user = when (val result = gateway.currentUser(apiBaseUrl)) {
            is GatewayResult.Success -> result.value
            GatewayResult.NotFound -> return ConfigureWorkspaceResult.WorkspaceNotFound
            is GatewayResult.Failure -> return ConfigureWorkspaceResult.WorkspaceResolutionUnavailable(result.failure)
        }
        val workspace = when (val result = gateway.resolveWorkspace(apiBaseUrl, command.workspaceSlug)) {
            is GatewayResult.Success -> result.value
            GatewayResult.NotFound -> return ConfigureWorkspaceResult.WorkspaceNotFound
            is GatewayResult.Failure -> return ConfigureWorkspaceResult.WorkspaceResolutionUnavailable(result.failure)
        }
        return transactions.inTransaction {
            val stored = configurationStore.find()
            val identity = WorkspaceIdentity(workspace.id, workspace.slug, workspace.displayName, workspace.webUrl, user.stableId, user.displayName)
            if (stored == null) {
                val created = InstallationConfiguration.create(apiBaseUrl, identity, clock.instant(), 30)
                configurationStore.save(created.stored())
                ConfigureWorkspaceResult.WorkspaceConfigured(created.stored().projection())
            } else if (stored.workspaceId != workspace.id || stored.currentUserStableId != user.stableId) {
                ConfigureWorkspaceResult.WorkspaceIdentityMismatch(stored.projection())
            } else {
                val updated = stored.domain().replaceWorkspaceIdentity(identity) as InstallationConfiguration.WorkspaceIdentityReplacementResult.Accepted
                configurationStore.save(updated.configuration.stored())
                ConfigureWorkspaceResult.WorkspaceAlreadyConfigured(updated.configuration.stored().projection())
            }
        }
    }

    suspend fun add(command: AddRepositoryCommand): AddRepositoryResult {
        val configuration = transactions.inTransaction { configurationStore.find() }
            ?: return AddRepositoryResult.WorkspaceNotConfigured
        val repository = when (val result = gateway.resolveRepository(configuration.bitbucketApiBaseUrl, configuration.workspaceSlug, command.repositorySlug)) {
            is GatewayResult.Success -> result.value
            GatewayResult.NotFound -> return AddRepositoryResult.RepositoryNotFound
            is GatewayResult.Failure -> return AddRepositoryResult.RepositoryResolutionUnavailable(result.failure)
        }
        if (repository.workspaceId != configuration.workspaceId) return AddRepositoryResult.RepositoryNotFound
        return transactions.inTransaction {
            val current = configurationStore.find() ?: return@inTransaction AddRepositoryResult.WorkspaceNotConfigured
            val candidate = ConfiguredRepository(repository.id, repository.workspaceId, repository.slug, repository.displayName, repository.webUrl)
            when (val result = current.domain().addRepository(candidate)) {
                is InstallationConfiguration.AddRepositoryResult.Added -> { configurationStore.save(result.configuration.stored()); AddRepositoryResult.RepositoryAdded(candidate.projection()) }
                is InstallationConfiguration.AddRepositoryResult.Readded -> { configurationStore.save(result.configuration.stored()); AddRepositoryResult.RepositoryAdded(candidate.projection()) }
                is InstallationConfiguration.AddRepositoryResult.AlreadyConfigured -> AddRepositoryResult.RepositoryAlreadyConfigured(result.repository.projection())
                is InstallationConfiguration.AddRepositoryResult.SlugCollision -> AddRepositoryResult.RepositoryAlreadyConfigured(result.existing.projection())
            }
        }
    }

    suspend fun remove(command: RemoveRepositoryCommand): RemoveRepositoryResult = transactions.inTransaction {
        val current = configurationStore.find() ?: return@inTransaction RemoveRepositoryResult.RepositoryNotConfigured(command.repositoryId)
        when (val result = current.domain().removeRepository(command.repositoryId, clock.instant())) {
            is InstallationConfiguration.RemoveRepositoryResult.Removed -> { configurationStore.save(result.configuration.stored()); RemoveRepositoryResult.RepositoryRemoved(command.repositoryId) }
            is InstallationConfiguration.RemoveRepositoryResult.NotConfigured -> RemoveRepositoryResult.RepositoryNotConfigured(command.repositoryId)
        }
    }
}

private fun StoredInstallationConfiguration.domain() = InstallationConfiguration.create(
    bitbucketApiBaseUrl,
    WorkspaceIdentity(workspaceId, workspaceSlug, workspaceDisplayName, workspaceWebUrl, currentUserStableId, currentUserDisplayName),
    configuredAt, retentionDays,
    repositories.map { ConfiguredRepository(it.id, it.workspaceId, it.slug, it.displayName, it.webUrl, it.removedAt) },
)

private fun InstallationConfiguration.stored() = StoredInstallationConfiguration(
    workspace.id, bitbucketApiBaseUrl, workspace.slug, workspace.displayName, workspace.webUrl,
    workspace.currentUserStableId, workspace.currentUserDisplayName, configuredAt, retentionDays,
    repositories.map { StoredConfiguredRepository(it.id, it.workspaceId, it.slug, it.displayName, it.webUrl, it.removedAt) },
)

private fun StoredInstallationConfiguration.projection() = WorkspaceConfigurationProjection(
    workspaceId, bitbucketApiBaseUrl, workspaceSlug, workspaceDisplayName, workspaceWebUrl, retentionDays,
    repositories.filter { it.removedAt == null }.map { ConfiguredRepositoryProjection(it.id, it.slug, it.displayName, it.webUrl) },
)

private fun ConfiguredRepository.projection() = ConfiguredRepositoryProjection(id, slug, displayName, webUrl)

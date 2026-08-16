package com.mindtable.bitbuckethelper.application.service

import com.mindtable.bitbuckethelper.application.model.*
import com.mindtable.bitbuckethelper.application.port.inbound.GetRefreshRun
import com.mindtable.bitbuckethelper.application.port.inbound.StartRefreshRun
import com.mindtable.bitbuckethelper.application.port.outbound.ApplicationTransactionRunner
import com.mindtable.bitbuckethelper.domain.shared.RefreshRunId
import java.time.Clock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class RefreshRunServices(
    private val transactions: ApplicationTransactionRunner,
    private val coordinator: RepositoryRefreshCoordinator,
    private val registry: InMemoryRefreshRunRegistry,
    private val serviceScope: CoroutineScope,
    private val pollingAdvice: ActivePollingAdvice,
    private val clock: Clock,
) {
    val startRefreshRun: StartRefreshRun = StartRefreshRun(::start)
    val getRefreshRun: GetRefreshRun = GetRefreshRun(::get)

    suspend fun start(command: StartRefreshRunCommand): StartRefreshRunResult {
        val configuration = transactions.inTransaction { configurationStore.find() }
            ?: return StartRefreshRunResult.WorkspaceNotConfigured
        val activeRepositoryIds = configuration.repositories
            .filter { it.removedAt == null }
            .map { it.id }
        val requestedRepositoryIds = when (val target = command.target) {
            RefreshTarget.AllConfiguredRepositories -> activeRepositoryIds
            is RefreshTarget.Repositories -> target.repositoryIds.distinct()
        }
        if (requestedRepositoryIds.isEmpty()) return StartRefreshRunResult.NoRepositoriesConfigured

        val registrations = requestedRepositoryIds.map { repositoryId ->
            coordinator.register(RefreshRepositoryCommand(repositoryId))
        }
        val dispositions = registrations.map(RepositoryRefreshRegistration::disposition)
        val initialEntries = dispositions.mapNotNull { disposition ->
            when (disposition) {
                is RefreshRegistrationDisposition.Started,
                is RefreshRegistrationDisposition.JoinedExisting,
                -> RefreshRunRepositoryEntry.Queued(disposition.repositoryId)
                is RefreshRegistrationDisposition.DeferredByBackoff ->
                    RefreshRunRepositoryEntry.DeferredByBackoff(
                        disposition.repositoryId,
                        disposition.retryAt,
                    )
                is RefreshRegistrationDisposition.RepositoryNotConfigured -> null
            }
        }
        val registeredSnapshot = registry.createWithEntries(initialEntries)
        registrations.forEach { registration ->
            when (registration.disposition) {
                is RefreshRegistrationDisposition.Started,
                is RefreshRegistrationDisposition.JoinedExisting,
                -> monitor(registeredSnapshot.id, registration)
                is RefreshRegistrationDisposition.DeferredByBackoff,
                is RefreshRegistrationDisposition.RepositoryNotConfigured,
                -> Unit
            }
        }
        return StartRefreshRunResult.RefreshRunRegistered(registeredSnapshot, dispositions)
    }

    suspend fun get(refreshRunId: RefreshRunId): GetRefreshRunResult {
        val run = registry.find(refreshRunId)
            ?: return GetRefreshRunResult.RefreshRunUnavailable(refreshRunId)
        return if (run.repositories.any { it is RefreshRunRepositoryEntry.Queued || it is RefreshRunRepositoryEntry.Running }) {
            GetRefreshRunResult.RefreshRunInProgress(run, pollingAdvice)
        } else {
            GetRefreshRunResult.RefreshRunCompleted(run)
        }
    }

    private fun monitor(runId: RefreshRunId, registration: RepositoryRefreshRegistration) {
        serviceScope.launch {
            val repositoryId = registration.disposition.repositoryId
            if (!registry.update(runId, RefreshRunRepositoryEntry.Running(repositoryId))) return@launch
            try {
                when (val result = registration.await()) {
                    is RefreshRepositoryResult.Succeeded -> registry.update(
                        runId,
                        RefreshRunRepositoryEntry.Succeeded(repositoryId, result.completedAt),
                    )
                    is RefreshRepositoryResult.PartiallySucceeded -> registry.update(
                        runId,
                        RefreshRunRepositoryEntry.PartiallySucceeded(
                            repositoryId,
                            result.completedAt,
                            result.partialFailure,
                        ),
                    )
                    is RefreshRepositoryResult.Failed -> registry.update(
                        runId,
                        RefreshRunRepositoryEntry.Failed(repositoryId, clock.instant(), result.failure),
                    )
                    is RefreshRepositoryResult.DeferredByBackoff -> registry.update(
                        runId,
                        RefreshRunRepositoryEntry.DeferredByBackoff(repositoryId, result.retryAt),
                    )
                    is RefreshRepositoryResult.RepositoryNotConfigured ->
                        registry.removeRepository(runId, repositoryId)
                }
            } catch (failure: Throwable) {
                withContext(NonCancellable) {
                    registry.removeRepository(runId, repositoryId)
                }
                throw failure
            }
        }
    }
}

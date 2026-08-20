package com.mindtable.bitbuckethelper.application.service

import com.mindtable.bitbuckethelper.application.model.*
import com.mindtable.bitbuckethelper.application.port.inbound.GetRefreshRun
import com.mindtable.bitbuckethelper.application.port.inbound.StartRefreshRun
import com.mindtable.bitbuckethelper.application.port.outbound.ApplicationTransactionRunner
import com.mindtable.bitbuckethelper.application.port.outbound.OperationalEvent
import com.mindtable.bitbuckethelper.application.port.outbound.OperationalEventRecorder
import com.mindtable.bitbuckethelper.application.port.outbound.RefreshRepositoryOutcome
import com.mindtable.bitbuckethelper.domain.shared.RefreshRunId
import com.mindtable.bitbuckethelper.observability.MonotonicTimeSource
import java.time.Clock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
    private val operationalEventRecorder: OperationalEventRecorder = OperationalEventRecorder.NONE,
    private val timeSource: MonotonicTimeSource = MonotonicTimeSource.SYSTEM,
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
        operationalEventRecorder.recordSafely(
            OperationalEvent.RefreshRunRegistered(
                refreshRunId = registeredSnapshot.id,
                repositoryCount = registrations.size,
                startedCount = dispositions.count { it is RefreshRegistrationDisposition.Started },
                joinedCount = dispositions.count { it is RefreshRegistrationDisposition.JoinedExisting },
                deferredCount = dispositions.count { it is RefreshRegistrationDisposition.DeferredByBackoff },
                notConfiguredCount = dispositions.count { it is RefreshRegistrationDisposition.RepositoryNotConfigured },
            ),
        )
        registrations.forEach { registration -> monitor(registeredSnapshot.id, registration) }
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

    @OptIn(DelicateCoroutinesApi::class, ExperimentalCoroutinesApi::class)
    private fun monitor(runId: RefreshRunId, registration: RepositoryRefreshRegistration) {
        val capturedRunId = runId
        val capturedRepositoryId = registration.disposition.repositoryId
        val capturesRegistryEntry = registration.disposition is RefreshRegistrationDisposition.Started ||
            registration.disposition is RefreshRegistrationDisposition.JoinedExisting
        serviceScope.launch(start = CoroutineStart.ATOMIC) {
            val startedAtNanos = runCatching { timeSource.nanoTime() }.getOrDefault(0L)
            try {
                if (capturesRegistryEntry) {
                    registry.update(capturedRunId, RefreshRunRepositoryEntry.Running(capturedRepositoryId))
                }
                val result = registration.await()
                when (result) {
                    is RefreshRepositoryResult.Succeeded -> registry.update(
                        capturedRunId,
                        RefreshRunRepositoryEntry.Succeeded(capturedRepositoryId, result.completedAt),
                    )
                    is RefreshRepositoryResult.PartiallySucceeded -> registry.update(
                        capturedRunId,
                        RefreshRunRepositoryEntry.PartiallySucceeded(
                            capturedRepositoryId,
                            result.completedAt,
                            result.partialFailure,
                        ),
                    )
                    is RefreshRepositoryResult.Failed -> registry.update(
                        capturedRunId,
                        RefreshRunRepositoryEntry.Failed(capturedRepositoryId, clock.instant(), result.failure),
                    )
                    is RefreshRepositoryResult.DeferredByBackoff -> registry.update(
                        capturedRunId,
                        RefreshRunRepositoryEntry.DeferredByBackoff(capturedRepositoryId, result.retryAt),
                    )
                    is RefreshRepositoryResult.RepositoryNotConfigured ->
                        registry.removeRepository(capturedRunId, capturedRepositoryId)
                }
                operationalEventRecorder.recordSafely(
                    result.toOperationalEvent(capturedRunId, elapsedMilliseconds(startedAtNanos)),
                )
            } catch (cancellation: CancellationException) {
                withContext(NonCancellable) {
                    registry.removeRepository(capturedRunId, capturedRepositoryId)
                }
                throw cancellation
            } catch (failure: Throwable) {
                withContext(NonCancellable) {
                    registry.update(
                        capturedRunId,
                        RefreshRunRepositoryEntry.Failed(
                            capturedRepositoryId,
                            clock.instant(),
                            unexpectedRefreshFailure,
                        ),
                    )
                }
                operationalEventRecorder.recordSafely(
                    OperationalEvent.RefreshRepositoryFinished(
                        refreshRunId = capturedRunId,
                        repositoryId = capturedRepositoryId,
                        outcome = RefreshRepositoryOutcome.UNEXPECTED,
                        failureCategory = null,
                        retryable = null,
                        retryAt = null,
                        durationMilliseconds = elapsedMilliseconds(startedAtNanos),
                        unexpectedFailure = failure,
                    ),
                )
            }
        }
    }

    private fun elapsedMilliseconds(startedAtNanos: Long): Long = runCatching {
        ((timeSource.nanoTime() - startedAtNanos).coerceAtLeast(0L)) / NANOS_PER_MILLISECOND
    }.getOrDefault(0L)

    private companion object {
        const val NANOS_PER_MILLISECOND = 1_000_000L
    }
}

private fun OperationalEventRecorder.recordSafely(event: OperationalEvent) {
    try {
        record(event)
    } catch (_: Throwable) {
        // Observability must not alter refresh state or cancellation behavior.
    }
}

private fun RefreshRepositoryResult.toOperationalEvent(
    refreshRunId: RefreshRunId,
    durationMilliseconds: Long,
): OperationalEvent.RefreshRepositoryFinished = when (this) {
    is RefreshRepositoryResult.Succeeded -> OperationalEvent.RefreshRepositoryFinished(
        refreshRunId = refreshRunId,
        repositoryId = repositoryId,
        outcome = RefreshRepositoryOutcome.SUCCEEDED,
        failureCategory = null,
        retryable = null,
        retryAt = null,
        durationMilliseconds = durationMilliseconds,
    )
    is RefreshRepositoryResult.PartiallySucceeded -> partialFailure.failures.firstOrNull().let { failure ->
        OperationalEvent.RefreshRepositoryFinished(
            refreshRunId = refreshRunId,
            repositoryId = repositoryId,
            outcome = RefreshRepositoryOutcome.PARTIAL,
            failureCategory = failure?.category,
            retryable = failure?.retryable,
            retryAt = failure?.retryAt,
            durationMilliseconds = durationMilliseconds,
        )
    }
    is RefreshRepositoryResult.Failed -> OperationalEvent.RefreshRepositoryFinished(
        refreshRunId = refreshRunId,
        repositoryId = repositoryId,
        outcome = RefreshRepositoryOutcome.FAILED,
        failureCategory = failure.category,
        retryable = failure.retryable,
        retryAt = failure.retryAt,
        durationMilliseconds = durationMilliseconds,
    )
    is RefreshRepositoryResult.DeferredByBackoff -> OperationalEvent.RefreshRepositoryFinished(
        refreshRunId = refreshRunId,
        repositoryId = repositoryId,
        outcome = RefreshRepositoryOutcome.DEFERRED,
        failureCategory = null,
        retryable = null,
        retryAt = retryAt,
        durationMilliseconds = durationMilliseconds,
    )
    is RefreshRepositoryResult.RepositoryNotConfigured -> OperationalEvent.RefreshRepositoryFinished(
        refreshRunId = refreshRunId,
        repositoryId = repositoryId,
        outcome = RefreshRepositoryOutcome.NOT_CONFIGURED,
        failureCategory = null,
        retryable = null,
        retryAt = null,
        durationMilliseconds = durationMilliseconds,
    )
}

private val unexpectedRefreshFailure = SynchronizationFailure(
    category = SynchronizationFailureCategory.UPSTREAM,
    retryable = false,
    retryAt = null,
)

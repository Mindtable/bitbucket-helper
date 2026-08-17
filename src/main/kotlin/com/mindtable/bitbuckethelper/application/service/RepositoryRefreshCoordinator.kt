package com.mindtable.bitbuckethelper.application.service

import com.mindtable.bitbuckethelper.application.model.Freshness
import com.mindtable.bitbuckethelper.application.model.PartialFailureMetadata
import com.mindtable.bitbuckethelper.application.model.RefreshRegistrationDisposition
import com.mindtable.bitbuckethelper.application.model.RefreshRepositoryCommand
import com.mindtable.bitbuckethelper.application.model.RefreshRepositoryResult
import com.mindtable.bitbuckethelper.application.model.StoredSynchronizationSnapshot
import com.mindtable.bitbuckethelper.application.model.SynchronizationActivity
import com.mindtable.bitbuckethelper.application.model.SynchronizationAttemptOutcome
import com.mindtable.bitbuckethelper.application.model.SynchronizationProblem
import com.mindtable.bitbuckethelper.application.model.SynchronizationProjection
import com.mindtable.bitbuckethelper.application.policy.SynchronizationBackoff
import com.mindtable.bitbuckethelper.application.port.inbound.RefreshRepository
import com.mindtable.bitbuckethelper.application.port.outbound.ApplicationTransactionRunner
import com.mindtable.bitbuckethelper.domain.shared.RepositoryId
import java.time.Clock
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class RepositoryRefreshRegistration internal constructor(
    val disposition: RefreshRegistrationDisposition,
    private val result: Deferred<RefreshRepositoryResult>,
) {
    suspend fun await(): RefreshRepositoryResult = result.await()
}

class RepositoryRefreshCoordinator(
    private val transactions: ApplicationTransactionRunner,
    private val delegate: RefreshRepository,
    serviceScope: CoroutineScope,
    private val clock: Clock,
    private val backoff: SynchronizationBackoff = SynchronizationBackoff(),
) : RefreshRepository {
    private val mutex = Mutex()
    private val flights = mutableMapOf<RepositoryId, CompletableDeferred<RefreshRepositoryResult>>()
    private val flightScope = CoroutineScope(
        serviceScope.coroutineContext + SupervisorJob(serviceScope.coroutineContext[Job]),
    )

    override suspend fun invoke(command: RefreshRepositoryCommand): RefreshRepositoryResult =
        register(command).await()

    suspend fun register(command: RefreshRepositoryCommand): RepositoryRefreshRegistration =
        mutex.withLock {
            flights[command.repositoryId]?.let { existing ->
                return@withLock registration(
                    RefreshRegistrationDisposition.JoinedExisting(command.repositoryId),
                    existing,
                )
            }

            val preflight = transactions.inTransaction {
                val configured = configurationStore.find()?.repositories?.any {
                    it.id == command.repositoryId && it.removedAt == null
                } == true
                configured to synchronizationCheckpointStore.find(command.repositoryId)
            }
            if (!preflight.first) {
                return@withLock immediate(
                    RefreshRegistrationDisposition.RepositoryNotConfigured(command.repositoryId),
                    RefreshRepositoryResult.RepositoryNotConfigured(command.repositoryId),
                )
            }

            val now = clock.instant()
            val checkpoint = preflight.second
            val backoffUntil = checkpoint?.backoffUntil
            if (backoffUntil != null && backoffUntil > now) {
                return@withLock immediate(
                    RefreshRegistrationDisposition.DeferredByBackoff(command.repositoryId, backoffUntil),
                    RefreshRepositoryResult.DeferredByBackoff(
                        command.repositoryId,
                        backoffUntil,
                        checkpoint.projection(now),
                    ),
                )
            }

            val placeholder = CompletableDeferred<RefreshRepositoryResult>()
            flights[command.repositoryId] = placeholder
            startOwner(command, placeholder)
            registration(
                RefreshRegistrationDisposition.Started(command.repositoryId),
                placeholder,
            )
        }

    @OptIn(DelicateCoroutinesApi::class, ExperimentalCoroutinesApi::class)
    private fun startOwner(
        command: RefreshRepositoryCommand,
        placeholder: CompletableDeferred<RefreshRepositoryResult>,
    ) {
        // Atomic start closes the installed-before-dispatch cancellation gap while
        // retaining the injected service scope's dispatcher and parent lifecycle.
        flightScope.launch(start = CoroutineStart.ATOMIC) {
            var outcome: Result<RefreshRepositoryResult>? = null
            try {
                setActivity(command.repositoryId, SynchronizationActivity.QUEUED)
                setActivity(command.repositoryId, SynchronizationActivity.RUNNING)
                outcome = Result.success(delegate(command).also { persistOutcome(it) })
            } catch (failure: Throwable) {
                outcome = Result.failure(failure)
            } finally {
                withContext(NonCancellable) {
                    var terminalOutcome = requireNotNull(outcome)
                    try {
                        setActivity(command.repositoryId, SynchronizationActivity.IDLE)
                    } catch (cleanupFailure: Throwable) {
                        val originalFailure = terminalOutcome.exceptionOrNull()
                        if (originalFailure == null) {
                            terminalOutcome = Result.failure(cleanupFailure)
                        } else {
                            originalFailure.addSuppressed(cleanupFailure)
                        }
                    } finally {
                        mutex.withLock {
                            if (flights[command.repositoryId] === placeholder) {
                                flights.remove(command.repositoryId)
                            }
                        }
                    }
                    terminalOutcome.fold(
                        onSuccess = placeholder::complete,
                        onFailure = placeholder::completeExceptionally,
                    )
                }
            }
        }
    }

    private suspend fun setActivity(repositoryId: RepositoryId, activity: SynchronizationActivity) {
        transactions.inTransaction {
            val current = synchronizationCheckpointStore.find(repositoryId)
                ?: emptyCheckpoint(repositoryId)
            if (current.activity != activity) {
                synchronizationCheckpointStore.save(current.copy(activity = activity))
            }
        }
    }

    private suspend fun persistOutcome(result: RefreshRepositoryResult) {
        when (result) {
            is RefreshRepositoryResult.Failed -> persistFailure(result)
            is RefreshRepositoryResult.Succeeded,
            is RefreshRepositoryResult.PartiallySucceeded,
            -> clearBackoff(result.repositoryId)
            is RefreshRepositoryResult.DeferredByBackoff,
            is RefreshRepositoryResult.RepositoryNotConfigured,
            -> Unit
        }
    }

    private suspend fun persistFailure(result: RefreshRepositoryResult.Failed) {
        val now = clock.instant()
        transactions.inTransaction {
            val current = synchronizationCheckpointStore.find(result.repositoryId)
            val failed = current.withFailure(result, now)
            synchronizationCheckpointStore.save(
                failed.copy(backoffUntil = backoff.retryAt(now, failed, result.failure)),
            )
        }
    }

    private suspend fun clearBackoff(repositoryId: RepositoryId) {
        transactions.inTransaction {
            synchronizationCheckpointStore.find(repositoryId)?.let { current ->
                if (current.consecutiveFailureCount != 0 || current.backoffUntil != null) {
                    synchronizationCheckpointStore.save(
                        current.copy(consecutiveFailureCount = 0, backoffUntil = null),
                    )
                }
            }
        }
    }

    private fun immediate(
        disposition: RefreshRegistrationDisposition,
        result: RefreshRepositoryResult,
    ) = registration(disposition, CompletableDeferred(result))

    private fun registration(
        disposition: RefreshRegistrationDisposition,
        result: Deferred<RefreshRepositoryResult>,
    ) = RepositoryRefreshRegistration(disposition, result)
}

private fun StoredSynchronizationSnapshot?.withFailure(
    result: RefreshRepositoryResult.Failed,
    now: Instant,
): StoredSynchronizationSnapshot {
    val current = this ?: emptyCheckpoint(result.repositoryId)
    val synchronization = result.synchronization
    val alreadyPersisted = current.lastAttemptOutcome == SynchronizationAttemptOutcome.FAILED &&
        synchronization.lastAttemptAt != null && current.lastAttemptAt == synchronization.lastAttemptAt
    val failureCount = when {
        alreadyPersisted -> current.consecutiveFailureCount.coerceAtLeast(1)
        current.consecutiveFailureCount == Int.MAX_VALUE -> Int.MAX_VALUE
        else -> current.consecutiveFailureCount + 1
    }
    val problem = synchronization.problem.takeUnless { it is SynchronizationProblem.None }
        ?: SynchronizationProblem.Present(PartialFailureMetadata(1, 0, listOf(result.failure)))
    return current.copy(
        lastAttemptAt = synchronization.lastAttemptAt ?: now,
        lastAttemptOutcome = synchronization.lastAttemptOutcome ?: SynchronizationAttemptOutcome.FAILED,
        lastSuccessAt = synchronization.lastSuccessAt,
        snapshotAt = synchronization.freshness.snapshotAtOrNull(),
        problem = problem,
        consecutiveFailureCount = failureCount,
        backoffUntil = null,
    )
}

private fun Freshness.snapshotAtOrNull(): Instant? = when (this) {
    Freshness.NeverSynchronized -> null
    is Freshness.Fresh -> snapshotAt
    is Freshness.Stale -> snapshotAt
}

private fun emptyCheckpoint(repositoryId: RepositoryId) = StoredSynchronizationSnapshot(
    repositoryId = repositoryId,
    activity = SynchronizationActivity.IDLE,
    lastAttemptAt = null,
    lastAttemptOutcome = null,
    lastSuccessAt = null,
    snapshotAt = null,
    problem = SynchronizationProblem.None,
    consecutiveFailureCount = 0,
    backoffUntil = null,
    pullRequestCursor = null,
    activityCursor = null,
)

private fun StoredSynchronizationSnapshot.projection(now: Instant) = SynchronizationProjection(
    repositoryId = repositoryId,
    activity = activity,
    lastAttemptAt = lastAttemptAt,
    lastAttemptOutcome = lastAttemptOutcome,
    lastSuccessAt = lastSuccessAt,
    freshness = snapshotAt?.let { Freshness.Fresh(it, Duration.between(it, now)) }
        ?: Freshness.NeverSynchronized,
    problem = problem,
)

package com.mindtable.bitbuckethelper.application.service

import com.mindtable.bitbuckethelper.application.model.RefreshRepositoryResult
import com.mindtable.bitbuckethelper.application.model.RefreshAllRepositoriesResult
import com.mindtable.bitbuckethelper.application.model.RefreshRepositoryCommand
import com.mindtable.bitbuckethelper.application.port.inbound.RefreshAllRepositories
import com.mindtable.bitbuckethelper.application.port.inbound.RefreshRepository
import com.mindtable.bitbuckethelper.application.port.outbound.ApplicationTransactionRunner
import com.mindtable.bitbuckethelper.application.port.outbound.OperationalEvent
import com.mindtable.bitbuckethelper.application.port.outbound.OperationalEventRecorder
import com.mindtable.bitbuckethelper.application.port.outbound.RefreshRepositoryOutcome
import com.mindtable.bitbuckethelper.domain.shared.RefreshRunId
import com.mindtable.bitbuckethelper.observability.MonotonicTimeSource
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

class RefreshAllRepositoriesService(
    private val transactions: ApplicationTransactionRunner,
    private val refreshRepository: RefreshRepository,
    maximumConcurrency: Int,
    private val operationalEventRecorder: OperationalEventRecorder = OperationalEventRecorder.NONE,
    private val timeSource: MonotonicTimeSource = MonotonicTimeSource.SYSTEM,
) : RefreshAllRepositories {
    private val semaphore = Semaphore(maximumConcurrency.also {
        require(it > 0) { "Refresh-all concurrency must be positive" }
    })

    override suspend fun invoke(): RefreshAllRepositoriesResult {
        val repositoryIds = transactions.inTransaction {
            configurationStore.find()?.repositories.orEmpty()
                .filter { it.removedAt == null }
                .map { it.id }
        }
        val results = coroutineScope {
            repositoryIds.map { repositoryId ->
                val capturedRepositoryId = repositoryId
                async {
                    val startedAtNanos = runCatching { timeSource.nanoTime() }.getOrDefault(0L)
                    val result = semaphore.withPermit {
                        refreshRepository(RefreshRepositoryCommand(capturedRepositoryId))
                    }
                    operationalEventRecorder.recordSafely(
                        result.toOperationalEvent(
                            refreshRunId = null,
                            durationMilliseconds = elapsedMilliseconds(startedAtNanos),
                        ),
                    )
                    result
                }
            }.awaitAll()
        }
        return RefreshAllRepositoriesResult(results)
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
        // Observability must not alter scheduled refresh outcomes.
    }
}

private fun RefreshRepositoryResult.toOperationalEvent(
    refreshRunId: RefreshRunId?,
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

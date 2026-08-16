package com.mindtable.bitbuckethelper.application.service

import com.mindtable.bitbuckethelper.application.model.RefreshRunRepositoryEntry
import com.mindtable.bitbuckethelper.application.model.RefreshRunSnapshot
import com.mindtable.bitbuckethelper.domain.shared.RefreshRunId
import com.mindtable.bitbuckethelper.domain.shared.RepositoryId
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.ArrayDeque
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

fun interface RefreshRunIdSource {
    fun next(): RefreshRunId

    companion object {
        fun sequence(ids: List<RefreshRunId>): RefreshRunIdSource {
            val remaining = ArrayDeque(ids)
            return RefreshRunIdSource {
                check(remaining.isNotEmpty()) { "Refresh run ID sequence is exhausted" }
                remaining.removeFirst()
            }
        }
    }
}

class InMemoryRefreshRunRegistry(
    private val clock: Clock,
    private val timeToLive: Duration,
    private val maximumEntries: Int,
    private val idSource: RefreshRunIdSource,
) {
    init {
        require(!timeToLive.isZero && !timeToLive.isNegative) { "Refresh run lifetime must be positive" }
        require(maximumEntries > 0) { "Refresh run capacity must be positive" }
    }

    private val mutex = Mutex()
    private val runs = linkedMapOf<RefreshRunId, MutableRun>()

    suspend fun create(repositoryIds: List<RepositoryId>): RefreshRunSnapshot = createWithEntries(
        repositoryIds.distinct().map { RefreshRunRepositoryEntry.Queued(it) },
    )

    suspend fun createWithEntries(
        entries: List<RefreshRunRepositoryEntry>,
    ): RefreshRunSnapshot = mutex.withLock {
        val now = clock.instant()
        removeExpired(now)
        while (runs.size >= maximumEntries) {
            val evicted = runs.values.minWithOrNull(compareBy<MutableRun>({ it.createdAt }, { it.id.value }))
                ?: break
            runs.remove(evicted.id)
        }
        val id = idSource.next()
        check(id !in runs) { "Refresh run ID already exists" }
        val repositories = linkedMapOf<RepositoryId, RefreshRunRepositoryEntry>()
        entries.forEach { entry ->
            repositories.putIfAbsent(entry.repositoryId, entry.detached())
        }
        val run = MutableRun(id, now, now.refreshRunSafePlus(timeToLive), repositories)
        runs[id] = run
        run.snapshot()
    }

    suspend fun find(id: RefreshRunId): RefreshRunSnapshot? = mutex.withLock {
        val now = clock.instant()
        removeExpired(now)
        runs[id]?.snapshot()
    }

    suspend fun update(id: RefreshRunId, entry: RefreshRunRepositoryEntry): Boolean = mutex.withLock {
        val now = clock.instant()
        removeExpired(now)
        val run = runs[id] ?: return@withLock false
        val current = run.repositories[entry.repositoryId] ?: return@withLock false
        if (!current.canTransitionTo(entry)) return@withLock false
        run.repositories[entry.repositoryId] = entry.detached()
        true
    }

    suspend fun removeRepository(id: RefreshRunId, repositoryId: RepositoryId): Boolean = mutex.withLock {
        val now = clock.instant()
        removeExpired(now)
        runs[id]?.repositories?.remove(repositoryId) != null
    }

    private fun removeExpired(now: Instant) {
        runs.entries.removeIf { (_, run) -> now >= run.expiresAt }
    }
}

private data class MutableRun(
    val id: RefreshRunId,
    val createdAt: Instant,
    val expiresAt: Instant,
    val repositories: LinkedHashMap<RepositoryId, RefreshRunRepositoryEntry>,
) {
    fun snapshot() = RefreshRunSnapshot(
        id,
        createdAt,
        expiresAt,
        java.util.List.copyOf(repositories.values.map(RefreshRunRepositoryEntry::detached)),
    )
}

private fun RefreshRunRepositoryEntry.canTransitionTo(next: RefreshRunRepositoryEntry): Boolean {
    if (repositoryId != next.repositoryId) return false
    return when (this) {
        is RefreshRunRepositoryEntry.Queued ->
            next is RefreshRunRepositoryEntry.Running || next.isTerminal()
        is RefreshRunRepositoryEntry.Running -> next.isTerminal()
        is RefreshRunRepositoryEntry.Succeeded,
        is RefreshRunRepositoryEntry.PartiallySucceeded,
        is RefreshRunRepositoryEntry.Failed,
        is RefreshRunRepositoryEntry.DeferredByBackoff,
        -> false
    }
}

private fun RefreshRunRepositoryEntry.isTerminal(): Boolean = when (this) {
    is RefreshRunRepositoryEntry.Queued,
    is RefreshRunRepositoryEntry.Running,
    -> false
    is RefreshRunRepositoryEntry.Succeeded,
    is RefreshRunRepositoryEntry.PartiallySucceeded,
    is RefreshRunRepositoryEntry.Failed,
    is RefreshRunRepositoryEntry.DeferredByBackoff,
    -> true
}

private fun RefreshRunRepositoryEntry.detached(): RefreshRunRepositoryEntry = when (this) {
    is RefreshRunRepositoryEntry.Queued -> copy()
    is RefreshRunRepositoryEntry.Running -> copy()
    is RefreshRunRepositoryEntry.Succeeded -> copy()
    is RefreshRunRepositoryEntry.PartiallySucceeded -> copy(
        partialFailure = partialFailure.copy(failures = java.util.List.copyOf(partialFailure.failures)),
    )
    is RefreshRunRepositoryEntry.Failed -> copy(failure = failure.copy())
    is RefreshRunRepositoryEntry.DeferredByBackoff -> copy()
}

private fun Instant.refreshRunSafePlus(duration: Duration): Instant =
    runCatching { plus(duration) }.getOrDefault(Instant.MAX)

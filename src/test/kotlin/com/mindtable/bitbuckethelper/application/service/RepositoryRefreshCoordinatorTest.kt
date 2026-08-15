package com.mindtable.bitbuckethelper.application.service

import com.mindtable.bitbuckethelper.application.model.Freshness
import com.mindtable.bitbuckethelper.application.model.PartialFailureMetadata
import com.mindtable.bitbuckethelper.application.model.RefreshRegistrationDisposition
import com.mindtable.bitbuckethelper.application.model.RefreshRepositoryCommand
import com.mindtable.bitbuckethelper.application.model.RefreshRepositoryResult
import com.mindtable.bitbuckethelper.application.model.StoredConfiguredRepository
import com.mindtable.bitbuckethelper.application.model.StoredInstallationConfiguration
import com.mindtable.bitbuckethelper.application.model.StoredSynchronizationSnapshot
import com.mindtable.bitbuckethelper.application.model.SynchronizationActivity
import com.mindtable.bitbuckethelper.application.model.SynchronizationAttemptOutcome
import com.mindtable.bitbuckethelper.application.model.SynchronizationFailure
import com.mindtable.bitbuckethelper.application.model.SynchronizationFailureCategory
import com.mindtable.bitbuckethelper.application.model.SynchronizationProblem
import com.mindtable.bitbuckethelper.application.model.SynchronizationProjection
import com.mindtable.bitbuckethelper.application.policy.SynchronizationBackoff
import com.mindtable.bitbuckethelper.application.port.inbound.RefreshRepository
import com.mindtable.bitbuckethelper.application.port.outbound.ActionItemStore
import com.mindtable.bitbuckethelper.application.port.outbound.ApplicationTransaction
import com.mindtable.bitbuckethelper.application.port.outbound.ApplicationTransactionRunner
import com.mindtable.bitbuckethelper.application.port.outbound.ConfigurationStore
import com.mindtable.bitbuckethelper.application.port.outbound.NotificationIntentStore
import com.mindtable.bitbuckethelper.application.port.outbound.PullRequestStore
import com.mindtable.bitbuckethelper.application.port.outbound.ReminderProjectionStore
import com.mindtable.bitbuckethelper.application.port.outbound.SynchronizationCheckpointStore
import com.mindtable.bitbuckethelper.domain.shared.RepositoryId
import com.mindtable.bitbuckethelper.domain.shared.WorkspaceId
import java.net.URI
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.lang.reflect.Proxy
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RepositoryRefreshCoordinatorTest {
    @Test
    fun `same repository callers join one flight and receive the same typed result`() = runTest {
        val persistence = configuredPersistence(listOf(repositoryA))
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val calls = AtomicInteger()
        val expected = succeeded(repositoryA)
        val delegate = RefreshRepository {
            calls.incrementAndGet()
            entered.complete(Unit)
            release.await()
            expected
        }
        val coordinator = coordinator(persistence, delegate, backgroundScope)

        val first = coordinator.register(RefreshRepositoryCommand(repositoryA))
        assertInstanceOf(RefreshRegistrationDisposition.Started::class.java, first.disposition)
        entered.await()
        val second = coordinator.register(RefreshRepositoryCommand(repositoryA))
        assertInstanceOf(RefreshRegistrationDisposition.JoinedExisting::class.java, second.disposition)
        runCurrent()

        assertEquals(1, calls.get())
        release.complete(Unit)
        assertEquals(expected, first.await())
        assertEquals(expected, second.await())
    }

    @Test
    fun `canceling one waiter does not cancel the service scope flight or another waiter`() = runTest {
        val persistence = configuredPersistence(listOf(repositoryA))
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var delegateCancelled = false
        val expected = succeeded(repositoryA)
        val delegate = RefreshRepository {
            entered.complete(Unit)
            try {
                release.await()
                expected
            } finally {
                delegateCancelled = !release.isCompleted
            }
        }
        val coordinator = coordinator(persistence, delegate, backgroundScope)

        val canceledWaiter = launch { coordinator(RefreshRepositoryCommand(repositoryA)) }
        entered.await()
        val survivingWaiter = async { coordinator(RefreshRepositoryCommand(repositoryA)) }
        runCurrent()
        canceledWaiter.cancelAndJoin()

        assertFalse(delegateCancelled)
        release.complete(Unit)
        assertEquals(expected, survivingWaiter.await())
    }

    @Test
    fun `different repository flights overlap without serialized refresh work`() = runTest {
        val persistence = configuredPersistence(listOf(repositoryA, repositoryB))
        val started = Channel<RepositoryId>(Channel.UNLIMITED)
        val gates = mapOf(repositoryA to CompletableDeferred<Unit>(), repositoryB to CompletableDeferred())
        val delegate = RefreshRepository { command ->
            started.send(command.repositoryId)
            gates.getValue(command.repositoryId).await()
            succeeded(command.repositoryId)
        }
        val coordinator = coordinator(persistence, delegate, backgroundScope)

        val first = async { coordinator(RefreshRepositoryCommand(repositoryA)) }
        val second = async { coordinator(RefreshRepositoryCommand(repositoryB)) }
        val overlapping = setOf(started.receive(), started.receive())

        assertEquals(setOf(repositoryA, repositoryB), overlapping)
        gates.values.forEach { it.complete(Unit) }
        assertInstanceOf(RefreshRepositoryResult.Succeeded::class.java, first.await())
        assertInstanceOf(RefreshRepositoryResult.Succeeded::class.java, second.await())
    }

    @Test
    fun `exceptional and cancelled flights clean up by identity so later calls start`() = runTest {
        val persistence = configuredPersistence(listOf(repositoryA))
        val calls = AtomicInteger()
        val delegate = RefreshRepository { command ->
            when (calls.incrementAndGet()) {
                1 -> error("first flight failed")
                3 -> throw CancellationException("third flight cancelled")
                else -> succeeded(command.repositoryId)
            }
        }
        val coordinator = coordinator(persistence, delegate, backgroundScope)

        assertInstanceOf(
            IllegalStateException::class.java,
            runCatching { coordinator(RefreshRepositoryCommand(repositoryA)) }.exceptionOrNull(),
        )
        assertInstanceOf(RefreshRepositoryResult.Succeeded::class.java, coordinator(RefreshRepositoryCommand(repositoryA)))
        assertInstanceOf(
            CancellationException::class.java,
            runCatching { coordinator(RefreshRepositoryCommand(repositoryA)) }.exceptionOrNull(),
        )
        assertInstanceOf(RefreshRepositoryResult.Succeeded::class.java, coordinator(RefreshRepositoryCommand(repositoryA)))

        assertEquals(4, calls.get())
        assertEquals(SynchronizationActivity.IDLE, persistence.checkpoint(repositoryA)?.activity)
    }

    @Test
    fun `lifecycle persists queued running and idle while preserving synchronization content`() = runTest {
        val persistence = TestPersistence()
        persistence.configure(listOf(repositoryA))
        val original = checkpoint(
            repositoryA,
            activity = SynchronizationActivity.IDLE,
            consecutiveFailures = 0,
            backoffUntil = null,
        )
        persistence.saveCheckpoint(original)
        persistence.savedActivities.clear()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val delegate = RefreshRepository { command ->
            entered.complete(Unit)
            release.await()
            RefreshRepositoryResult.RepositoryNotConfigured(command.repositoryId)
        }
        val coordinator = coordinator(persistence, delegate, backgroundScope)

        val result = async { coordinator(RefreshRepositoryCommand(repositoryA)) }
        entered.await()
        assertEquals(
            listOf(SynchronizationActivity.QUEUED, SynchronizationActivity.RUNNING),
            persistence.savedActivities.map { it.second },
        )
        release.complete(Unit)
        assertInstanceOf(RefreshRepositoryResult.RepositoryNotConfigured::class.java, result.await())

        assertEquals(
            listOf(SynchronizationActivity.QUEUED, SynchronizationActivity.RUNNING, SynchronizationActivity.IDLE),
            persistence.savedActivities.map { it.second },
        )
        assertEquals(original, persistence.checkpoint(repositoryA))
    }

    @Test
    fun `future backoff defers without delegate or false running lifecycle while equality permits run`() = runTest {
        val retryAt = now.plusSeconds(60)
        val clock = MutableClock(now)
        val persistence = TestPersistence()
        persistence.configure(listOf(repositoryA))
        persistence.saveCheckpoint(checkpoint(repositoryA, backoffUntil = retryAt))
        persistence.savedActivities.clear()
        val calls = AtomicInteger()
        val delegate = RefreshRepository { command ->
            calls.incrementAndGet()
            succeeded(command.repositoryId)
        }
        val coordinator = coordinator(persistence, delegate, backgroundScope, clock)

        val registration = coordinator.register(RefreshRepositoryCommand(repositoryA))
        assertInstanceOf(RefreshRegistrationDisposition.DeferredByBackoff::class.java, registration.disposition)
        val deferred = assertInstanceOf(RefreshRepositoryResult.DeferredByBackoff::class.java, registration.await())
        assertEquals(retryAt, deferred.retryAt)
        assertEquals(0, calls.get())
        assertTrue(persistence.savedActivities.isEmpty())

        clock.current = retryAt
        assertInstanceOf(RefreshRepositoryResult.Succeeded::class.java, coordinator(RefreshRepositoryCommand(repositoryA)))
        assertEquals(1, calls.get())
        assertEquals(
            listOf(SynchronizationActivity.QUEUED, SynchronizationActivity.RUNNING, SynchronizationActivity.IDLE),
            persistence.savedActivities.map { it.second },
        )
    }

    @Test
    fun `failed attempt persists backoff that a newly constructed coordinator honors`() = runTest {
        val clock = MutableClock(now)
        val persistence = configuredPersistence(listOf(repositoryA))
        val calls = AtomicInteger()
        val failure = SynchronizationFailure(SynchronizationFailureCategory.NETWORK, true, null)
        val delegate = RefreshRepository { command ->
            calls.incrementAndGet()
            failed(command.repositoryId, failure)
        }

        val firstCoordinator = coordinator(persistence, delegate, backgroundScope, clock)
        assertInstanceOf(RefreshRepositoryResult.Failed::class.java, firstCoordinator(RefreshRepositoryCommand(repositoryA)))
        assertEquals(now.plusSeconds(30), persistence.checkpoint(repositoryA)?.backoffUntil)

        val reconstructedCoordinator = coordinator(persistence, delegate, backgroundScope, clock)
        val registration = reconstructedCoordinator.register(RefreshRepositoryCommand(repositoryA))
        assertInstanceOf(RefreshRegistrationDisposition.DeferredByBackoff::class.java, registration.disposition)
        assertInstanceOf(RefreshRepositoryResult.DeferredByBackoff::class.java, registration.await())
        assertEquals(1, calls.get())
    }

    @Test
    fun `successful run at backoff expiry clears failure count and backoff`() = runTest {
        val persistence = configuredPersistence(listOf(repositoryA))
        persistence.saveCheckpoint(checkpoint(repositoryA, consecutiveFailures = 5, backoffUntil = now))
        val coordinator = coordinator(
            persistence,
            RefreshRepository { command -> succeeded(command.repositoryId) },
            backgroundScope,
        )

        assertInstanceOf(RefreshRepositoryResult.Succeeded::class.java, coordinator(RefreshRepositoryCommand(repositoryA)))

        val stored = requireNotNull(persistence.checkpoint(repositoryA))
        assertEquals(0, stored.consecutiveFailureCount)
        assertNull(stored.backoffUntil)
        assertEquals(SynchronizationActivity.IDLE, stored.activity)
    }

    @Test
    fun `repository not configured is typed and never invokes delegate`() = runTest {
        val persistence = configuredPersistence(listOf(repositoryA), removed = setOf(repositoryA))
        val calls = AtomicInteger()
        val coordinator = coordinator(
            persistence,
            RefreshRepository { command -> calls.incrementAndGet(); succeeded(command.repositoryId) },
            backgroundScope,
        )

        val registration = coordinator.register(RefreshRepositoryCommand(repositoryA))
        assertInstanceOf(RefreshRegistrationDisposition.RepositoryNotConfigured::class.java, registration.disposition)
        assertInstanceOf(RefreshRepositoryResult.RepositoryNotConfigured::class.java, registration.await())
        assertEquals(0, calls.get())
        assertNull(persistence.checkpoint(repositoryA))
    }

    @Test
    fun `coordinator exposes no force or bypass entry point`() {
        val publicNames = RepositoryRefreshCoordinator::class.java.methods.map { it.name.lowercase() }

        assertTrue(publicNames.none { "force" in it || "bypass" in it })
    }

    @Test
    fun `refresh all ignores removed repositories bounds concurrency and preserves ordered typed results`() = runTest {
        val persistence = configuredPersistence(
            listOf(repositoryC, removedRepository, repositoryA, repositoryB),
            removed = setOf(removedRepository),
        )
        val started = Channel<RepositoryId>(Channel.UNLIMITED)
        val active = AtomicInteger()
        val maximumActive = AtomicInteger()
        val gates = listOf(repositoryA, repositoryB, repositoryC).associateWith { CompletableDeferred<Unit>() }
        val expected = mapOf<RepositoryId, RefreshRepositoryResult>(
            repositoryC to succeeded(repositoryC),
            repositoryA to RefreshRepositoryResult.RepositoryNotConfigured(repositoryA),
            repositoryB to RefreshRepositoryResult.DeferredByBackoff(repositoryB, now.plusSeconds(90), projection(repositoryB)),
        )
        val calls = mutableListOf<RepositoryId>()
        val refresh = RefreshRepository { command ->
            calls += command.repositoryId
            val running = active.incrementAndGet()
            maximumActive.updateAndGet { maxOf(it, running) }
            started.send(command.repositoryId)
            try {
                gates.getValue(command.repositoryId).await()
                expected.getValue(command.repositoryId)
            } finally {
                active.decrementAndGet()
            }
        }
        val service = RefreshAllRepositoriesService(persistence, refresh, maximumConcurrency = 2)

        val result = async { service() }
        val first = started.receive()
        val second = started.receive()
        runCurrent()
        assertTrue(started.tryReceive().isFailure)
        gates.getValue(first).complete(Unit)
        val third = started.receive()
        gates.getValue(second).complete(Unit)
        gates.getValue(third).complete(Unit)

        assertEquals(listOf(repositoryC, repositoryA, repositoryB), result.await().repositories.map { it.repositoryId })
        assertEquals(listOf(expected.getValue(repositoryC), expected.getValue(repositoryA), expected.getValue(repositoryB)), result.await().repositories)
        assertEquals(setOf(repositoryA, repositoryB, repositoryC), calls.toSet())
        assertFalse(removedRepository in calls)
        assertEquals(2, maximumActive.get())
    }

    @Test
    fun `refresh all is structured and cancels its bounded children`() = runTest {
        val persistence = configuredPersistence(listOf(repositoryA, repositoryB))
        val started = Channel<RepositoryId>(Channel.UNLIMITED)
        val cancelled = Channel<RepositoryId>(Channel.UNLIMITED)
        val refresh = RefreshRepository { command ->
            started.send(command.repositoryId)
            try {
                awaitCancellation()
            } finally {
                cancelled.send(command.repositoryId)
            }
        }
        val service = RefreshAllRepositoriesService(persistence, refresh, maximumConcurrency = 2)

        val run = launch { service() }
        val running = setOf(started.receive(), started.receive())
        run.cancelAndJoin()

        assertEquals(running, setOf(cancelled.receive(), cancelled.receive()))
    }

    @Test
    fun `refresh all requires a positive concurrency bound`() {
        val persistence = TestPersistence()
        val refresh = RefreshRepository { command -> succeeded(command.repositoryId) }

        assertThrows(IllegalArgumentException::class.java) {
            RefreshAllRepositoriesService(persistence, refresh, maximumConcurrency = 0)
        }
    }

    private fun coordinator(
        persistence: ApplicationTransactionRunner,
        delegate: RefreshRepository,
        scope: kotlinx.coroutines.CoroutineScope,
        clock: Clock = MutableClock(now),
    ) = RepositoryRefreshCoordinator(
        transactions = persistence,
        delegate = delegate,
        serviceScope = scope,
        clock = clock,
        backoff = SynchronizationBackoff(),
    )
}

private val now = Instant.parse("2026-08-15T12:00:00Z")
private val repositoryA = RepositoryId("repo_a")
private val repositoryB = RepositoryId("repo_b")
private val repositoryC = RepositoryId("repo_c")
private val removedRepository = RepositoryId("repo_removed")
private val workspaceId = WorkspaceId("ws_team")

private fun succeeded(repositoryId: RepositoryId) = RefreshRepositoryResult.Succeeded(
    repositoryId,
    now,
    projection(repositoryId),
)

private fun failed(repositoryId: RepositoryId, failure: SynchronizationFailure) = RefreshRepositoryResult.Failed(
    repositoryId,
    failure,
    projection(
        repositoryId,
        outcome = SynchronizationAttemptOutcome.FAILED,
        problem = SynchronizationProblem.Present(PartialFailureMetadata(1, 0, listOf(failure))),
    ),
)

private fun projection(
    repositoryId: RepositoryId,
    outcome: SynchronizationAttemptOutcome = SynchronizationAttemptOutcome.SUCCEEDED,
    problem: SynchronizationProblem = SynchronizationProblem.None,
) = SynchronizationProjection(
    repositoryId,
    SynchronizationActivity.IDLE,
    now,
    outcome,
    now,
    Freshness.Fresh(now, java.time.Duration.ZERO),
    problem,
)

private fun checkpoint(
    repositoryId: RepositoryId,
    activity: SynchronizationActivity = SynchronizationActivity.IDLE,
    consecutiveFailures: Int = 2,
    backoffUntil: Instant? = null,
) = StoredSynchronizationSnapshot(
    repositoryId = repositoryId,
    activity = activity,
    lastAttemptAt = now.minusSeconds(5),
    lastAttemptOutcome = SynchronizationAttemptOutcome.FAILED,
    lastSuccessAt = now.minusSeconds(60),
    snapshotAt = now.minusSeconds(60),
    problem = SynchronizationProblem.Present(
        PartialFailureMetadata(
            1,
            0,
            listOf(SynchronizationFailure(SynchronizationFailureCategory.NETWORK, true, null)),
        ),
    ),
    consecutiveFailureCount = consecutiveFailures,
    backoffUntil = backoffUntil,
    pullRequestCursor = "pull-cursor",
    activityCursor = "activity-cursor",
)

private fun configuredPersistence(
    repositories: List<RepositoryId>,
    removed: Set<RepositoryId> = emptySet(),
) = TestPersistence().also { persistence ->
    kotlinx.coroutines.runBlocking { persistence.configure(repositories, removed = removed) }
}

private suspend fun ApplicationTransactionRunner.configure(
    repositories: List<RepositoryId>,
    removed: Set<RepositoryId> = emptySet(),
) {
    inTransaction {
        configurationStore.save(
            StoredInstallationConfiguration(
                workspaceId = workspaceId,
                bitbucketApiBaseUrl = URI("https://api.bitbucket.org/2.0"),
                workspaceSlug = "team",
                workspaceDisplayName = "Team",
                workspaceWebUrl = URI("https://bitbucket.org/team"),
                currentUserStableId = "user-1",
                currentUserDisplayName = "User",
                configuredAt = now,
                retentionDays = 30,
                repositories = repositories.map { repositoryId ->
                    StoredConfiguredRepository(
                        id = repositoryId,
                        workspaceId = workspaceId,
                        slug = repositoryId.value.removePrefix("repo_"),
                        displayName = repositoryId.value,
                        webUrl = URI("https://bitbucket.org/team/${repositoryId.value}"),
                        removedAt = now.takeIf { repositoryId in removed },
                    )
                },
            ),
        )
    }
}

private suspend fun ApplicationTransactionRunner.saveCheckpoint(snapshot: StoredSynchronizationSnapshot) {
    inTransaction { synchronizationCheckpointStore.save(snapshot) }
}

private suspend fun ApplicationTransactionRunner.checkpoint(repositoryId: RepositoryId) =
    inTransaction { synchronizationCheckpointStore.find(repositoryId) }

private class MutableClock(var current: Instant) : Clock() {
    override fun getZone(): ZoneId = ZoneOffset.UTC
    override fun withZone(zone: ZoneId): Clock = Clock.fixed(current, zone)
    override fun instant(): Instant = current
}

private class TestPersistence : ApplicationTransactionRunner {
    private val mutex = Mutex()
    private var configuration: StoredInstallationConfiguration? = null
    private var checkpoints = linkedMapOf<RepositoryId, StoredSynchronizationSnapshot>()
    val savedActivities = mutableListOf<Pair<RepositoryId, SynchronizationActivity>>()

    override suspend fun <T> inTransaction(block: suspend ApplicationTransaction.() -> T): T = mutex.withLock {
        var workingConfiguration = configuration
        val workingCheckpoints = checkpoints.toMutableMap()
        val result = block(
                object : ApplicationTransaction {
                    override val configurationStore: ConfigurationStore = object : ConfigurationStore {
                        override suspend fun find() = workingConfiguration
                        override suspend fun save(configuration: StoredInstallationConfiguration) {
                            workingConfiguration = configuration
                        }
                    }
                    override val pullRequestStore: PullRequestStore = unusedPort()
                    override val actionItemStore: ActionItemStore = unusedPort()
                    override val synchronizationCheckpointStore: SynchronizationCheckpointStore =
                        object : SynchronizationCheckpointStore {
                            override suspend fun find(repositoryId: RepositoryId) =
                                workingCheckpoints[repositoryId]

                            override suspend fun list() = workingCheckpoints.values.sortedBy { it.repositoryId.value }

                            override suspend fun save(snapshot: StoredSynchronizationSnapshot) {
                                if (workingCheckpoints[snapshot.repositoryId]?.activity != snapshot.activity) {
                                    savedActivities += snapshot.repositoryId to snapshot.activity
                                }
                                workingCheckpoints[snapshot.repositoryId] = snapshot
                            }
                        }
                    override val notificationIntentStore: NotificationIntentStore = unusedPort()
                    override val reminderProjectionStore: ReminderProjectionStore = unusedPort()
                },
            )
        configuration = workingConfiguration
        checkpoints = workingCheckpoints.toMap(linkedMapOf())
        result
    }
}

@Suppress("UNCHECKED_CAST")
private inline fun <reified T> unusedPort(): T = Proxy.newProxyInstance(
    T::class.java.classLoader,
    arrayOf(T::class.java),
) { _, method, _ -> error("Unexpected test call to ${method.name}") } as T

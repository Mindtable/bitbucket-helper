package com.mindtable.bitbuckethelper.application.service

import com.mindtable.bitbuckethelper.application.model.*
import com.mindtable.bitbuckethelper.application.policy.SynchronizationBackoff
import com.mindtable.bitbuckethelper.application.port.inbound.RefreshRepository
import com.mindtable.bitbuckethelper.application.port.outbound.*
import com.mindtable.bitbuckethelper.domain.shared.*
import java.net.URI
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.ArrayDeque
import java.util.concurrent.CancellationException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import com.mindtable.bitbuckethelper.observability.MonotonicTimeSource

@OptIn(ExperimentalCoroutinesApi::class)
class RefreshRunServicesTest {
    private val now = Instant.parse("2026-08-15T12:00:00Z")

    @Test
    fun `registered run correlates every monitored repository terminal outcome`() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val serviceScope = kotlinx.coroutines.CoroutineScope(SupervisorJob() + testDispatcher)
        val events = mutableListOf<OperationalEvent>()
        val services = service(
            RefreshState(configuration = configuration(listOf(repository(repositoryA)))),
            RefreshRepository { succeeded(repositoryA) },
            serviceScope,
            recorder = OperationalEventRecorder(events::add),
            timeSource = MonotonicTimeSource { 1_000L },
        )

        val registered = assertInstanceOf(
            StartRefreshRunResult.RefreshRunRegistered::class.java,
            services.start(StartRefreshRunCommand(RefreshTarget.AllConfiguredRepositories)),
        )
        runCurrent()

        assertEquals(
            OperationalEvent.RefreshRunRegistered(
                refreshRunId = registered.refreshRun.id,
                repositoryCount = 1,
                startedCount = 1,
                joinedCount = 0,
                deferredCount = 0,
                notConfiguredCount = 0,
            ),
            events.filterIsInstance<OperationalEvent.RefreshRunRegistered>().single(),
        )
        val finished = events.filterIsInstance<OperationalEvent.RefreshRepositoryFinished>().single()
        assertEquals(registered.refreshRun.id, finished.refreshRunId)
        assertEquals(repositoryA, finished.repositoryId)
        assertEquals(RefreshRepositoryOutcome.SUCCEEDED, finished.outcome)
        assertEquals(0L, finished.durationMilliseconds)
        serviceScope.coroutineContext[kotlinx.coroutines.Job]!!.cancelAndJoin()
    }

    @Test
    fun `registration records exact disposition counts and immediate terminal outcomes`() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val serviceScope = kotlinx.coroutines.CoroutineScope(SupervisorJob() + testDispatcher)
        val events = mutableListOf<OperationalEvent>()
        val retryAt = now.plusSeconds(60)
        val services = service(
            RefreshState(
                configuration = configuration(
                    listOf(repository(repositoryA), repository(repositoryB), repository(repositoryRemoved, removedAt = now)),
                ),
                checkpoints = mutableListOf(checkpoint(repositoryB, backoffUntil = retryAt)),
            ),
            RefreshRepository { succeeded(it.repositoryId) },
            serviceScope,
            recorder = OperationalEventRecorder(events::add),
            timeSource = MonotonicTimeSource { 0L },
        )

        val registered = assertInstanceOf(
            StartRefreshRunResult.RefreshRunRegistered::class.java,
            services.start(
                StartRefreshRunCommand(
                    RefreshTarget.Repositories(listOf(repositoryB, repositoryA, repositoryMissing, repositoryRemoved)),
                ),
            ),
        )
        runCurrent()

        assertEquals(
            OperationalEvent.RefreshRunRegistered(
                refreshRunId = registered.refreshRun.id,
                repositoryCount = 4,
                startedCount = 1,
                joinedCount = 0,
                deferredCount = 1,
                notConfiguredCount = 2,
            ),
            events.filterIsInstance<OperationalEvent.RefreshRunRegistered>().single(),
        )
        val terminalEvents = events.filterIsInstance<OperationalEvent.RefreshRepositoryFinished>()
        assertEquals(4, terminalEvents.size)
        assertEquals(
            listOf(repositoryB, repositoryA, repositoryMissing, repositoryRemoved),
            terminalEvents.map { it.repositoryId },
        )
        assertEquals(
            listOf(
                RefreshRepositoryOutcome.DEFERRED,
                RefreshRepositoryOutcome.SUCCEEDED,
                RefreshRepositoryOutcome.NOT_CONFIGURED,
                RefreshRepositoryOutcome.NOT_CONFIGURED,
            ),
            terminalEvents.map { it.outcome },
        )
        terminalEvents.forEach { event ->
            assertEquals(registered.refreshRun.id, event.refreshRunId)
            assertEquals(0L, event.durationMilliseconds)
        }
        serviceScope.coroutineContext[kotlinx.coroutines.Job]!!.cancelAndJoin()
    }

    @Test
    fun `joined unexpected flight emits exactly one run-correlated event per monitoring run`() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val serviceScope = kotlinx.coroutines.CoroutineScope(SupervisorJob() + testDispatcher)
        val events = mutableListOf<OperationalEvent>()
        val release = CompletableDeferred<Unit>()
        val failure = IllegalStateException("private shared-flight payload")
        val services = service(
            RefreshState(configuration = configuration(listOf(repository(repositoryA)))),
            RefreshRepository {
                release.await()
                throw failure
            },
            serviceScope,
            recorder = OperationalEventRecorder(events::add),
            timeSource = MonotonicTimeSource { 0L },
        )

        val first = assertInstanceOf(
            StartRefreshRunResult.RefreshRunRegistered::class.java,
            services.start(StartRefreshRunCommand(RefreshTarget.Repositories(listOf(repositoryA)))),
        )
        runCurrent()
        val second = assertInstanceOf(
            StartRefreshRunResult.RefreshRunRegistered::class.java,
            services.start(StartRefreshRunCommand(RefreshTarget.Repositories(listOf(repositoryA)))),
        )
        assertInstanceOf(RefreshRegistrationDisposition.JoinedExisting::class.java, second.dispositions.single())
        release.complete(Unit)
        runCurrent()

        val terminalEvents = events.filterIsInstance<OperationalEvent.RefreshRepositoryFinished>()
        assertEquals(2, terminalEvents.size)
        assertEquals(
            listOf(first.refreshRun.id, second.refreshRun.id),
            terminalEvents.map { requireNotNull(it.refreshRunId) },
        )
        assertEquals(listOf(repositoryA, repositoryA), terminalEvents.map { it.repositoryId })
        assertTrue(terminalEvents.all { it.outcome == RefreshRepositoryOutcome.UNEXPECTED })
        assertTrue(terminalEvents.all { it.failureCategory == null && it.retryable == null && it.retryAt == null })
        assertEquals(listOf(0L, 0L), terminalEvents.map { it.durationMilliseconds })
        serviceScope.coroutineContext[kotlinx.coroutines.Job]!!.cancelAndJoin()
    }

    @Test
    fun `joined successful flight emits one success event per monitoring run`() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val serviceScope = kotlinx.coroutines.CoroutineScope(SupervisorJob() + testDispatcher)
        val events = mutableListOf<OperationalEvent>()
        val release = CompletableDeferred<Unit>()
        val services = service(
            RefreshState(configuration = configuration(listOf(repository(repositoryA)))),
            RefreshRepository {
                release.await()
                succeeded(repositoryA)
            },
            serviceScope,
            recorder = OperationalEventRecorder(events::add),
            timeSource = MonotonicTimeSource { 0L },
        )

        val first = assertInstanceOf(
            StartRefreshRunResult.RefreshRunRegistered::class.java,
            services.start(StartRefreshRunCommand(RefreshTarget.Repositories(listOf(repositoryA)))),
        )
        runCurrent()
        val second = assertInstanceOf(
            StartRefreshRunResult.RefreshRunRegistered::class.java,
            services.start(StartRefreshRunCommand(RefreshTarget.Repositories(listOf(repositoryA)))),
        )
        release.complete(Unit)
        runCurrent()

        val terminalEvents = events.filterIsInstance<OperationalEvent.RefreshRepositoryFinished>()
        assertEquals(2, terminalEvents.size)
        assertEquals(listOf(first.refreshRun.id, second.refreshRun.id), terminalEvents.map { requireNotNull(it.refreshRunId) })
        assertEquals(listOf(RefreshRepositoryOutcome.SUCCEEDED, RefreshRepositoryOutcome.SUCCEEDED), terminalEvents.map { it.outcome })
        assertEquals(listOf(0L, 0L), terminalEvents.map { it.durationMilliseconds })
        serviceScope.coroutineContext[kotlinx.coroutines.Job]!!.cancelAndJoin()
    }

    @Test
    fun `registry uses injected lifetime capacity and ids with immutable copies and exact expiry`() = runTest {
        val clock = MutableRunClock(now)
        val ids = ArrayDeque(listOf(RefreshRunId("rr_alpha"), RefreshRunId("rr_beta"), RefreshRunId("rr_gamma")))
        val registry = InMemoryRefreshRunRegistry(
            clock = clock,
            timeToLive = Duration.ofSeconds(10),
            maximumEntries = 2,
            idSource = RefreshRunIdSource { ids.removeFirst() },
        )
        val mutableRepositories = mutableListOf(repositoryA)

        val alpha = registry.create(mutableRepositories)
        mutableRepositories.clear()
        assertEquals(RefreshRunId("rr_alpha"), alpha.id)
        assertEquals(now.plusSeconds(10), alpha.expiresAt)
        assertEquals(listOf(repositoryA), registry.find(alpha.id)!!.repositories.map { it.repositoryId })
        tryToClear(registry.find(alpha.id)!!.repositories)
        assertEquals(listOf(repositoryA), registry.find(alpha.id)!!.repositories.map { it.repositoryId })

        clock.current = now.plusSeconds(1)
        val beta = registry.create(listOf(repositoryB))
        clock.current = now.plusSeconds(2)
        val gamma = registry.create(listOf(repositoryC))
        assertNull(registry.find(alpha.id), "oldest entry must be deterministically evicted")
        assertNotNull(registry.find(beta.id))
        assertNotNull(registry.find(gamma.id))
        assertFalse(registry.update(alpha.id, RefreshRunRepositoryEntry.Running(repositoryA)))
        assertNull(registry.find(alpha.id), "late updates must not resurrect evicted runs")

        clock.current = beta.expiresAt
        assertNull(registry.find(beta.id), "a run is unavailable at its exact expiry")
        assertFalse(registry.update(beta.id, RefreshRunRepositoryEntry.Succeeded(repositoryB, clock.current)))
        assertNull(registry.find(RefreshRunId("rr_unknown")))
    }

    @Test
    fun `registry evicts equal-age entries by id and ignores out of order updates after terminal state`() = runTest {
        val ids = ArrayDeque(listOf(RefreshRunId("rr_zulu"), RefreshRunId("rr_alpha"), RefreshRunId("rr_middle")))
        val registry = InMemoryRefreshRunRegistry(
            Clock.fixed(now, ZoneOffset.UTC),
            Duration.ofMinutes(5),
            2,
            RefreshRunIdSource { ids.removeFirst() },
        )
        val zulu = registry.create(listOf(repositoryA))
        val alpha = registry.create(listOf(repositoryB))
        val middle = registry.create(listOf(repositoryC))

        assertNull(registry.find(alpha.id), "lexically smaller id is evicted first when creation times tie")
        assertNotNull(registry.find(zulu.id))
        assertNotNull(registry.find(middle.id))
        assertTrue(registry.update(zulu.id, RefreshRunRepositoryEntry.Running(repositoryA)))
        assertTrue(registry.update(zulu.id, RefreshRunRepositoryEntry.Succeeded(repositoryA, now)))
        assertFalse(registry.update(zulu.id, RefreshRunRepositoryEntry.Running(repositoryA)))
        assertInstanceOf(
            RefreshRunRepositoryEntry.Succeeded::class.java,
            registry.find(zulu.id)!!.repositories.single(),
        )
    }

    @Test
    fun `start reports workspace and repository configuration outcomes without creating runs`() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val serviceScope = kotlinx.coroutines.CoroutineScope(SupervisorJob() + testDispatcher)
        val noWorkspaceState = RefreshState()
        val noWorkspace = service(
            noWorkspaceState,
            RefreshRepository { error("must not refresh") },
            serviceScope,
        )
        assertEquals(
            StartRefreshRunResult.WorkspaceNotConfigured,
            noWorkspace.start(StartRefreshRunCommand(RefreshTarget.AllConfiguredRepositories)),
        )

        val removedOnly = RefreshState(configuration = configuration(listOf(repository(repositoryA, removedAt = now))))
        val noRepositories = service(removedOnly, RefreshRepository { error("must not refresh") }, serviceScope)
        assertEquals(
            StartRefreshRunResult.NoRepositoriesConfigured,
            noRepositories.start(StartRefreshRunCommand(RefreshTarget.AllConfiguredRepositories)),
        )
        serviceScope.coroutineContext[kotlinx.coroutines.Job]!!.cancelAndJoin()
    }

    @Test
    fun `explicit targets deduplicate in request order and register every exact disposition without bypass`() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val serviceScope = kotlinx.coroutines.CoroutineScope(SupervisorJob() + testDispatcher)
        val state = RefreshState(
            configuration = configuration(
                listOf(
                    repository(repositoryA),
                    repository(repositoryB),
                    repository(repositoryRemoved, removedAt = now),
                ),
            ),
            checkpoints = mutableListOf(
                checkpoint(repositoryB, backoffUntil = now.plusSeconds(60)),
            ),
        )
        val release = CompletableDeferred<Unit>()
        val calls = mutableListOf<RepositoryId>()
        val delegate = RefreshRepository { command ->
            calls += command.repositoryId
            release.await()
            succeeded(command.repositoryId)
        }
        val services = service(state, delegate, serviceScope)

        val result = assertInstanceOf(
            StartRefreshRunResult.RefreshRunRegistered::class.java,
            services.start(
                StartRefreshRunCommand(
                    RefreshTarget.Repositories(
                        listOf(repositoryB, repositoryA, repositoryB, repositoryMissing, repositoryRemoved),
                    ),
                ),
            ),
        )

        assertEquals(
            listOf(repositoryB, repositoryA, repositoryMissing, repositoryRemoved),
            result.dispositions.map { it.repositoryId },
        )
        assertInstanceOf(RefreshRegistrationDisposition.DeferredByBackoff::class.java, result.dispositions[0])
        assertInstanceOf(RefreshRegistrationDisposition.Started::class.java, result.dispositions[1])
        assertInstanceOf(RefreshRegistrationDisposition.RepositoryNotConfigured::class.java, result.dispositions[2])
        assertInstanceOf(RefreshRegistrationDisposition.RepositoryNotConfigured::class.java, result.dispositions[3])
        assertEquals(
            listOf(repositoryB, repositoryA),
            result.refreshRun.repositories.map { it.repositoryId },
        )
        assertInstanceOf(RefreshRunRepositoryEntry.DeferredByBackoff::class.java, result.refreshRun.repositories[0])
        assertInstanceOf(RefreshRunRepositoryEntry.Queued::class.java, result.refreshRun.repositories[1])
        runCurrent()
        assertEquals(listOf(repositoryA), calls)
        release.complete(Unit)
        runCurrent()
        serviceScope.coroutineContext[kotlinx.coroutines.Job]!!.cancelAndJoin()
    }

    @Test
    fun `all target selects active configured repositories in stored order`() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val serviceScope = kotlinx.coroutines.CoroutineScope(SupervisorJob() + testDispatcher)
        val state = RefreshState(
            configuration = configuration(
                listOf(repository(repositoryB), repository(repositoryRemoved, removedAt = now), repository(repositoryA)),
            ),
        )
        val release = CompletableDeferred<Unit>()
        val services = service(state, RefreshRepository { release.await(); succeeded(it.repositoryId) }, serviceScope)

        val result = assertInstanceOf(
            StartRefreshRunResult.RefreshRunRegistered::class.java,
            services.start(StartRefreshRunCommand(RefreshTarget.AllConfiguredRepositories)),
        )

        assertEquals(listOf(repositoryB, repositoryA), result.dispositions.map { it.repositoryId })
        assertEquals(listOf(repositoryB, repositoryA), result.refreshRun.repositories.map { it.repositoryId })
        release.complete(Unit)
        runCurrent()
        serviceScope.coroutineContext[kotlinx.coroutines.Job]!!.cancelAndJoin()
    }

    @Test
    fun `entries move queued to running to every typed terminal result with polling only while active`() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val serviceScope = kotlinx.coroutines.CoroutineScope(SupervisorJob() + testDispatcher)
        val retryAt = now.plusSeconds(90)
        val failure = SynchronizationFailure(SynchronizationFailureCategory.NETWORK, true, retryAt)
        val partial = PartialFailureMetadata(2, 1, listOf(failure))
        val state = RefreshState(
            configuration = configuration(listOf(repository(repositoryA), repository(repositoryB), repository(repositoryC), repository(repositoryDeferred))),
            checkpoints = mutableListOf(checkpoint(repositoryDeferred, backoffUntil = retryAt)),
        )
        val gates = mapOf(
            repositoryA to CompletableDeferred<Unit>(),
            repositoryB to CompletableDeferred<Unit>(),
            repositoryC to CompletableDeferred<Unit>(),
        )
        val delegate = RefreshRepository { command ->
            gates.getValue(command.repositoryId).await()
            when (command.repositoryId) {
                repositoryA -> succeeded(repositoryA, now.plusSeconds(1))
                repositoryB -> partiallySucceeded(repositoryB, partial, now.plusSeconds(2))
                repositoryC -> failed(repositoryC, failure)
                else -> error("unexpected repository")
            }
        }
        val events = mutableListOf<OperationalEvent>()
        val services = service(
            state,
            delegate,
            serviceScope,
            recorder = OperationalEventRecorder(events::add),
            timeSource = MonotonicTimeSource { 0L },
        )

        val started = assertInstanceOf(
            StartRefreshRunResult.RefreshRunRegistered::class.java,
            services.start(StartRefreshRunCommand(RefreshTarget.AllConfiguredRepositories)),
        )
        assertEquals(4, started.refreshRun.repositories.size)
        assertEquals(3, started.refreshRun.repositories.count { it is RefreshRunRepositoryEntry.Queued })
        assertInstanceOf(RefreshRunRepositoryEntry.DeferredByBackoff::class.java, started.refreshRun.repositories.last())

        runCurrent()
        val running = assertInstanceOf(
            GetRefreshRunResult.RefreshRunInProgress::class.java,
            services.get(started.refreshRun.id),
        )
        assertEquals(125, running.polling.afterMilliseconds)
        assertEquals(3, running.refreshRun.repositories.count { it is RefreshRunRepositoryEntry.Running })
        assertInstanceOf(RefreshRunRepositoryEntry.DeferredByBackoff::class.java, running.refreshRun.repositories.last())

        gates.values.forEach { it.complete(Unit) }
        runCurrent()
        val completed = assertInstanceOf(
            GetRefreshRunResult.RefreshRunCompleted::class.java,
            services.get(started.refreshRun.id),
        ).refreshRun
        assertInstanceOf(RefreshRunRepositoryEntry.Succeeded::class.java, completed.repositories[0])
        assertEquals(now.plusSeconds(1), (completed.repositories[0] as RefreshRunRepositoryEntry.Succeeded).completedAt)
        assertInstanceOf(RefreshRunRepositoryEntry.PartiallySucceeded::class.java, completed.repositories[1])
        assertEquals(partial, (completed.repositories[1] as RefreshRunRepositoryEntry.PartiallySucceeded).partialFailure)
        assertInstanceOf(RefreshRunRepositoryEntry.Failed::class.java, completed.repositories[2])
        assertEquals(failure, (completed.repositories[2] as RefreshRunRepositoryEntry.Failed).failure)
        assertInstanceOf(RefreshRunRepositoryEntry.DeferredByBackoff::class.java, completed.repositories[3])
        val terminalEvents = events.filterIsInstance<OperationalEvent.RefreshRepositoryFinished>()
        assertEquals(4, terminalEvents.size)
        val order = listOf(repositoryA, repositoryB, repositoryC, repositoryDeferred)
        assertEquals(
            listOf(
                RefreshRepositoryOutcome.SUCCEEDED,
                RefreshRepositoryOutcome.PARTIAL,
                RefreshRepositoryOutcome.FAILED,
                RefreshRepositoryOutcome.DEFERRED,
            ),
            terminalEvents.sortedBy { order.indexOf(it.repositoryId) }.map { it.outcome },
        )
        terminalEvents.forEach { event ->
            assertEquals(started.refreshRun.id, event.refreshRunId)
            assertEquals(0L, event.durationMilliseconds)
        }
        val partialEvent = terminalEvents.single { it.repositoryId == repositoryB }
        assertEquals(SynchronizationFailureCategory.NETWORK, partialEvent.failureCategory)
        assertEquals(true, partialEvent.retryable)
        assertEquals(retryAt, partialEvent.retryAt)
        val failedEvent = terminalEvents.single { it.repositoryId == repositoryC }
        assertEquals(SynchronizationFailureCategory.NETWORK, failedEvent.failureCategory)
        assertEquals(true, failedEvent.retryable)
        assertEquals(retryAt, failedEvent.retryAt)
        assertEquals(retryAt, terminalEvents.single { it.repositoryId == repositoryDeferred }.retryAt)
        serviceScope.coroutineContext[kotlinx.coroutines.Job]!!.cancelAndJoin()
    }

    @Test
    fun `manual partial refresh summary is count based and category order is deterministic`() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val serviceScope = kotlinx.coroutines.CoroutineScope(SupervisorJob() + testDispatcher)
        val failures = listOf(
            SynchronizationFailure(SynchronizationFailureCategory.NETWORK, true, now.plusSeconds(30)),
            SynchronizationFailure(SynchronizationFailureCategory.AUTHENTICATION, false, null),
            SynchronizationFailure(SynchronizationFailureCategory.NETWORK, true, now.plusSeconds(30)),
        )
        val partial = PartialFailureMetadata(attemptedCount = 5, succeededCount = 2, failures = failures)
        val events = mutableListOf<OperationalEvent>()
        val services = service(
            RefreshState(configuration = configuration(listOf(repository(repositoryA)))),
            RefreshRepository { partiallySucceeded(repositoryA, partial, now) },
            serviceScope,
            recorder = OperationalEventRecorder(events::add),
        )

        services.start(StartRefreshRunCommand(RefreshTarget.AllConfiguredRepositories))
        runCurrent()

        val event = events.filterIsInstance<OperationalEvent.RefreshRepositoryFinished>().single()
        assertEquals(3, event.javaClass.getDeclaredField("failureCount").apply { isAccessible = true }.getInt(event))
        @Suppress("UNCHECKED_CAST")
        val categories = event.javaClass.getDeclaredField("failureCategories").apply { isAccessible = true }
            .get(event) as List<SynchronizationFailureCategory>
        assertEquals(
            listOf(SynchronizationFailureCategory.AUTHENTICATION, SynchronizationFailureCategory.NETWORK),
            categories,
        )
        serviceScope.coroutineContext[kotlinx.coroutines.Job]!!.cancelAndJoin()
    }

    @Test
    fun `started and joined registrations share one service-scope flight despite request cancellation`() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val serviceScope = kotlinx.coroutines.CoroutineScope(SupervisorJob() + testDispatcher)
        val state = RefreshState(configuration = configuration(listOf(repository(repositoryA))))
        val registryLockEntered = CompletableDeferred<Unit>()
        val releaseRegistryLock = CountDownLatch(1)
        val idCalls = AtomicInteger()
        val registry = InMemoryRefreshRunRegistry(
            Clock.fixed(now, ZoneOffset.UTC),
            Duration.ofMinutes(10),
            100,
            RefreshRunIdSource {
                if (idCalls.getAndIncrement() == 0) {
                    registryLockEntered.complete(Unit)
                    releaseRegistryLock.await()
                    RefreshRunId("rr_blocker")
                } else {
                    RefreshRunId("rr_shared")
                }
            },
        )
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val calls = AtomicInteger()
        val delegate = RefreshRepository {
            calls.incrementAndGet()
            entered.complete(Unit)
            release.await()
            succeeded(repositoryA)
        }
        val services = service(state, delegate, serviceScope, registry = registry)
        val registryBlocker = async(Dispatchers.Default) { registry.create(emptyList()) }
        registryLockEntered.await()

        val requestJob = launch {
            services.start(StartRefreshRunCommand(RefreshTarget.Repositories(listOf(repositoryA))))
        }
        try {
            runCurrent()
            entered.await()
            assertTrue(requestJob.isActive, "the request must still be suspended on registry creation")
            requestJob.cancelAndJoin()
            assertTrue(requestJob.isCancelled)
        } finally {
            releaseRegistryLock.countDown()
        }
        registryBlocker.await()

        val second = assertInstanceOf(
            StartRefreshRunResult.RefreshRunRegistered::class.java,
            services.start(StartRefreshRunCommand(RefreshTarget.Repositories(listOf(repositoryA)))),
        )
        assertInstanceOf(RefreshRegistrationDisposition.JoinedExisting::class.java, second.dispositions.single())
        runCurrent()
        assertEquals(1, calls.get())

        release.complete(Unit)
        runCurrent()
        assertInstanceOf(GetRefreshRunResult.RefreshRunCompleted::class.java, services.get(second.refreshRun.id))
        assertEquals(1, calls.get())
        serviceScope.coroutineContext[kotlinx.coroutines.Job]!!.cancelAndJoin()
    }

    @Test
    fun `expired and unknown runs are unavailable and late service completion cannot resurrect eviction`() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val serviceScope = kotlinx.coroutines.CoroutineScope(SupervisorJob() + testDispatcher)
        val clock = MutableRunClock(now)
        val state = RefreshState(configuration = configuration(listOf(repository(repositoryA), repository(repositoryB))))
        val gates = mutableMapOf(repositoryA to CompletableDeferred<Unit>(), repositoryB to CompletableDeferred<Unit>())
        val registry = InMemoryRefreshRunRegistry(
            clock,
            Duration.ofSeconds(10),
            1,
            RefreshRunIdSource.sequence(listOf(RefreshRunId("rr_first"), RefreshRunId("rr_second"))),
        )
        val services = service(
            state,
            RefreshRepository { command -> gates.getValue(command.repositoryId).await(); succeeded(command.repositoryId) },
            serviceScope,
            clock,
            registry,
        )

        val first = (services.start(StartRefreshRunCommand(RefreshTarget.Repositories(listOf(repositoryA)))) as StartRefreshRunResult.RefreshRunRegistered).refreshRun
        runCurrent()
        val second = (services.start(StartRefreshRunCommand(RefreshTarget.Repositories(listOf(repositoryB)))) as StartRefreshRunResult.RefreshRunRegistered).refreshRun
        runCurrent()
        assertEquals(GetRefreshRunResult.RefreshRunUnavailable(first.id), services.get(first.id))
        gates.getValue(repositoryA).complete(Unit)
        runCurrent()
        assertEquals(GetRefreshRunResult.RefreshRunUnavailable(first.id), services.get(first.id))

        clock.current = second.expiresAt
        assertEquals(GetRefreshRunResult.RefreshRunUnavailable(second.id), services.get(second.id))
        gates.getValue(repositoryB).complete(Unit)
        runCurrent()
        assertEquals(GetRefreshRunResult.RefreshRunUnavailable(second.id), services.get(second.id))
        assertEquals(
            GetRefreshRunResult.RefreshRunUnavailable(RefreshRunId("rr_unknown")),
            services.get(RefreshRunId("rr_unknown")),
        )
        serviceScope.coroutineContext[kotlinx.coroutines.Job]!!.cancelAndJoin()
    }

    @Test
    fun `unexpected coordinator failure becomes a privacy safe terminal repository failure`() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val observedFailures = mutableListOf<Throwable>()
        val handler = CoroutineExceptionHandler { _, failure -> observedFailures += failure }
        val serviceScope = kotlinx.coroutines.CoroutineScope(SupervisorJob() + testDispatcher + handler)
        val unsafeMessage = "upstream payload at secret path must not escape"
        val events = mutableListOf<OperationalEvent>()
        val services = service(
            RefreshState(configuration = configuration(listOf(repository(repositoryA)))),
            RefreshRepository { throw IllegalStateException(unsafeMessage) },
            serviceScope,
            recorder = OperationalEventRecorder(events::add),
            timeSource = MonotonicTimeSource { 0L },
        )

        val started = assertInstanceOf(
            StartRefreshRunResult.RefreshRunRegistered::class.java,
            services.start(StartRefreshRunCommand(RefreshTarget.Repositories(listOf(repositoryA)))),
        )
        runCurrent()

        val completed = assertInstanceOf(
            GetRefreshRunResult.RefreshRunCompleted::class.java,
            services.get(started.refreshRun.id),
        )
        val failed = assertInstanceOf(
            RefreshRunRepositoryEntry.Failed::class.java,
            completed.refreshRun.repositories.single(),
        )
        assertEquals(repositoryA, failed.repositoryId)
        assertEquals(now, failed.completedAt)
        assertEquals(
            SynchronizationFailure(SynchronizationFailureCategory.UPSTREAM, retryable = false, retryAt = null),
            failed.failure,
        )
        assertTrue(observedFailures.isEmpty(), "unexpected coordinator failures must not escape the monitor")
        assertFalse(completed.toString().contains(unsafeMessage))
        assertFalse(completed.toString().contains("IllegalStateException"))
        val terminalEvents = events.filterIsInstance<OperationalEvent.RefreshRepositoryFinished>()
        assertEquals(1, terminalEvents.size)
        assertEquals(started.refreshRun.id, terminalEvents.single().refreshRunId)
        assertEquals(repositoryA, terminalEvents.single().repositoryId)
        assertEquals(RefreshRepositoryOutcome.UNEXPECTED, terminalEvents.single().outcome)
        assertEquals(0L, terminalEvents.single().durationMilliseconds)
        assertFalse(terminalEvents.single().toString().contains(unsafeMessage))
        serviceScope.coroutineContext[kotlinx.coroutines.Job]!!.cancelAndJoin()
    }

    @Test
    fun `service scope cancellation before monitor dispatch cannot leave a queued run actively pollable`() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val serviceJob = SupervisorJob()
        val serviceScope = kotlinx.coroutines.CoroutineScope(serviceJob + testDispatcher)
        val neverComplete = CompletableDeferred<Unit>()
        val services = service(
            RefreshState(configuration = configuration(listOf(repository(repositoryA)))),
            RefreshRepository {
                neverComplete.await()
                succeeded(repositoryA)
            },
            serviceScope,
        )

        val started = assertInstanceOf(
            StartRefreshRunResult.RefreshRunRegistered::class.java,
            services.start(StartRefreshRunCommand(RefreshTarget.Repositories(listOf(repositoryA)))),
        )
        assertInstanceOf(RefreshRunRepositoryEntry.Queued::class.java, started.refreshRun.repositories.single())

        serviceJob.cancel()
        runCurrent()
        serviceJob.join()

        val completed = assertInstanceOf(
            GetRefreshRunResult.RefreshRunCompleted::class.java,
            services.get(started.refreshRun.id),
        )
        assertTrue(completed.refreshRun.repositories.isEmpty())
    }

    @Test
    fun `cancelled coordinator flight stops active polling`() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val serviceScope = kotlinx.coroutines.CoroutineScope(SupervisorJob() + testDispatcher)
        val events = mutableListOf<OperationalEvent>()
        val services = service(
            RefreshState(configuration = configuration(listOf(repository(repositoryA)))),
            RefreshRepository { throw CancellationException("refresh flight cancelled") },
            serviceScope,
            recorder = OperationalEventRecorder(events::add),
            timeSource = MonotonicTimeSource { 0L },
        )

        val started = assertInstanceOf(
            StartRefreshRunResult.RefreshRunRegistered::class.java,
            services.start(StartRefreshRunCommand(RefreshTarget.Repositories(listOf(repositoryA)))),
        )
        runCurrent()

        val completed = assertInstanceOf(
            GetRefreshRunResult.RefreshRunCompleted::class.java,
            services.get(started.refreshRun.id),
        )
        assertTrue(completed.refreshRun.repositories.isEmpty())
        assertTrue(events.filterIsInstance<OperationalEvent.RefreshRepositoryFinished>().isEmpty())
        serviceScope.coroutineContext[kotlinx.coroutines.Job]!!.cancelAndJoin()
    }

    @Test
    fun `throwing operational recorder cannot alter terminal refresh state`() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val serviceScope = kotlinx.coroutines.CoroutineScope(SupervisorJob() + testDispatcher)
        val recorder = OperationalEventRecorder { throw AssertionError("recorder failed") }
        val services = service(
            RefreshState(configuration = configuration(listOf(repository(repositoryA)))),
            RefreshRepository { succeeded(repositoryA) },
            serviceScope,
            recorder = recorder,
            timeSource = MonotonicTimeSource { 0L },
        )

        val started = assertInstanceOf(
            StartRefreshRunResult.RefreshRunRegistered::class.java,
            services.start(StartRefreshRunCommand(RefreshTarget.Repositories(listOf(repositoryA)))),
        )
        runCurrent()

        assertInstanceOf(GetRefreshRunResult.RefreshRunCompleted::class.java, services.get(started.refreshRun.id))
        serviceScope.coroutineContext[kotlinx.coroutines.Job]!!.cancelAndJoin()
    }

    @Test
    fun `capacity one concurrent starts both return even when the first run is immediately evicted`() = runTest {
        val firstIdEntered = CompletableDeferred<Unit>()
        val releaseFirstId = CountDownLatch(1)
        val idCalls = AtomicInteger()
        val registry = InMemoryRefreshRunRegistry(
            Clock.fixed(now, ZoneOffset.UTC),
            Duration.ofMinutes(10),
            1,
            RefreshRunIdSource {
                when (idCalls.getAndIncrement()) {
                    0 -> {
                        firstIdEntered.complete(Unit)
                        releaseFirstId.await()
                        RefreshRunId("rr_first")
                    }
                    else -> RefreshRunId("rr_second")
                }
            },
        )
        val serviceScope = kotlinx.coroutines.CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val repositoryBFlightEntered = CompletableDeferred<Unit>()
        val neverComplete = CompletableDeferred<Unit>()
        val services = service(
            RefreshState(configuration = configuration(listOf(repository(repositoryA), repository(repositoryB)))),
            RefreshRepository { command ->
                if (command.repositoryId == repositoryB) repositoryBFlightEntered.complete(Unit)
                neverComplete.await()
                succeeded(command.repositoryId)
            },
            serviceScope,
            registry = registry,
        )

        val first = async(Dispatchers.Default) {
            runCatching {
                services.start(StartRefreshRunCommand(RefreshTarget.Repositories(listOf(repositoryA))))
            }
        }
        firstIdEntered.await()
        val second = async(Dispatchers.Default) {
            runCatching {
                services.start(StartRefreshRunCommand(RefreshTarget.Repositories(listOf(repositoryB))))
            }
        }
        repositoryBFlightEntered.await()
        releaseFirstId.countDown()

        val results = listOf(first.await(), second.await())
        assertTrue(results.all { it.isSuccess }, results.map { it.exceptionOrNull() }.toString())
        assertEquals(
            setOf(RefreshRunId("rr_first"), RefreshRunId("rr_second")),
            results.mapTo(mutableSetOf()) {
                (it.getOrThrow() as StartRefreshRunResult.RefreshRunRegistered).refreshRun.id
            },
        )
        serviceScope.coroutineContext[kotlinx.coroutines.Job]!!.cancelAndJoin()
    }

    private fun service(
        state: RefreshState,
        delegate: RefreshRepository,
        serviceScope: kotlinx.coroutines.CoroutineScope,
        clock: Clock = Clock.fixed(now, ZoneOffset.UTC),
        registry: InMemoryRefreshRunRegistry = InMemoryRefreshRunRegistry(
            clock,
            Duration.ofMinutes(10),
            100,
            RefreshRunIdSource.sequence(listOf(
                RefreshRunId("rr_one"),
                RefreshRunId("rr_two"),
                RefreshRunId("rr_three"),
                RefreshRunId("rr_four"),
            )),
        ),
        recorder: OperationalEventRecorder = OperationalEventRecorder.NONE,
        timeSource: MonotonicTimeSource = MonotonicTimeSource.SYSTEM,
    ): RefreshRunServices {
        val transactions = RefreshTransactions(state)
        val coordinator = RepositoryRefreshCoordinator(
            transactions,
            delegate,
            serviceScope,
            clock,
            SynchronizationBackoff(delays = listOf(Duration.ofSeconds(10)), maximumDelay = Duration.ofMinutes(1)),
        )
        return RefreshRunServices(
            transactions,
            coordinator,
            registry,
            serviceScope,
            ActivePollingAdvice(125),
            clock,
            recorder,
            timeSource,
        )
    }

    private fun configuration(repositories: List<StoredConfiguredRepository>) = StoredInstallationConfiguration(
        WorkspaceId("ws_team"),
        URI("https://api.bitbucket.org/2.0"),
        "team",
        "Team",
        URI("https://bitbucket.org/team"),
        "current-user",
        "Current User",
        now.minusSeconds(3_600),
        30,
        repositories,
    )

    private fun repository(id: RepositoryId, removedAt: Instant? = null) = StoredConfiguredRepository(
        id,
        WorkspaceId("ws_team"),
        id.value,
        id.value,
        URI("https://bitbucket.org/team/${id.value}"),
        removedAt,
    )

    private fun checkpoint(repositoryId: RepositoryId, backoffUntil: Instant? = null) = StoredSynchronizationSnapshot(
        repositoryId,
        SynchronizationActivity.IDLE,
        now.minusSeconds(10),
        SynchronizationAttemptOutcome.SUCCEEDED,
        now.minusSeconds(10),
        now.minusSeconds(10),
        SynchronizationProblem.None,
        0,
        backoffUntil,
        null,
        null,
    )

    private fun succeeded(repositoryId: RepositoryId, completedAt: Instant = now) = RefreshRepositoryResult.Succeeded(
        repositoryId,
        completedAt,
        synchronization(repositoryId),
    )

    private fun partiallySucceeded(
        repositoryId: RepositoryId,
        partial: PartialFailureMetadata,
        completedAt: Instant,
    ) = RefreshRepositoryResult.PartiallySucceeded(repositoryId, completedAt, partial, synchronization(repositoryId))

    private fun failed(repositoryId: RepositoryId, failure: SynchronizationFailure) =
        RefreshRepositoryResult.Failed(repositoryId, failure, synchronization(repositoryId))

    private fun synchronization(repositoryId: RepositoryId) = SynchronizationProjection(
        repositoryId,
        SynchronizationActivity.IDLE,
        now,
        SynchronizationAttemptOutcome.SUCCEEDED,
        now,
        Freshness.Fresh(now, Duration.ZERO),
        SynchronizationProblem.None,
    )

    private fun <T> tryToClear(values: List<T>) {
        val mutable = values as? MutableList<T> ?: return
        try {
            mutable.clear()
        } catch (_: UnsupportedOperationException) {
            // Either an immutable or an unmodifiable list proves the contract.
        }
    }

    private companion object {
        val repositoryA = RepositoryId("repo_alpha")
        val repositoryB = RepositoryId("repo_beta")
        val repositoryC = RepositoryId("repo_charlie")
        val repositoryDeferred = RepositoryId("repo_deferred")
        val repositoryMissing = RepositoryId("repo_missing")
        val repositoryRemoved = RepositoryId("repo_removed")
    }
}

private data class RefreshState(
    var configuration: StoredInstallationConfiguration? = null,
    val checkpoints: MutableList<StoredSynchronizationSnapshot> = mutableListOf(),
)

private class RefreshTransactions(private val state: RefreshState) : ApplicationTransactionRunner {
    private val mutex = Mutex()

    override suspend fun <T> inTransaction(block: suspend ApplicationTransaction.() -> T): T =
        mutex.withLock { block(RefreshTransaction(state)) }
}

private class RefreshTransaction(private val state: RefreshState) : ApplicationTransaction {
    override val configurationStore = object : ConfigurationStore {
        override suspend fun find() = state.configuration
        override suspend fun save(configuration: StoredInstallationConfiguration) { state.configuration = configuration }
    }
    override val synchronizationCheckpointStore = object : SynchronizationCheckpointStore {
        override suspend fun find(repositoryId: RepositoryId) = state.checkpoints.singleOrNull { it.repositoryId == repositoryId }
        override suspend fun list() = state.checkpoints.toList()
        override suspend fun save(snapshot: StoredSynchronizationSnapshot) {
            state.checkpoints.removeIf { it.repositoryId == snapshot.repositoryId }
            state.checkpoints += snapshot
        }
    }
    override val pullRequestStore: PullRequestStore get() = error("unused")
    override val actionItemStore: ActionItemStore get() = error("unused")
    override val notificationIntentStore: NotificationIntentStore get() = error("unused")
    override val reminderProjectionStore: ReminderProjectionStore get() = error("unused")
}

private class MutableRunClock(var current: Instant) : Clock() {
    override fun getZone(): ZoneId = ZoneOffset.UTC
    override fun withZone(zone: ZoneId): Clock = this
    override fun instant(): Instant = current
}

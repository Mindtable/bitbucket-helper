package com.mindtable.bitbuckethelper.bootstrap

import com.mindtable.bitbuckethelper.adapter.inbound.scheduler.QuartzApplicationScheduler
import com.mindtable.bitbuckethelper.adapter.inbound.scheduler.ScheduledUseCases
import com.mindtable.bitbuckethelper.adapter.inbound.scheduler.SchedulerLifecycleSeams
import com.mindtable.bitbuckethelper.application.model.NotificationDispatchSummary
import com.mindtable.bitbuckethelper.application.model.PruneInactivePullRequestsResult
import com.mindtable.bitbuckethelper.application.model.RefreshAllRepositoriesResult
import com.mindtable.bitbuckethelper.application.port.inbound.PruneInactivePullRequests
import com.mindtable.bitbuckethelper.application.port.inbound.RefreshAllRepositories
import com.mindtable.bitbuckethelper.application.port.inbound.RetryPendingNotifications
import com.mindtable.bitbuckethelper.application.port.inbound.SendDueReminders
import com.mindtable.bitbuckethelper.observability.BackendEventRecorder
import com.mindtable.bitbuckethelper.observability.BackendLogEvent
import java.lang.management.ManagementFactory
import java.nio.file.Path
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir

class ServiceRuntimeLifecycleTest {
    @Test
    fun `real Quartz registration failure is recorded once by ServiceRuntime`() {
        val events = mutableListOf<BackendLogEvent>()
        val failure = IllegalStateException("registration-token-private")
        val quartz = scheduler(
            events,
            SchedulerLifecycleSeams(registrationHook = { throw failure }),
        )
        val runtime = runtimeUsing(quartz, events)

        try {
            assertThrowsIllegalState { runtime.start() }
            assertSingleSchedulerComponentFailure(events, "service.start.failed")
            assertFalse(events.single { it is BackendLogEvent.ServiceStartFailed }.toString().contains("registration-token-private"))
        } finally {
            runtime.close()
        }
    }

    @Test
    fun `real Quartz start failure is recorded once by ServiceRuntime`() {
        val events = mutableListOf<BackendLogEvent>()
        val failure = IllegalStateException("start-token-private")
        val quartz = scheduler(
            events,
            SchedulerLifecycleSeams(startAction = { throw failure }),
        )
        val runtime = runtimeUsing(quartz, events)

        try {
            assertThrowsIllegalState { runtime.start() }
            assertSingleSchedulerComponentFailure(events, "service.start.failed")
            assertFalse(events.single { it is BackendLogEvent.ServiceStartFailed }.toString().contains("start-token-private"))
        } finally {
            runtime.close()
        }
    }

    @Test
    fun `real Quartz shutdown failure is recorded once by ServiceRuntime`() {
        val events = mutableListOf<BackendLogEvent>()
        val failure = IllegalStateException("shutdown-token-private")
        val quartz = scheduler(
            events,
            SchedulerLifecycleSeams(
                startAction = {},
                shutdownAction = { _, _ -> throw failure },
            ),
        )
        val runtime = runtimeUsing(quartz, events)

        runtime.start()
        val observed = runCatching { runtime.close() }.exceptionOrNull()
        assertEquals("Quartz application scheduler shutdown failed", observed?.message)
        assertFalse(observed?.stackTraceToString()?.contains("shutdown-token-private") == true)
        assertSingleSchedulerComponentFailure(events, "service.stop.failed")
        assertFalse(events.single { it is BackendLogEvent.ServiceStopFailed }.toString().contains("shutdown-token-private"))
    }

    @Test
    fun `startup and cleanup failures identify components while preserving primary and suppressed failures`() {
        val events = mutableListOf<BackendLogEvent>()
        val schedulerStartFailure = IllegalStateException("scheduler-start-failure")
        val schedulerStopFailure = IllegalStateException("scheduler-stop-failure")
        val scopeStopFailure = IllegalStateException("scope-stop-failure")
        val runtime = ServiceRuntime.createForLifecycleTest(
            RuntimeLifecycleActions(
                startScheduler = { throw schedulerStartFailure },
                startServers = { 18080 },
                stopServers = {},
                stopScheduler = { throw schedulerStopFailure },
                cancelAndJoinScope = { throw scopeStopFailure },
                closeGateway = {},
                closePersistence = {},
            ),
            BackendEventRecorder(events::add),
        )

        val failure = org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException::class.java) {
            runtime.start()
        }

        assertSame(schedulerStartFailure, failure)
        assertEquals(
            listOf("service.start.failed", "service.stop.failed", "service.stop.failed"),
            events.map(BackendLogEvent::eventName),
        )
        assertEquals("scheduler", (events[0] as BackendLogEvent.ServiceStartFailed).component)
        assertEquals(
            listOf("scheduler", "service_scope"),
            events.drop(1).map { (it as BackendLogEvent.ServiceStopFailed).component },
        )
        assertEquals(listOf(schedulerStopFailure, scopeStopFailure), failure.suppressed.toList())
    }

    @Test
    fun `close records every cleanup component and keeps reverse cleanup failure order`() {
        val events = mutableListOf<BackendLogEvent>()
        val httpFailure = IllegalStateException("http-stop-failure")
        val schedulerFailure = IllegalStateException("scheduler-stop-failure")
        val scopeFailure = IllegalStateException("scope-stop-failure")
        val gatewayFailure = IllegalStateException("gateway-stop-failure")
        val persistenceFailure = IllegalStateException("persistence-stop-failure")
        val runtime = ServiceRuntime.createForLifecycleTest(
            RuntimeLifecycleActions(
                startScheduler = {},
                startServers = { 18080 },
                stopServers = { throw httpFailure },
                stopScheduler = { throw schedulerFailure },
                cancelAndJoinScope = { throw scopeFailure },
                closeGateway = { throw gatewayFailure },
                closePersistence = { throw persistenceFailure },
            ),
            BackendEventRecorder(events::add),
        )

        runtime.start()
        val failure = org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException::class.java) {
            runtime.close()
        }

        assertSame(httpFailure, failure)
        assertEquals(
            listOf("http_servers", "scheduler", "service_scope", "bitbucket_gateway", "persistence"),
            events.map { (it as BackendLogEvent.ServiceStopFailed).component },
        )
        assertEquals(
            listOf(schedulerFailure, scopeFailure, gatewayFailure, persistenceFailure),
            failure.suppressed.toList(),
        )
    }

    @Test
    fun `close before start releases every constructed resource without starting a server`() {
        val events = mutableListOf<String>()
        val runtime = ServiceRuntime.createForLifecycleTest(
            RuntimeLifecycleActions(
                startScheduler = { events += "scheduler started" },
                startServers = { events += "servers started"; 18080 },
                stopServers = { events += "servers stopped" },
                stopScheduler = { events += "scheduler stopped" },
                cancelAndJoinScope = { events += "scope joined" },
                closeGateway = { events += "gateway closed" },
                closePersistence = { events += "persistence closed" },
            ),
        )

        runtime.close()

        assertTrue(
            events == listOf(
                "scheduler stopped",
                "scope joined",
                "gateway closed",
                "persistence closed",
            ),
            "unexpected lifecycle order: $events",
        )
    }

    @Test
    fun `shutdown is reverse ordered and idempotent`() {
        val events = mutableListOf<String>()
        val runtime = ServiceRuntime.createForLifecycleTest(
            RuntimeLifecycleActions(
                startScheduler = { events += "scheduler started" },
                startServers = { events += "servers started"; 18080 },
                stopServers = { events += "servers stopped" },
                stopScheduler = { events += "scheduler stopped" },
                cancelAndJoinScope = { events += "scope joined" },
                closeGateway = { events += "gateway closed" },
                closePersistence = { events += "persistence closed" },
            ),
        )

        runtime.start()
        runtime.close()
        runtime.close()

        assertTrue(
            events == listOf(
                "scheduler started",
                "servers started",
                "servers stopped",
                "scheduler stopped",
                "scope joined",
                "gateway closed",
                "persistence closed",
            ),
            "unexpected lifecycle order: $events",
        )
    }

    @Test
    @Timeout(value = 20, unit = TimeUnit.SECONDS)
    fun `close before start never resolves or binds the HTTP connector`(
        @TempDir directory: Path,
    ) = runBlocking {
        val runtime = ServiceRuntime.create(configuration(directory.resolve("closed.sqlite")))

        try {
            runtime.close()
            val portResolution = async(start = CoroutineStart.UNDISPATCHED) {
                runtime.resolvedHttpPort()
            }
            try {
                assertFalse(
                    portResolution.isCompleted,
                    "closing an unstarted runtime must not start the lazy CIO server job",
                )
            } finally {
                portResolution.cancelAndJoin()
            }
        } finally {
            runtime.close()
        }
    }

    @Test
    @Timeout(value = 20, unit = TimeUnit.SECONDS)
    fun `close waits for the complete start transition`(
        @TempDir directory: Path,
    ) {
        val startTransitionEntered = CountDownLatch(1)
        val releaseStartTransition = CountDownLatch(1)
        val runtime = ServiceRuntime.create(
            configuration = configuration(directory.resolve("interleaving.sqlite")),
            clock = FIXED_CLOCK,
            lifecycleProbe = ServiceRuntimeLifecycleProbe {
                startTransitionEntered.countDown()
                check(releaseStartTransition.await(5, TimeUnit.SECONDS)) {
                    "Timed out waiting to release the start transition"
                }
            },
        )
        val startFailure = AtomicReference<Throwable?>()
        val closeFailure = AtomicReference<Throwable?>()
        val startCompleted = CountDownLatch(1)
        val closeCompleted = CountDownLatch(1)
        val startThread = Thread(
            {
                try {
                    runtime.start()
                } catch (failure: Throwable) {
                    startFailure.set(failure)
                } finally {
                    startCompleted.countDown()
                }
            },
            "service-runtime-start-test",
        )
        val closeThread = Thread(
            {
                try {
                    runtime.close()
                } catch (failure: Throwable) {
                    closeFailure.set(failure)
                } finally {
                    closeCompleted.countDown()
                }
            },
            "service-runtime-close-test",
        )

        try {
            startThread.start()
            assertTrue(startTransitionEntered.await(5, TimeUnit.SECONDS))
            closeThread.start()

            eventuallyWithin(Duration.ofSeconds(2)) {
                ManagementFactory.getThreadMXBean()
                    .getThreadInfo(closeThread.threadId())
                    ?.takeIf {
                        it.threadState == Thread.State.BLOCKED &&
                            it.lockOwnerId == startThread.threadId()
                    }
            }

            releaseStartTransition.countDown()
            assertTrue(startCompleted.await(5, TimeUnit.SECONDS))
            assertTrue(closeCompleted.await(5, TimeUnit.SECONDS))
            assertNull(startFailure.get())
            assertNull(closeFailure.get())
        } finally {
            releaseStartTransition.countDown()
            startThread.join(5_000)
            closeThread.join(5_000)
            runtime.close()
        }
    }

    private fun configuration(databasePath: Path) = ServiceConfiguration(
        httpHost = "127.0.0.1",
        httpPort = 0,
        databasePath = databasePath,
        unixSocketPath = databasePath.resolveSibling("service.sock"),
        notificationExecutablePath = Path.of("/usr/bin/true"),
        bitbucketRequestTimeout = Duration.ofMillis(100),
        credentials = BitbucketCredentials("person@example.com", "test-token"),
    )

    private fun scheduler(
        events: MutableList<BackendLogEvent>,
        seams: SchedulerLifecycleSeams,
    ) = QuartzApplicationScheduler.create(
        scheduledUseCases = ScheduledUseCases(
            refreshAllRepositories = RefreshAllRepositories { RefreshAllRepositoriesResult(emptyList()) },
            retryPendingNotifications = RetryPendingNotifications { emptyDispatchSummary() },
            sendDueReminders = SendDueReminders { emptyList() },
            pruneInactivePullRequests = PruneInactivePullRequests {
                PruneInactivePullRequestsResult(0, Instant.EPOCH)
            },
        ),
        jobTimeout = Duration.ofSeconds(1),
        clock = FIXED_CLOCK,
        seams = seams,
        recorder = BackendEventRecorder(events::add),
    )

    private fun runtimeUsing(
        scheduler: QuartzApplicationScheduler,
        events: MutableList<BackendLogEvent>,
    ) = ServiceRuntime.createForLifecycleTest(
        RuntimeLifecycleActions(
            startScheduler = scheduler::start,
            startServers = { 18080 },
            stopServers = {},
            stopScheduler = scheduler::close,
            cancelAndJoinScope = {},
            closeGateway = {},
            closePersistence = {},
        ),
        BackendEventRecorder(events::add),
    )

    private fun assertSingleSchedulerComponentFailure(
        events: List<BackendLogEvent>,
        eventName: String,
    ) {
        assertEquals(1, events.count { it.eventName == eventName })
        assertEquals("scheduler", events.filter { it.eventName == eventName }
            .map { event ->
                when (event) {
                    is BackendLogEvent.ServiceStartFailed -> event.component
                    is BackendLogEvent.ServiceStopFailed -> event.component
                    else -> error("unexpected scheduler failure event")
                }
            }.single())
    }

    private fun assertThrowsIllegalState(action: () -> Unit) {
        val failure = runCatching(action).exceptionOrNull()
        assertTrue(failure is IllegalStateException)
    }

    private fun emptyDispatchSummary() = NotificationDispatchSummary(
        attemptedIntentIds = emptyList(),
        acceptedCount = 0,
        retryScheduledCount = 0,
        exhaustedCount = 0,
    )

    private fun <T : Any> eventuallyWithin(timeout: Duration, condition: () -> T?): T {
        val deadline = System.nanoTime() + timeout.toNanos()
        while (true) {
            condition()?.let { return it }
            if (System.nanoTime() >= deadline) {
                throw AssertionError("Condition was not satisfied before the monotonic deadline")
            }
            Thread.yield()
        }
    }

    private companion object {
        val FIXED_CLOCK: Clock = Clock.fixed(
            Instant.parse("2026-08-15T10:15:30Z"),
            ZoneOffset.UTC,
        )
    }
}

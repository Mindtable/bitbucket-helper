package com.mindtable.bitbuckethelper.adapter.inbound.scheduler

import com.mindtable.bitbuckethelper.application.model.HealthStatus
import com.mindtable.bitbuckethelper.application.model.NotificationDispatchSummary
import com.mindtable.bitbuckethelper.application.model.PruneInactivePullRequestsResult
import com.mindtable.bitbuckethelper.application.model.RefreshAllRepositoriesResult
import com.mindtable.bitbuckethelper.application.port.inbound.PruneInactivePullRequests
import com.mindtable.bitbuckethelper.application.port.inbound.RefreshAllRepositories
import com.mindtable.bitbuckethelper.application.port.inbound.RetryPendingNotifications
import com.mindtable.bitbuckethelper.application.port.inbound.SendDueReminders
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.lang.reflect.Proxy
import java.nio.charset.StandardCharsets
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.Date
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.quartz.CronTrigger
import org.quartz.DisallowConcurrentExecution
import org.quartz.JobExecutionContext
import org.quartz.JobExecutionException
import org.quartz.SimpleTrigger

class QuartzApplicationSchedulerTest {
    @Test
    fun `registers the four stable maintenance schedules before start`() {
        val now = Instant.parse("2026-08-15T12:34:56Z")
        val scheduler = scheduler(clock = Clock.fixed(now, ZoneOffset.UTC))

        try {
            assertEquals(STABLE_JOB_KEYS, scheduler.scheduledJobKeyNames())
            assertEquals(HealthStatus.HEALTHY, scheduler.health().status)
            assertEquals(STOPPED_CODE, scheduler.health().safeCode)

            assertSimpleTrigger(
                trigger = scheduler.scheduledTrigger(REFRESH_KEY),
                expectedStart = now,
                expectedInterval = Duration.ofMinutes(5),
            )
            assertSimpleTrigger(
                trigger = scheduler.scheduledTrigger(RETRY_KEY),
                expectedStart = now.plus(Duration.ofMinutes(1)),
                expectedInterval = Duration.ofMinutes(1),
            )
            assertSimpleTrigger(
                trigger = scheduler.scheduledTrigger(REMINDERS_KEY),
                expectedStart = Instant.parse("2026-08-15T13:00:00Z"),
                expectedInterval = Duration.ofHours(1),
            )

            val prune = scheduler.scheduledTrigger(PRUNE_KEY) as CronTrigger
            assertEquals("0 0 3 * * ?", prune.cronExpression)
            assertEquals("UTC", prune.timeZone.id)
            assertEquals(CronTrigger.MISFIRE_INSTRUCTION_DO_NOTHING, prune.misfireInstruction)
        } finally {
            scheduler.close()
        }
    }

    @Test
    fun `uses an isolated four-thread in-memory scheduler identity`() {
        val first = scheduler()
        val second = scheduler()

        try {
            val firstMetadata = first.schedulerMetadata()
            val secondMetadata = second.schedulerMetadata()

            assertEquals(4, firstMetadata.threadPoolSize)
            assertEquals("org.quartz.simpl.RAMJobStore", firstMetadata.jobStoreClass.name)
            assertTrue(firstMetadata.schedulerName.startsWith("bitbucket-helper-application-"))
            assertTrue(secondMetadata.schedulerName.startsWith("bitbucket-helper-application-"))
            assertNotEquals(firstMetadata.schedulerName, secondMetadata.schedulerName)
        } finally {
            first.close()
            second.close()
        }
    }

    @Test
    fun `generic job forbids self concurrency and awaits its suspending operation`() {
        assertTrue(
            SuspendingUseCaseJob::class.java.isAnnotationPresent(DisallowConcurrentExecution::class.java),
        )
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val job = SuspendingUseCaseJob(
            operation = {
                entered.complete(Unit)
                release.await()
            },
            timeout = Duration.ofSeconds(2),
        )
        val executor = Executors.newSingleThreadExecutor()

        try {
            val execution = executor.submit<Unit> { job.execute(UNUSED_CONTEXT) }
            runBlocking { entered.await() }
            assertFalse(execution.isDone)
            release.complete(Unit)
            execution.get(TEST_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        } finally {
            release.complete(Unit)
            executor.shutdownNow()
        }
    }

    @Test
    fun `generic job cancels on its injected timeout and reports only a safe non-refiring failure`() {
        val cancelled = CompletableDeferred<Unit>()
        val job = SuspendingUseCaseJob(
            operation = {
                try {
                    CompletableDeferred<Unit>().await()
                } catch (failure: CancellationException) {
                    cancelled.complete(Unit)
                    throw failure
                }
            },
            timeout = Duration.ofMillis(25),
        )

        val captured = executeCapturingStandardError(job)

        runBlocking { cancelled.await() }
        assertSanitizedJobFailure(captured)
    }

    @Test
    fun `generic job sanitizes ordinary failures but does not translate Errors`() {
        val ordinary = SuspendingUseCaseJob(
            operation = { error(RAW_FAILURE) },
            timeout = Duration.ofSeconds(1),
        )
        assertSanitizedJobFailure(executeCapturingStandardError(ordinary))

        val fatal = AssertionError(RAW_FAILURE)
        val errorJob = SuspendingUseCaseJob(
            operation = { throw fatal },
            timeout = Duration.ofSeconds(1),
        )
        val capturedError = executeCapturingStandardError(errorJob)
        assertTrue(capturedError.failure is AssertionError)
        assertFalse(capturedError.failure is JobExecutionException)
        assertEquals(RAW_FAILURE, capturedError.failure?.message)
        assertTrue(capturedError.standardError.isEmpty())
    }

    @Test
    fun `stable use-case keys invoke only their matching application use cases`() = runBlocking {
        val calls = mutableListOf<String>()
        val useCases = useCases(calls)

        STABLE_JOB_KEYS.forEach { key -> useCases.operation(key).invoke() }

        assertEquals(STABLE_JOB_KEYS.toList(), calls)
        assertThrows(IllegalArgumentException::class.java) {
            useCases.operation("private-$RAW_FAILURE")
        }.also { failure ->
            assertFalse(failure.message.orEmpty().contains(RAW_FAILURE))
        }
    }

    @Test
    fun `start and close are idempotent and shutdown waits for an active job`() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val scheduler = scheduler(
            refresh = RefreshAllRepositories {
                entered.countDown()
                release.await(TEST_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                RefreshAllRepositoriesResult(emptyList())
            },
            timeout = Duration.ofSeconds(5),
        )
        lateinit var closeFuture: CompletableFuture<Void>

        try {
            scheduler.start()
            scheduler.start()
            assertTrue(entered.await(TEST_TIMEOUT_SECONDS, TimeUnit.SECONDS))
            closeFuture = CompletableFuture.runAsync { scheduler.close() }
            Thread.sleep(50)
            assertFalse(closeFuture.isDone)
            release.countDown()
            closeFuture.get(TEST_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            scheduler.close()
            assertEquals(STOPPED_CODE, scheduler.health().safeCode)
            assertThrows(IllegalStateException::class.java) { scheduler.start() }
        } finally {
            release.countDown()
            scheduler.close()
        }
    }

    @Test
    fun `health safely distinguishes running stopped and failed registration`() {
        val scheduler = scheduler()
        try {
            assertEquals(STOPPED_CODE, scheduler.health().safeCode)
            scheduler.start()
            assertEquals(RUNNING_CODE, scheduler.health().safeCode)
        } finally {
            scheduler.close()
        }
        assertEquals(STOPPED_CODE, scheduler.health().safeCode)

        val failed = scheduler(registrationHook = { throw IllegalStateException(RAW_FAILURE) })
        try {
            val health = failed.health()
            assertEquals(HealthStatus.UNHEALTHY, health.status)
            assertEquals(REGISTRATION_FAILED_CODE, health.safeCode)
            assertFalse(health.toString().contains(RAW_FAILURE))
            val startFailure = assertThrows(IllegalStateException::class.java) { failed.start() }
            assertFalse(startFailure.stackTraceToString().contains(RAW_FAILURE))
        } finally {
            failed.close()
        }
    }

    @Test
    fun `sub-millisecond and non-positive timeouts are rejected before scheduler creation`() {
        listOf(Duration.ZERO, Duration.ofNanos(1), Duration.ofMillis(-1)).forEach { invalid ->
            assertThrows(IllegalArgumentException::class.java) { scheduler(timeout = invalid) }
        }
    }

    private fun assertSimpleTrigger(
        trigger: org.quartz.Trigger,
        expectedStart: Instant,
        expectedInterval: Duration,
    ) {
        trigger as SimpleTrigger
        assertEquals(Date.from(expectedStart), trigger.startTime)
        assertEquals(expectedInterval.toMillis(), trigger.repeatInterval)
        assertEquals(SimpleTrigger.REPEAT_INDEFINITELY, trigger.repeatCount)
        assertEquals(
            SimpleTrigger.MISFIRE_INSTRUCTION_RESCHEDULE_NEXT_WITH_REMAINING_COUNT,
            trigger.misfireInstruction,
        )
    }

    private fun assertSanitizedJobFailure(captured: CapturedExecution) {
        assertTrue(captured.failure is JobExecutionException)
        val failure = captured.failure as JobExecutionException
        assertEquals(SANITIZED_JOB_FAILURE + System.lineSeparator(), captured.standardError)
        assertEquals(SANITIZED_JOB_FAILURE, failure.message)
        assertEquals(null, failure.cause)
        assertFalse(failure.refireImmediately())
        listOf(captured.standardError, failure.stackTraceToString()).forEach { diagnostic ->
            assertFalse(diagnostic.contains(RAW_FAILURE))
        }
    }

    private fun executeCapturingStandardError(job: SuspendingUseCaseJob): CapturedExecution {
        val original = System.err
        val bytes = ByteArrayOutputStream()
        val capture = PrintStream(bytes, true, StandardCharsets.UTF_8)
        var failure: Throwable? = null
        try {
            System.setErr(capture)
            try {
                job.execute(UNUSED_CONTEXT)
            } catch (thrown: Throwable) {
                failure = thrown
            }
        } finally {
            capture.flush()
            System.setErr(original)
            capture.close()
        }
        return CapturedExecution(bytes.toString(StandardCharsets.UTF_8), failure)
    }

    private fun scheduler(
        refresh: RefreshAllRepositories = RefreshAllRepositories {
            RefreshAllRepositoriesResult(emptyList())
        },
        timeout: Duration = Duration.ofSeconds(2),
        clock: Clock = Clock.systemUTC(),
        registrationHook: (org.quartz.Scheduler) -> Unit = {},
    ): QuartzApplicationScheduler = QuartzApplicationScheduler.create(
        scheduledUseCases = ScheduledUseCases(
            refreshAllRepositories = refresh,
            retryPendingNotifications = RetryPendingNotifications { emptyDispatchSummary() },
            sendDueReminders = SendDueReminders { emptyList() },
            pruneInactivePullRequests = PruneInactivePullRequests {
                PruneInactivePullRequestsResult(0, clock.instant())
            },
        ),
        jobTimeout = timeout,
        clock = clock,
        registrationHook = registrationHook,
    )

    private fun useCases(calls: MutableList<String>) = ScheduledUseCases(
        refreshAllRepositories = RefreshAllRepositories {
            calls += REFRESH_KEY
            RefreshAllRepositoriesResult(emptyList())
        },
        retryPendingNotifications = RetryPendingNotifications {
            calls += RETRY_KEY
            emptyDispatchSummary()
        },
        sendDueReminders = SendDueReminders {
            calls += REMINDERS_KEY
            emptyList()
        },
        pruneInactivePullRequests = PruneInactivePullRequests {
            calls += PRUNE_KEY
            PruneInactivePullRequestsResult(0, Instant.EPOCH)
        },
    )

    private fun emptyDispatchSummary() = NotificationDispatchSummary(
        attemptedIntentIds = emptyList(),
        acceptedCount = 0,
        retryScheduledCount = 0,
        exhaustedCount = 0,
    )

    private data class CapturedExecution(val standardError: String, val failure: Throwable?)

    private companion object {
        const val REFRESH_KEY = "refresh-all-repositories"
        const val RETRY_KEY = "retry-pending-notifications"
        const val REMINDERS_KEY = "send-due-reminders"
        const val PRUNE_KEY = "prune-inactive-pull-requests"
        val STABLE_JOB_KEYS = sortedSetOf(REFRESH_KEY, RETRY_KEY, REMINDERS_KEY, PRUNE_KEY)
        const val STOPPED_CODE = "scheduler-stopped"
        const val RUNNING_CODE = "scheduler-running"
        const val REGISTRATION_FAILED_CODE = "scheduler-registration-failed"
        const val SANITIZED_JOB_FAILURE = "Scheduled application use case failed"
        const val RAW_FAILURE = "sentinel-api-token Authorization database internals"
        const val TEST_TIMEOUT_SECONDS = 3L
        val UNUSED_CONTEXT = Proxy.newProxyInstance(
            JobExecutionContext::class.java.classLoader,
            arrayOf(JobExecutionContext::class.java),
        ) { _, _, _ -> throw AssertionError("JobExecutionContext must not be used") } as JobExecutionContext
    }
}

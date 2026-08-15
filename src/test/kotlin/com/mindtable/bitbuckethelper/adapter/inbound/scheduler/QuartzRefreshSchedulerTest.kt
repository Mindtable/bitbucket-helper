package com.mindtable.bitbuckethelper.adapter.inbound.scheduler

import com.mindtable.bitbuckethelper.application.model.BitbucketAccount
import com.mindtable.bitbuckethelper.application.model.BitbucketConnectionSnapshot
import com.mindtable.bitbuckethelper.application.model.ConnectionState
import com.mindtable.bitbuckethelper.application.port.inbound.RefreshBitbucketConnection
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.lang.reflect.Proxy
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.quartz.JobExecutionContext
import org.quartz.JobExecutionException
import org.quartz.JobKey
import org.quartz.SimpleTrigger
import org.quartz.TriggerKey

class QuartzRefreshSchedulerTest {
    @Test
    fun `start schedules an immediate refresh`() = runBlocking {
        val invoked = CompletableDeferred<Unit>()
        val scheduler = scheduler(
            refresh = RefreshBitbucketConnection {
                invoked.complete(Unit)
                healthySnapshot()
            },
        )

        try {
            scheduler.start()

            withTimeout(TEST_TIMEOUT_MILLIS) { invoked.await() }
        } finally {
            scheduler.close()
        }
    }

    @Test
    fun `repeating trigger never overlaps refresh executions`() = runBlocking {
        val firstInvocationEntered = CompletableDeferred<Unit>()
        val releaseFirstInvocation = CompletableDeferred<Unit>()
        val twoInvocationsCompleted = CompletableDeferred<Unit>()
        val invocationSequence = AtomicInteger()
        val currentConcurrency = AtomicInteger()
        val maximumConcurrency = AtomicInteger()
        val completedInvocations = AtomicInteger()
        val scheduler = scheduler(
            refreshInterval = Duration.ofMillis(20),
            refresh = RefreshBitbucketConnection {
                val invocation = invocationSequence.incrementAndGet()
                val active = currentConcurrency.incrementAndGet()
                maximumConcurrency.updateAndGet { previous -> maxOf(previous, active) }
                try {
                    if (invocation == 1) {
                        firstInvocationEntered.complete(Unit)
                        releaseFirstInvocation.await()
                    }
                    healthySnapshot()
                } finally {
                    currentConcurrency.decrementAndGet()
                    if (completedInvocations.incrementAndGet() == 2) {
                        twoInvocationsCompleted.complete(Unit)
                    }
                }
            },
        )

        try {
            scheduler.start()
            withTimeout(TEST_TIMEOUT_MILLIS) { firstInvocationEntered.await() }

            val nextScheduledFireAt = scheduler.scheduledTrigger().nextFireTime.time
            withTimeout(TEST_TIMEOUT_MILLIS) {
                while (System.currentTimeMillis() < nextScheduledFireAt + OVERLAP_OPPORTUNITY_MILLIS) {
                    yield()
                }
            }
            releaseFirstInvocation.complete(Unit)

            withTimeout(TEST_TIMEOUT_MILLIS) { twoInvocationsCompleted.await() }
            assertEquals(1, maximumConcurrency.get())
        } finally {
            releaseFirstInvocation.complete(Unit)
            scheduler.close()
        }
    }

    @Test
    fun `trigger repeats forever with an explicit skip catch-up misfire policy`() {
        val scheduler = scheduler(refreshInterval = Duration.ofSeconds(37))

        try {
            val trigger = scheduler.scheduledTrigger()

            assertEquals(TriggerKey(REFRESH_KEY_NAME, REFRESH_KEY_GROUP), trigger.key)
            assertEquals(JobKey(REFRESH_KEY_NAME, REFRESH_KEY_GROUP), trigger.jobKey)
            assertEquals(37_000L, trigger.repeatInterval)
            assertEquals(SimpleTrigger.REPEAT_INDEFINITELY, trigger.repeatCount)
            assertEquals(
                SimpleTrigger.MISFIRE_INSTRUCTION_RESCHEDULE_NEXT_WITH_REMAINING_COUNT,
                trigger.misfireInstruction,
            )
        } finally {
            scheduler.close()
        }
    }

    @Test
    fun `start and close are idempotent and a closed scheduler cannot restart`() = runBlocking {
        val invoked = CompletableDeferred<Unit>()
        val scheduler = scheduler(
            refresh = RefreshBitbucketConnection {
                invoked.complete(Unit)
                healthySnapshot()
            },
        )

        try {
            scheduler.start()
            scheduler.start()
            withTimeout(TEST_TIMEOUT_MILLIS) { invoked.await() }
        } finally {
            scheduler.close()
            scheduler.close()
        }

        assertThrows(IllegalStateException::class.java) { scheduler.start() }
        Unit
    }

    @Test
    fun `job timeout cancels a suspended refresh`() = runBlocking {
        val refreshEntered = CompletableDeferred<Unit>()
        val refreshCancelled = CompletableDeferred<Unit>()
        val releaseRefresh = CompletableDeferred<Unit>()
        val scheduler = scheduler(
            jobTimeout = Duration.ofMillis(40),
            refresh = RefreshBitbucketConnection {
                refreshEntered.complete(Unit)
                try {
                    releaseRefresh.await()
                    healthySnapshot()
                } catch (cancelled: CancellationException) {
                    refreshCancelled.complete(Unit)
                    healthySnapshot()
                }
            },
        )

        try {
            scheduler.start()
            withTimeout(TEST_TIMEOUT_MILLIS) { refreshEntered.await() }
            withTimeout(TEST_TIMEOUT_MILLIS) { refreshCancelled.await() }
        } finally {
            releaseRefresh.complete(Unit)
            scheduler.close()
        }
    }

    @Test
    fun `job reports refresh exceptions once without exposing their diagnostics or requesting refire`() {
        val job = RefreshBitbucketConnectionJob(
            refresh = RefreshBitbucketConnection {
                error(RAW_REFRESH_FAILURE)
            },
            timeout = Duration.ofSeconds(1),
        )

        assertSanitizedJobFailure(executeCapturingStandardError(job))
    }

    @Test
    fun `job reports timeouts through the same sanitized non-refiring boundary`() {
        val neverCompletes = CompletableDeferred<Unit>()
        val job = RefreshBitbucketConnectionJob(
            refresh = RefreshBitbucketConnection {
                neverCompletes.await()
                healthySnapshot()
            },
            timeout = Duration.ofMillis(20),
        )

        assertSanitizedJobFailure(executeCapturingStandardError(job))
    }

    @Test
    fun `job does not translate Errors`() {
        val fatal = AssertionError("fatal scheduler test error")
        val job = RefreshBitbucketConnectionJob(
            refresh = RefreshBitbucketConnection { throw fatal },
            timeout = Duration.ofSeconds(1),
        )

        val captured = executeCapturingStandardError(job)

        assertTrue(captured.failure is AssertionError)
        assertFalse(captured.failure is JobExecutionException)
        assertTrue(captured.standardError.isEmpty())
    }

    @Test
    fun `sub-millisecond intervals and timeouts are rejected before Quartz creation`() {
        assertSchedulerCreationRejected(refreshInterval = Duration.ofNanos(1))
        assertSchedulerCreationRejected(jobTimeout = Duration.ofNanos(1))
    }

    private fun assertSchedulerCreationRejected(
        refreshInterval: Duration = Duration.ofMinutes(15),
        jobTimeout: Duration = Duration.ofSeconds(2),
    ) {
        var unexpectedlyCreatedScheduler: QuartzRefreshScheduler? = null
        try {
            assertThrows(IllegalArgumentException::class.java) {
                unexpectedlyCreatedScheduler = scheduler(
                    refreshInterval = refreshInterval,
                    jobTimeout = jobTimeout,
                )
            }
        } finally {
            unexpectedlyCreatedScheduler?.close()
        }
    }

    private fun assertSanitizedJobFailure(captured: CapturedJobExecution) {
        assertTrue(captured.failure is JobExecutionException)
        val failure = captured.failure as JobExecutionException
        val expectedStandardError = SANITIZED_JOB_FAILURE + System.lineSeparator()

        assertTrue(captured.standardError == expectedStandardError)
        assertTrue(failure.message == SANITIZED_JOB_FAILURE)
        assertTrue(failure.cause == null)
        assertTrue(failure.suppressed.isEmpty())
        assertFalse(failure.refireImmediately())

        val publicDiagnostics = listOf(
            captured.standardError,
            failure.message.orEmpty(),
            failure.stackTraceToString(),
        )
        FORBIDDEN_DIAGNOSTIC_FRAGMENTS.forEach { forbidden ->
            assertFalse(publicDiagnostics.any { it.contains(forbidden, ignoreCase = true) })
        }
    }

    private fun executeCapturingStandardError(
        job: RefreshBitbucketConnectionJob,
    ): CapturedJobExecution {
        val originalStandardError = System.err
        val bytes = ByteArrayOutputStream()
        val capture = PrintStream(bytes, true, StandardCharsets.UTF_8)
        var failure: Throwable? = null
        try {
            System.setErr(capture)
            try {
                job.execute(UNUSED_JOB_EXECUTION_CONTEXT)
            } catch (thrown: Throwable) {
                failure = thrown
            }
        } finally {
            capture.flush()
            System.setErr(originalStandardError)
            capture.close()
        }
        return CapturedJobExecution(
            standardError = bytes.toString(StandardCharsets.UTF_8),
            failure = failure,
        )
    }

    private fun scheduler(
        refresh: RefreshBitbucketConnection = RefreshBitbucketConnection { healthySnapshot() },
        refreshInterval: Duration = Duration.ofMinutes(15),
        jobTimeout: Duration = Duration.ofSeconds(2),
    ): QuartzRefreshScheduler = QuartzRefreshScheduler.create(
        refresh = refresh,
        refreshInterval = refreshInterval,
        jobTimeout = jobTimeout,
    )

    private fun healthySnapshot() = BitbucketConnectionSnapshot(
        state = ConnectionState.HEALTHY,
        account = BitbucketAccount(
            uuid = "{scheduler-test-account}",
            displayName = "Scheduler Test",
            nickname = null,
        ),
        lastAttemptAt = Instant.parse("2026-08-15T12:00:00Z"),
        lastSuccessAt = Instant.parse("2026-08-15T12:00:00Z"),
        failure = null,
    )

    private companion object {
        const val TEST_TIMEOUT_MILLIS = 2_000L
        const val OVERLAP_OPPORTUNITY_MILLIS = 100L
        const val REFRESH_KEY_NAME = "bitbucket-current-user-refresh"
        const val REFRESH_KEY_GROUP = "bitbucket-helper"
        const val SANITIZED_JOB_FAILURE = "Bitbucket refresh job failed"
        const val RAW_REFRESH_FAILURE =
            "sentinel-api-token Authorization database internals must remain private"
        val FORBIDDEN_DIAGNOSTIC_FRAGMENTS = listOf(
            "sentinel-api-token",
            "Authorization",
            "database internals",
            "must remain private",
        )
        val UNUSED_JOB_EXECUTION_CONTEXT = Proxy.newProxyInstance(
            JobExecutionContext::class.java.classLoader,
            arrayOf(JobExecutionContext::class.java),
        ) { _, _, _ ->
            throw AssertionError("JobExecutionContext is not used by the refresh job")
        } as JobExecutionContext
    }

    private data class CapturedJobExecution(
        val standardError: String,
        val failure: Throwable?,
    )
}

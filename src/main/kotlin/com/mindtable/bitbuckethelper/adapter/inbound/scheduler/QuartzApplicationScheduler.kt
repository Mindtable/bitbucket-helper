package com.mindtable.bitbuckethelper.adapter.inbound.scheduler

import com.mindtable.bitbuckethelper.application.model.HealthComponent
import com.mindtable.bitbuckethelper.application.model.HealthComponentSnapshot
import com.mindtable.bitbuckethelper.application.model.HealthStatus
import com.mindtable.bitbuckethelper.observability.BackendEventRecorder
import com.mindtable.bitbuckethelper.observability.BackendLogEvent
import com.mindtable.bitbuckethelper.observability.MonotonicTimeSource
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import java.util.Date
import java.util.Properties
import java.util.UUID
import org.quartz.CronScheduleBuilder
import org.quartz.JobBuilder
import org.quartz.JobKey
import org.quartz.Scheduler
import org.quartz.SchedulerMetaData
import org.quartz.SimpleScheduleBuilder
import org.quartz.Trigger
import org.quartz.TriggerBuilder
import org.quartz.TriggerKey
import org.quartz.impl.StdSchedulerFactory
import org.quartz.impl.matchers.GroupMatcher
import org.quartz.simpl.RAMJobStore
import org.quartz.simpl.SimpleThreadPool

internal data class SchedulerLifecycleSeams(
    val registrationHook: (Scheduler) -> Unit = {},
    val startAction: (Scheduler) -> Unit = { it.start() },
    val shutdownEntered: () -> Unit = {},
    val shutdownAction: (Scheduler, Boolean) -> Unit = { scheduler, waitForJobs ->
        scheduler.shutdown(waitForJobs)
    },
)

/**
 * Safe Quartz-facing lifecycle failure with a private diagnostic channel for
 * the service boundary. The original failure is deliberately not installed
 * as [cause], so public rendering remains the fixed safe wrapper.
 */
internal class SchedulerLifecycleFailure(
    message: String,
    internal val diagnosticFailure: Throwable,
) : IllegalStateException(message) {
    override fun toString(): String = "java.lang.IllegalStateException: $message"
}

class QuartzApplicationScheduler private constructor(
    private val scheduler: Scheduler,
    private val seams: SchedulerLifecycleSeams,
    private val recorder: BackendEventRecorder,
) : AutoCloseable {
    private val lifecycleMonitor = Any()
    private var started = false
    private var closed = false
    private var terminalFailureCode: String? = null
    private var terminalDiagnosticFailure: Throwable? = null
    private var shutdownComplete = false

    fun start() {
        synchronized(lifecycleMonitor) {
            terminalFailureCode?.let { failureCode ->
                throw SchedulerLifecycleFailure(
                    message = failureMessage(failureCode),
                    diagnosticFailure = terminalDiagnosticFailure
                        ?: IllegalStateException(failureMessage(failureCode)),
                )
            }
            check(!closed) { CLOSED_MESSAGE }
            if (started) return
            try {
                seams.startAction(scheduler)
                started = true
                recordSafely(BackendLogEvent.SchedulerStarted("running"))
            } catch (failure: Throwable) {
                terminalFailureCode = START_FAILED_CODE
                terminalDiagnosticFailure = (failure as? SchedulerLifecycleFailure)?.diagnosticFailure ?: failure
                attemptShutdown()
                if (failure is Error) throw failure
                throw SchedulerLifecycleFailure(
                    START_FAILED_MESSAGE,
                    terminalDiagnosticFailure ?: failure,
                )
            }
        }
    }

    fun health(): HealthComponentSnapshot = synchronized(lifecycleMonitor) {
        terminalFailureCode?.let { failureCode ->
            HealthComponentSnapshot(
                component = HealthComponent.SCHEDULER,
                status = HealthStatus.UNHEALTHY,
                safeCode = failureCode,
            )
        } ?: when {
            started && !closed -> HealthComponentSnapshot(
                component = HealthComponent.SCHEDULER,
                status = HealthStatus.HEALTHY,
                safeCode = RUNNING_CODE,
            )
            else -> HealthComponentSnapshot(
                component = HealthComponent.SCHEDULER,
                status = HealthStatus.HEALTHY,
                safeCode = STOPPED_CODE,
            )
        }
    }

    internal fun scheduledJobKeyNames(): Set<String> =
        scheduler.getJobKeys(GroupMatcher.anyJobGroup()).mapTo(sortedSetOf()) { it.name }

    internal fun scheduledTrigger(useCaseKey: String): Trigger =
        checkNotNull(scheduler.getTrigger(triggerKey(useCaseKey))) {
            "Application trigger is not scheduled"
        }

    internal fun schedulerMetadata(): SchedulerMetaData = scheduler.metaData

    override fun close() {
        synchronized(lifecycleMonitor) {
            if (closed) return
            if (!shutdownComplete) {
                val cleanupFailure = attemptShutdown()
                if (cleanupFailure != null) {
                    if (cleanupFailure is Error) throw cleanupFailure
                    throw SchedulerLifecycleFailure(
                        SHUTDOWN_FAILED_MESSAGE,
                        (cleanupFailure as? SchedulerLifecycleFailure)?.diagnosticFailure ?: cleanupFailure,
                    )
                }
            }
            closed = true
            started = false
        }
    }

    private fun attemptShutdown(): Throwable? {
        try {
            seams.shutdownEntered()
        } catch (_: Throwable) {
            // Test observation must never prevent the actual Quartz cleanup attempt.
        }
        return try {
            seams.shutdownAction(scheduler, true)
            shutdownComplete = true
            recordSafely(BackendLogEvent.SchedulerStopped("stopped"))
            null
        } catch (failure: Throwable) {
            failure
        }
    }

    private fun recordSafely(event: BackendLogEvent) {
        try {
            recorder.record(event)
        } catch (_: Throwable) {
            // Logging must not alter scheduler lifecycle or cleanup semantics.
        }
    }

    companion object {
        private const val GROUP = "bitbucket-helper-application"
        private const val THREAD_COUNT = 4
        private const val RUNNING_CODE = "scheduler-running"
        private const val STOPPED_CODE = "scheduler-stopped"
        private const val REGISTRATION_FAILED_CODE = "scheduler-registration-failed"
        private const val START_FAILED_CODE = "scheduler-start-failed"
        private const val REGISTRATION_FAILED_MESSAGE = "Quartz application scheduler registration failed"
        private const val START_FAILED_MESSAGE = "Quartz application scheduler start failed"
        private const val SHUTDOWN_FAILED_MESSAGE = "Quartz application scheduler shutdown failed"
        private const val CLOSED_MESSAGE = "Quartz application scheduler is closed"
        private val UTC = java.util.TimeZone.getTimeZone(ZoneOffset.UTC)

        fun create(
            scheduledUseCases: ScheduledUseCases,
            jobTimeout: Duration,
            recorder: BackendEventRecorder = BackendEventRecorder.NONE,
            executionIdSource: () -> String = { UUID.randomUUID().toString() },
            timeSource: MonotonicTimeSource = MonotonicTimeSource.SYSTEM,
        ): QuartzApplicationScheduler = create(
            scheduledUseCases = scheduledUseCases,
            jobTimeout = jobTimeout,
            clock = Clock.systemUTC(),
            seams = SchedulerLifecycleSeams(),
            recorder = recorder,
            executionIdSource = executionIdSource,
            timeSource = timeSource,
        )

        internal fun create(
            scheduledUseCases: ScheduledUseCases,
            jobTimeout: Duration,
            clock: Clock,
            seams: SchedulerLifecycleSeams = SchedulerLifecycleSeams(),
            recorder: BackendEventRecorder = BackendEventRecorder.NONE,
            executionIdSource: () -> String = { UUID.randomUUID().toString() },
            timeSource: MonotonicTimeSource = MonotonicTimeSource.SYSTEM,
        ): QuartzApplicationScheduler {
            SuspendingUseCaseJob.requirePositiveWholeMilliseconds("jobTimeout", jobTimeout)
            val scheduler = StdSchedulerFactory(quartzProperties()).scheduler
            val applicationScheduler = QuartzApplicationScheduler(scheduler, seams, recorder)
            try {
                scheduler.setJobFactory(
                    ApplicationUseCaseJobFactory(
                        scheduledUseCases = scheduledUseCases,
                        timeout = jobTimeout,
                        executionIdSource = executionIdSource,
                        recorder = recorder,
                        timeSource = timeSource,
                    ),
                )
                seams.registrationHook(scheduler)
                registerSchedules(scheduler, clock.instant())
            } catch (failure: Throwable) {
                applicationScheduler.terminalFailureCode = REGISTRATION_FAILED_CODE
                applicationScheduler.terminalDiagnosticFailure = failure
                applicationScheduler.attemptShutdown()
                if (failure is Error) throw failure
            }
            return applicationScheduler
        }

        private fun failureMessage(failureCode: String): String = when (failureCode) {
            REGISTRATION_FAILED_CODE -> REGISTRATION_FAILED_MESSAGE
            START_FAILED_CODE -> START_FAILED_MESSAGE
            else -> "Quartz application scheduler failed"
        }

        private fun registerSchedules(scheduler: Scheduler, now: Instant) {
            scheduleSimple(
                scheduler = scheduler,
                useCaseKey = ScheduledUseCases.REFRESH_ALL_REPOSITORIES,
                startAt = now,
                interval = Duration.ofMinutes(5),
            )
            scheduleSimple(
                scheduler = scheduler,
                useCaseKey = ScheduledUseCases.RETRY_PENDING_NOTIFICATIONS,
                startAt = now.plus(Duration.ofMinutes(1)),
                interval = Duration.ofMinutes(1),
            )
            val nextUtcHour = now.atZone(ZoneOffset.UTC)
                .truncatedTo(ChronoUnit.HOURS)
                .plusHours(1)
                .toInstant()
            scheduleSimple(
                scheduler = scheduler,
                useCaseKey = ScheduledUseCases.SEND_DUE_REMINDERS,
                startAt = nextUtcHour,
                interval = Duration.ofHours(1),
            )

            val pruneKey = ScheduledUseCases.PRUNE_INACTIVE_PULL_REQUESTS
            val pruneJob = job(pruneKey)
            val pruneTrigger = TriggerBuilder.newTrigger()
                .withIdentity(triggerKey(pruneKey))
                .forJob(pruneJob.key)
                .startAt(Date.from(now))
                .withSchedule(
                    CronScheduleBuilder.cronSchedule("0 0 3 * * ?")
                        .inTimeZone(UTC)
                        .withMisfireHandlingInstructionDoNothing(),
                )
                .build()
            scheduler.scheduleJob(pruneJob, pruneTrigger)
        }

        private fun scheduleSimple(
            scheduler: Scheduler,
            useCaseKey: String,
            startAt: Instant,
            interval: Duration,
        ) {
            val job = job(useCaseKey)
            val trigger = TriggerBuilder.newTrigger()
                .withIdentity(triggerKey(useCaseKey))
                .forJob(job.key)
                .startAt(Date.from(startAt))
                .withSchedule(
                    SimpleScheduleBuilder.simpleSchedule()
                        .withIntervalInMilliseconds(interval.toMillis())
                        .repeatForever()
                        .withMisfireHandlingInstructionNextWithRemainingCount(),
                )
                .build()
            scheduler.scheduleJob(job, trigger)
        }

        private fun job(useCaseKey: String) = JobBuilder.newJob(SuspendingUseCaseJob::class.java)
            .withIdentity(JobKey(useCaseKey, GROUP))
            .usingJobData(ApplicationUseCaseJobFactory.USE_CASE_KEY, useCaseKey)
            .build()

        private fun triggerKey(useCaseKey: String) = TriggerKey("$useCaseKey-trigger", GROUP)

        private fun quartzProperties() = Properties().apply {
            setProperty(
                "org.quartz.scheduler.instanceName",
                "bitbucket-helper-application-${UUID.randomUUID()}",
            )
            setProperty("org.quartz.threadPool.class", SimpleThreadPool::class.java.name)
            setProperty("org.quartz.threadPool.threadCount", THREAD_COUNT.toString())
            setProperty("org.quartz.threadPool.threadPriority", Thread.NORM_PRIORITY.toString())
            setProperty("org.quartz.jobStore.class", RAMJobStore::class.java.name)
        }
    }
}

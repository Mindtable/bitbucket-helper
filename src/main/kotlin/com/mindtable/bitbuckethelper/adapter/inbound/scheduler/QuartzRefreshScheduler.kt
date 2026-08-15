package com.mindtable.bitbuckethelper.adapter.inbound.scheduler

import com.mindtable.bitbuckethelper.application.port.inbound.RefreshBitbucketConnection
import java.time.Duration
import java.util.Properties
import java.util.UUID
import org.quartz.JobBuilder
import org.quartz.JobKey
import org.quartz.Scheduler
import org.quartz.SimpleScheduleBuilder
import org.quartz.SimpleTrigger
import org.quartz.TriggerBuilder
import org.quartz.TriggerKey
import org.quartz.impl.StdSchedulerFactory
import org.quartz.simpl.RAMJobStore
import org.quartz.simpl.SimpleThreadPool

class QuartzRefreshScheduler private constructor(
    private val scheduler: Scheduler,
) : AutoCloseable {
    private val lifecycleMonitor = Any()
    private var started = false
    private var closed = false

    fun start() {
        synchronized(lifecycleMonitor) {
            check(!closed) { "Quartz refresh scheduler is closed" }
            if (started) return

            scheduler.start()
            started = true
        }
    }

    internal fun scheduledTrigger(): SimpleTrigger =
        checkNotNull(scheduler.getTrigger(REFRESH_TRIGGER_KEY) as? SimpleTrigger) {
            "Quartz refresh trigger is not scheduled"
        }

    override fun close() {
        synchronized(lifecycleMonitor) {
            if (closed) return
            closed = true
            scheduler.shutdown(true)
        }
    }

    companion object {
        private const val REFRESH_KEY_NAME = "bitbucket-current-user-refresh"
        private const val REFRESH_KEY_GROUP = "bitbucket-helper"
        private const val THREAD_COUNT = 2

        private val REFRESH_JOB_KEY = JobKey(REFRESH_KEY_NAME, REFRESH_KEY_GROUP)
        private val REFRESH_TRIGGER_KEY = TriggerKey(REFRESH_KEY_NAME, REFRESH_KEY_GROUP)

        fun create(
            refresh: RefreshBitbucketConnection,
            refreshInterval: Duration,
            jobTimeout: Duration,
        ): QuartzRefreshScheduler {
            val refreshIntervalMillis = requirePositiveWholeMilliseconds(
                name = "refreshInterval",
                duration = refreshInterval,
            )
            requirePositiveWholeMilliseconds(name = "jobTimeout", duration = jobTimeout)

            val scheduler = StdSchedulerFactory(quartzProperties()).scheduler
            try {
                scheduler.setJobFactory(UseCaseJobFactory(refresh, jobTimeout))

                val job = JobBuilder.newJob(RefreshBitbucketConnectionJob::class.java)
                    .withIdentity(REFRESH_JOB_KEY)
                    .build()
                val trigger = TriggerBuilder.newTrigger()
                    .withIdentity(REFRESH_TRIGGER_KEY)
                    .forJob(REFRESH_JOB_KEY)
                    .startNow()
                    .withSchedule(
                        SimpleScheduleBuilder.simpleSchedule()
                            .withIntervalInMilliseconds(refreshIntervalMillis)
                            .repeatForever()
                            .withMisfireHandlingInstructionNextWithRemainingCount(),
                    )
                    .build()

                scheduler.scheduleJob(job, trigger)
                return QuartzRefreshScheduler(scheduler)
            } catch (failure: Throwable) {
                try {
                    scheduler.shutdown(true)
                } catch (cleanupFailure: Throwable) {
                    failure.addSuppressed(cleanupFailure)
                }
                throw failure
            }
        }

        private fun quartzProperties() = Properties().apply {
            setProperty(
                "org.quartz.scheduler.instanceName",
                "bitbucket-helper-refresh-${UUID.randomUUID()}",
            )
            setProperty("org.quartz.threadPool.class", SimpleThreadPool::class.java.name)
            setProperty("org.quartz.threadPool.threadCount", THREAD_COUNT.toString())
            setProperty("org.quartz.threadPool.threadPriority", Thread.NORM_PRIORITY.toString())
            setProperty("org.quartz.jobStore.class", RAMJobStore::class.java.name)
        }

        private fun requirePositiveWholeMilliseconds(name: String, duration: Duration): Long {
            val milliseconds = try {
                duration.toMillis()
            } catch (overflow: ArithmeticException) {
                throw IllegalArgumentException("$name must fit in whole milliseconds", overflow)
            }
            require(milliseconds > 0) { "$name must be at least one millisecond" }
            return milliseconds
        }
    }
}

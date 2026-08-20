package com.mindtable.bitbuckethelper.adapter.inbound.scheduler

import com.mindtable.bitbuckethelper.observability.BackendEventRecorder
import com.mindtable.bitbuckethelper.observability.MonotonicTimeSource
import java.time.Duration
import java.util.UUID
import org.quartz.Job
import org.quartz.Scheduler
import org.quartz.spi.JobFactory
import org.quartz.spi.TriggerFiredBundle

class ApplicationUseCaseJobFactory(
    private val scheduledUseCases: ScheduledUseCases,
    private val timeout: Duration,
    private val executionIdSource: () -> String = { UUID.randomUUID().toString() },
    private val recorder: BackendEventRecorder = BackendEventRecorder.NONE,
    private val timeSource: MonotonicTimeSource = MonotonicTimeSource.SYSTEM,
) : JobFactory {
    init {
        SuspendingUseCaseJob.requirePositiveWholeMilliseconds("jobTimeout", timeout)
    }

    override fun newJob(bundle: TriggerFiredBundle, scheduler: Scheduler): Job {
        check(bundle.jobDetail.jobClass == SuspendingUseCaseJob::class.java) {
            "Unsupported Quartz job type"
        }
        val useCaseKey = bundle.jobDetail.jobDataMap.getString(USE_CASE_KEY)
            ?: throw IllegalStateException("Scheduled use case key is missing")
        return SuspendingUseCaseJob(
            operation = scheduledUseCases.operation(useCaseKey),
            timeout = timeout,
            useCaseKey = useCaseKey,
            executionIdSource = executionIdSource,
            recorder = recorder,
            timeSource = timeSource,
        )
    }

    companion object {
        const val USE_CASE_KEY = "scheduled-use-case-key"
    }
}

package com.mindtable.bitbuckethelper.adapter.inbound.scheduler

import java.time.Duration
import org.quartz.Job
import org.quartz.Scheduler
import org.quartz.spi.JobFactory
import org.quartz.spi.TriggerFiredBundle

class ApplicationUseCaseJobFactory(
    private val scheduledUseCases: ScheduledUseCases,
    private val timeout: Duration,
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
        )
    }

    companion object {
        const val USE_CASE_KEY = "scheduled-use-case-key"
    }
}

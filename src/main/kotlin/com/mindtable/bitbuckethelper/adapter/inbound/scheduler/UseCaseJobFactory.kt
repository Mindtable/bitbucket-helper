package com.mindtable.bitbuckethelper.adapter.inbound.scheduler

import com.mindtable.bitbuckethelper.application.port.inbound.RefreshBitbucketConnection
import java.time.Duration
import org.quartz.Job
import org.quartz.Scheduler
import org.quartz.spi.JobFactory
import org.quartz.spi.TriggerFiredBundle

class UseCaseJobFactory(
    private val refresh: RefreshBitbucketConnection,
    private val timeout: Duration,
) : JobFactory {
    override fun newJob(bundle: TriggerFiredBundle, scheduler: Scheduler): Job {
        check(bundle.jobDetail.jobClass == RefreshBitbucketConnectionJob::class.java) {
            "Unsupported Quartz job type"
        }
        return RefreshBitbucketConnectionJob(refresh, timeout)
    }
}

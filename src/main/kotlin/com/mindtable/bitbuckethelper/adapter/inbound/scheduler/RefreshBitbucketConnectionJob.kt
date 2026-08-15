package com.mindtable.bitbuckethelper.adapter.inbound.scheduler

import com.mindtable.bitbuckethelper.application.port.inbound.RefreshBitbucketConnection
import java.time.Duration
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.quartz.DisallowConcurrentExecution
import org.quartz.Job
import org.quartz.JobExecutionContext
import org.quartz.JobExecutionException

@DisallowConcurrentExecution
class RefreshBitbucketConnectionJob(
    private val refresh: RefreshBitbucketConnection,
    private val timeout: Duration,
) : Job {
    override fun execute(context: JobExecutionContext) {
        try {
            runBlocking {
                withTimeout(timeout.toMillis()) {
                    refresh()
                }
            }
        } catch (_: Exception) {
            System.err.println(SANITIZED_FAILURE_DIAGNOSTIC)
            throw JobExecutionException(SANITIZED_FAILURE_DIAGNOSTIC, false)
        }
    }

    private companion object {
        const val SANITIZED_FAILURE_DIAGNOSTIC = "Bitbucket refresh job failed"
    }
}

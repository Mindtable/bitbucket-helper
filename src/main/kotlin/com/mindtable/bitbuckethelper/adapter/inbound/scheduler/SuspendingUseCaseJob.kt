package com.mindtable.bitbuckethelper.adapter.inbound.scheduler

import java.time.Duration
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.quartz.DisallowConcurrentExecution
import org.quartz.Job
import org.quartz.JobExecutionContext
import org.quartz.JobExecutionException

@DisallowConcurrentExecution
class SuspendingUseCaseJob(
    private val operation: suspend () -> Unit,
    private val timeout: Duration,
) : Job {
    init {
        requirePositiveWholeMilliseconds("jobTimeout", timeout)
    }

    override fun execute(context: JobExecutionContext) {
        try {
            runBlocking {
                withTimeout(timeout.toMillis()) {
                    operation()
                }
            }
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            throw sanitizedFailure()
        } catch (_: Exception) {
            throw sanitizedFailure()
        }
    }

    companion object {
        const val SANITIZED_FAILURE_DIAGNOSTIC = "Scheduled application use case failed"

        private fun sanitizedFailure(): JobExecutionException {
            System.err.println(SANITIZED_FAILURE_DIAGNOSTIC)
            return JobExecutionException(SANITIZED_FAILURE_DIAGNOSTIC, false)
        }

        internal fun requirePositiveWholeMilliseconds(name: String, duration: Duration): Long {
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

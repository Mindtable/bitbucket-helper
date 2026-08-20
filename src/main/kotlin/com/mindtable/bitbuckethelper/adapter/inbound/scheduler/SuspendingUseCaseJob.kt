package com.mindtable.bitbuckethelper.adapter.inbound.scheduler

import com.mindtable.bitbuckethelper.observability.BackendEventRecorder
import com.mindtable.bitbuckethelper.observability.BackendLogEvent
import com.mindtable.bitbuckethelper.observability.MonotonicTimeSource
import com.mindtable.bitbuckethelper.observability.reportBackendEventRecorderFailure
import java.time.Duration
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
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
    private val useCaseKey: String = DEFAULT_USE_CASE_KEY,
    private val executionIdSource: () -> String = { UUID.randomUUID().toString() },
    private val recorder: BackendEventRecorder = BackendEventRecorder.NONE,
    private val timeSource: MonotonicTimeSource = MonotonicTimeSource.SYSTEM,
) : Job {
    init {
        requirePositiveWholeMilliseconds("jobTimeout", timeout)
    }

    override fun execute(context: JobExecutionContext) {
        val executionId = executionIdSource()
        val startedAt = timeSource.nanoTime()
        recordSafely(BackendLogEvent.SchedulerJobStarted(executionId, useCaseKey))
        var operationFailure: Throwable? = null
        try {
            runBlocking {
                withTimeout(timeout.toMillis()) {
                    try {
                        operation()
                    } catch (cancellation: CancellationException) {
                        throw cancellation
                    } catch (interrupted: InterruptedException) {
                        throw interrupted
                    } catch (failure: Throwable) {
                        // Capture application failures inside the coroutine so
                        // kotlinx.coroutines cannot replace their identity
                        // with a recovered boundary wrapper.
                        operationFailure = failure
                    }
                }
            }
        } catch (_: TimeoutCancellationException) {
            recordSafely(
                BackendLogEvent.SchedulerJobTimedOut(
                    schedulerExecutionId = executionId,
                    jobKey = useCaseKey,
                    durationMilliseconds = elapsedMilliseconds(startedAt),
                ),
            )
            throw sanitizedFailure()
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            recordSafely(
                BackendLogEvent.SchedulerJobInterrupted(
                    schedulerExecutionId = executionId,
                    jobKey = useCaseKey,
                    durationMilliseconds = elapsedMilliseconds(startedAt),
                ),
            )
            throw sanitizedFailure()
        } catch (_: CancellationException) {
            recordSafely(
                BackendLogEvent.SchedulerJobInterrupted(
                    schedulerExecutionId = executionId,
                    jobKey = useCaseKey,
                    durationMilliseconds = elapsedMilliseconds(startedAt),
                ),
            )
            // Preserve the existing Quartz-facing cancellation contract: the
            // coroutine cancellation is translated to the same non-refiring
            // safe failure, but it is not an unexpected application failure.
            throw sanitizedFailure()
        } catch (failure: Exception) {
            val originalFailure = recoverOriginalFailure(failure)
            recordSafely(
                BackendLogEvent.SchedulerJobFailed(
                    schedulerExecutionId = executionId,
                    jobKey = useCaseKey,
                    durationMilliseconds = elapsedMilliseconds(startedAt),
                    failure = originalFailure,
                ),
            )
            throw sanitizedFailure()
        } catch (failure: Error) {
            val originalFailure = recoverOriginalFailure(failure) as? Error ?: failure
            recordSafely(
                BackendLogEvent.SchedulerJobFailed(
                    schedulerExecutionId = executionId,
                    jobKey = useCaseKey,
                    durationMilliseconds = elapsedMilliseconds(startedAt),
                    failure = originalFailure,
                ),
            )
            throw originalFailure
        }

        operationFailure?.let { failure ->
            when (failure) {
                is Error -> {
                    recordSafely(
                        BackendLogEvent.SchedulerJobFailed(
                            schedulerExecutionId = executionId,
                            jobKey = useCaseKey,
                            durationMilliseconds = elapsedMilliseconds(startedAt),
                            failure = failure,
                        ),
                    )
                    throw failure
                }

                else -> {
                    recordSafely(
                        BackendLogEvent.SchedulerJobFailed(
                            schedulerExecutionId = executionId,
                            jobKey = useCaseKey,
                            durationMilliseconds = elapsedMilliseconds(startedAt),
                            failure = failure,
                        ),
                    )
                    throw sanitizedFailure()
                }
            }
        }

        recordSafely(
            BackendLogEvent.SchedulerJobCompleted(
                schedulerExecutionId = executionId,
                jobKey = useCaseKey,
                durationMilliseconds = elapsedMilliseconds(startedAt),
            ),
        )
    }

    private fun elapsedMilliseconds(startedAt: Long): Long =
        ((timeSource.nanoTime() - startedAt).coerceAtLeast(0L)) / NANOS_PER_MILLISECOND

    private fun recordSafely(event: BackendLogEvent) {
        try {
            recorder.record(event)
        } catch (_: Throwable) {
            reportBackendEventRecorderFailure()
            // An observation failure must not change Quartz execution
            // semantics or mask the original operation failure.
        }
    }

    private fun recoverOriginalFailure(failure: Throwable): Throwable {
        var current = failure
        while (current.stackTrace.any { it.className == "_COROUTINE._BOUNDARY" }) {
            val cause = current.cause ?: return current
            current = cause
        }
        return current
    }

    companion object {
        const val SANITIZED_FAILURE_DIAGNOSTIC = "Scheduled application use case failed"

        private const val DEFAULT_USE_CASE_KEY = "scheduled-use-case"
        private const val NANOS_PER_MILLISECOND = 1_000_000L

        private fun sanitizedFailure(): JobExecutionException =
            JobExecutionException(SANITIZED_FAILURE_DIAGNOSTIC, false)

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

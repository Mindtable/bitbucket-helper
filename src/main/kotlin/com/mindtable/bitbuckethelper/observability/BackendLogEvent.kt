package com.mindtable.bitbuckethelper.observability

/** Severity used by the application event contract. */
enum class BackendLogLevel {
    TRACE,
    DEBUG,
    INFO,
    WARN,
    ERROR,
}

/**
 * The allowlisted event vocabulary emitted by the backend.
 *
 * Event values are intentionally typed classes rather than a map of arbitrary
 * fields.  The Log4j adapter owns the only conversion from these values to
 * logger key/value pairs.
 */
sealed interface BackendLogEvent {
    val level: BackendLogLevel
    val eventName: String
    val message: String
    val event: String get() = eventName

    data class ServiceStarting(
        val version: String,
        val configuredLevel: String,
    ) : BackendLogEvent {
        override val level = BackendLogLevel.INFO
        override val eventName = "service.starting"
        override val message = "Service is starting"
    }

    data class ServiceStarted(
        val browserPort: Int,
    ) : BackendLogEvent {
        override val level = BackendLogLevel.INFO
        override val eventName = "service.started"
        override val message = "Service started"
    }

    data class ServiceStopping(
        val reasonCategory: String,
    ) : BackendLogEvent {
        override val level = BackendLogLevel.INFO
        override val eventName = "service.stopping"
        override val message = "Service is stopping"
    }

    data class ServiceStopped(
        val durationMilliseconds: Long,
    ) : BackendLogEvent {
        override val level = BackendLogLevel.INFO
        override val eventName = "service.stopped"
        override val message = "Service stopped"
    }

    class ServiceConfigurationFailed(
        val settingCode: String,
        val failure: Throwable,
    ) : BackendLogEvent {
        override val level = BackendLogLevel.ERROR
        override val eventName = "service.configuration.failed"
        override val message = "Service configuration failed"

        override fun toString(): String =
            "service.configuration.failed(settingCode=$settingCode, failure=<redacted>)"
    }

    class ServiceStartFailed(
        val component: String,
        val failure: Throwable,
    ) : BackendLogEvent {
        override val level = BackendLogLevel.ERROR
        override val eventName = "service.start.failed"
        override val message = "Service start failed"

        override fun toString(): String =
            "service.start.failed(component=$component, failure=<redacted>)"
    }

    class ServiceStopFailed(
        val component: String,
        val failure: Throwable,
    ) : BackendLogEvent {
        override val level = BackendLogLevel.ERROR
        override val eventName = "service.stop.failed"
        override val message = "Service stop failed"

        override fun toString(): String =
            "service.stop.failed(component=$component, failure=<redacted>)"
    }

    data class HttpRequestCompleted(
        val requestId: String,
        val transport: String,
        val method: String,
        val operation: String,
        val status: Int,
        val outcome: String,
        val durationMilliseconds: Long,
        val mutation: Boolean,
    ) : BackendLogEvent {
        override val level = if (mutation) BackendLogLevel.INFO else BackendLogLevel.DEBUG
        override val eventName = "http.request.completed"
        override val message = "HTTP request completed"
    }

    data class HttpRequestRejected(
        val requestId: String,
        val transport: String,
        val method: String,
        val operation: String,
        val status: Int,
        val requestErrorCode: String,
        val durationMilliseconds: Long,
    ) : BackendLogEvent {
        override val level = BackendLogLevel.WARN
        override val eventName = "http.request.rejected"
        override val message = "HTTP request rejected"
    }

    class HttpRequestFailed(
        val requestId: String,
        val transport: String,
        val method: String,
        val operation: String,
        val status: Int,
        val durationMilliseconds: Long,
        val failure: Throwable,
    ) : BackendLogEvent {
        override val level = BackendLogLevel.ERROR
        override val eventName = "http.request.failed"
        override val message = "HTTP request failed"

        override fun toString(): String =
            "http.request.failed(requestId=$requestId, transport=$transport, method=$method, operation=$operation, status=$status, durationMilliseconds=$durationMilliseconds, failure=<redacted>)"
    }

    data class SchedulerStarted(
        val state: String,
    ) : BackendLogEvent {
        override val level = BackendLogLevel.INFO
        override val eventName = "scheduler.started"
        override val message = "Scheduler started"
    }

    data class SchedulerStopped(
        val state: String,
    ) : BackendLogEvent {
        override val level = BackendLogLevel.INFO
        override val eventName = "scheduler.stopped"
        override val message = "Scheduler stopped"
    }

    data class SchedulerJobStarted(
        val schedulerExecutionId: String,
        val jobKey: String,
    ) : BackendLogEvent {
        override val level = BackendLogLevel.DEBUG
        override val eventName = "scheduler.job.started"
        override val message = "Scheduler job started"
    }

    data class SchedulerJobCompleted(
        val schedulerExecutionId: String,
        val jobKey: String,
        val durationMilliseconds: Long,
        val safeSummary: String? = null,
    ) : BackendLogEvent {
        override val level = BackendLogLevel.INFO
        override val eventName = "scheduler.job.completed"
        override val message = "Scheduler job completed"
    }

    data class SchedulerJobTimedOut(
        val schedulerExecutionId: String,
        val jobKey: String,
        val durationMilliseconds: Long,
    ) : BackendLogEvent {
        override val level = BackendLogLevel.WARN
        override val eventName = "scheduler.job.timed_out"
        override val message = "Scheduler job timed out"
    }

    data class SchedulerJobInterrupted(
        val schedulerExecutionId: String,
        val jobKey: String,
        val durationMilliseconds: Long,
    ) : BackendLogEvent {
        override val level = BackendLogLevel.WARN
        override val eventName = "scheduler.job.interrupted"
        override val message = "Scheduler job interrupted"
    }

    class SchedulerJobFailed(
        val schedulerExecutionId: String,
        val jobKey: String,
        val durationMilliseconds: Long,
        val failure: Throwable,
    ) : BackendLogEvent {
        override val level = BackendLogLevel.ERROR
        override val eventName = "scheduler.job.failed"
        override val message = "Scheduler job failed"

        override fun toString(): String =
            "scheduler.job.failed(schedulerExecutionId=$schedulerExecutionId, jobKey=$jobKey, durationMilliseconds=$durationMilliseconds, failure=<redacted>)"
    }

    data class RefreshRunRegistered(
        val refreshRunId: String,
        val repositoryCount: Int,
        val startedCount: Int,
        val joinedCount: Int,
        val deferredCount: Int,
        val notConfiguredCount: Int,
        val requestId: String? = null,
    ) : BackendLogEvent {
        override val level = BackendLogLevel.INFO
        override val eventName = "refresh.run.registered"
        override val message = "Refresh run registered"
    }

    data class RefreshRepositoryCompleted(
        val refreshRunId: String?,
        val repositoryId: String,
        val outcome: String,
        val durationMilliseconds: Long,
        val failureCategory: String? = null,
        val retryable: Boolean? = null,
        val retryAt: String? = null,
    ) : BackendLogEvent {
        override val level = BackendLogLevel.INFO
        override val eventName = "refresh.repository.completed"
        override val message = "Refresh repository completed"
    }

    data class RefreshRepositoryPartial(
        val refreshRunId: String?,
        val repositoryId: String,
        val failureCategory: String,
        val retryable: Boolean?,
        val durationMilliseconds: Long,
    ) : BackendLogEvent {
        override val level = BackendLogLevel.WARN
        override val eventName = "refresh.repository.partial"
        override val message = "Refresh repository partially completed"
    }

    data class RefreshRepositoryFailed(
        val refreshRunId: String?,
        val repositoryId: String,
        val failureCategory: String,
        val retryable: Boolean?,
        val retryAt: String?,
        val durationMilliseconds: Long,
    ) : BackendLogEvent {
        override val level = BackendLogLevel.WARN
        override val eventName = "refresh.repository.failed"
        override val message = "Refresh repository failed"
    }

    data class RefreshRepositoryDeferred(
        val refreshRunId: String?,
        val repositoryId: String,
        val retryAt: String?,
        val durationMilliseconds: Long,
    ) : BackendLogEvent {
        override val level = BackendLogLevel.INFO
        override val eventName = "refresh.repository.deferred"
        override val message = "Refresh repository deferred"
    }

    class RefreshRepositoryUnexpected(
        val refreshRunId: String?,
        val repositoryId: String,
        val durationMilliseconds: Long = 0,
        val failure: Throwable,
    ) : BackendLogEvent {
        override val level = BackendLogLevel.ERROR
        override val eventName = "refresh.repository.unexpected"
        override val message = "Refresh repository failed unexpectedly"

        override fun toString(): String =
            "refresh.repository.unexpected(refreshRunId=$refreshRunId, repositoryId=$repositoryId, durationMilliseconds=$durationMilliseconds, failure=<redacted>)"
    }

    data class BitbucketRequestCompleted(
        val operation: String,
        val repositoryId: String?,
        val status: Int?,
        val durationMilliseconds: Long,
    ) : BackendLogEvent {
        override val level = BackendLogLevel.DEBUG
        override val eventName = "bitbucket.request.completed"
        override val message = "Bitbucket request completed"
    }

    class BitbucketRequestFailed(
        val operation: String,
        val repositoryId: String?,
        val category: String,
        val retryable: Boolean?,
        val status: Int?,
        val durationMilliseconds: Long,
        val unexpectedFailure: Throwable? = null,
    ) : BackendLogEvent {
        override val level = BackendLogLevel.WARN
        override val eventName = "bitbucket.request.failed"
        override val message = "Bitbucket request failed"

        override fun toString(): String =
            "bitbucket.request.failed(operation=$operation, repositoryId=$repositoryId, category=$category, retryable=$retryable, status=$status, durationMilliseconds=$durationMilliseconds, unexpectedFailure=<redacted>)"
    }

    data class NotificationProviderCompleted(
        val durationMilliseconds: Long,
    ) : BackendLogEvent {
        override val level = BackendLogLevel.DEBUG
        override val eventName = "notification.provider.completed"
        override val message = "Notification provider completed"
    }

    data class NotificationProviderFailed(
        val category: String,
        val ambiguous: Boolean,
        val durationMilliseconds: Long,
    ) : BackendLogEvent {
        override val level = BackendLogLevel.WARN
        override val eventName = "notification.provider.failed"
        override val message = "Notification provider failed"
    }

    data class NotificationDeliveryCompleted(
        val intentId: String,
        val attemptId: String,
        val attemptNumber: Int,
        val outcome: String,
        val retryDecision: String,
        val durationMilliseconds: Long,
    ) : BackendLogEvent {
        override val level = BackendLogLevel.INFO
        override val eventName = "notification.delivery.completed"
        override val message = "Notification delivery completed"
    }

    data class NotificationDeliveryFailed(
        val intentId: String,
        val attemptId: String,
        val attemptNumber: Int,
        val category: String,
        val ambiguous: Boolean?,
        val retryDecision: String,
        val durationMilliseconds: Long,
    ) : BackendLogEvent {
        override val level = BackendLogLevel.WARN
        override val eventName = "notification.delivery.failed"
        override val message = "Notification delivery failed"
    }

    class NotificationCleanupFailed(
        val intentId: String,
        val failure: Throwable,
    ) : BackendLogEvent {
        override val level = BackendLogLevel.ERROR
        override val eventName = "notification.cleanup.failed"
        override val message = "Notification cleanup failed"

        override fun toString(): String =
            "notification.cleanup.failed(intentId=$intentId, failure=<redacted>)"
    }

    class PersistenceTransactionFailed(
        val operation: String,
        val failure: Throwable,
    ) : BackendLogEvent {
        override val level = BackendLogLevel.ERROR
        override val eventName = "persistence.transaction.failed"
        override val message = "Persistence transaction failed"

        override fun toString(): String =
            "persistence.transaction.failed(operation=$operation, failure=<redacted>)"
    }

    class HealthProbeFailed(
        val component: String,
        val failure: Throwable,
    ) : BackendLogEvent {
        override val level = BackendLogLevel.WARN
        override val eventName = "health.probe.failed"
        override val message = "Health probe failed"

        override fun toString(): String =
            "health.probe.failed(component=$component, failure=<redacted>)"
    }
}

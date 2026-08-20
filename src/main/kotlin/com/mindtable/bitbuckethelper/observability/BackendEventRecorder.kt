package com.mindtable.bitbuckethelper.observability

import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.slf4j.event.Level
import org.slf4j.spi.LoggingEventBuilder
import java.util.LinkedHashMap
import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.message.MapMessage

fun interface MonotonicTimeSource {
    fun nanoTime(): Long

    companion object {
        val SYSTEM: MonotonicTimeSource = MonotonicTimeSource(System::nanoTime)
    }
}

fun interface BackendEventRecorder {
    fun record(event: BackendLogEvent)

    companion object {
        val NONE: BackendEventRecorder = BackendEventRecorder { }
    }
}

/** SLF4J adapter for the allowlisted backend event vocabulary. */
class Log4jBackendEventRecorder(
    private val serviceInstanceId: String,
    private val logger: Logger = LoggerFactory.getLogger("com.mindtable.bitbuckethelper"),
) : BackendEventRecorder {
    private val structuredLogger = LogManager.getLogger("com.mindtable.bitbuckethelper.structured")

    override fun record(event: BackendLogEvent) {
        // Convert before touching the logger.  No original Throwable is ever
        // supplied to an SLF4J overload or retained by a logging event.
        val diagnostic = when (event) {
            is BackendLogEvent.ServiceConfigurationFailed -> SafeExceptionDiagnostic.from(event.failure)
            is BackendLogEvent.ServiceStartFailed -> SafeExceptionDiagnostic.from(event.failure)
            is BackendLogEvent.ServiceStopFailed -> SafeExceptionDiagnostic.from(event.failure)
            is BackendLogEvent.HttpRequestFailed -> SafeExceptionDiagnostic.from(event.failure)
            is BackendLogEvent.SchedulerJobFailed -> SafeExceptionDiagnostic.from(event.failure)
            is BackendLogEvent.PersistenceTransactionFailed -> SafeExceptionDiagnostic.from(event.failure)
            is BackendLogEvent.HealthProbeFailed -> SafeExceptionDiagnostic.from(event.failure)
            is BackendLogEvent.BitbucketRequestFailed -> event.unexpectedFailure?.let(SafeExceptionDiagnostic::from)
            is BackendLogEvent.NotificationCleanupFailed -> SafeExceptionDiagnostic.from(event.failure)
            is BackendLogEvent.RefreshRepositoryUnexpected -> SafeExceptionDiagnostic.from(event.failure)
            else -> null
        }

        var builder = logger.atLevel(event.level.slf4jLevel())
            .setMessage(event.message)
            .addKeyValue("event", event.eventName)
            .addKeyValue("service_instance_id", safe(serviceInstanceId))

        builder = addEventFields(builder, event)
        if (diagnostic != null) builder = addDiagnostic(builder, diagnostic)
        builder.log()
        val structuredFields = LinkedHashMap<String, Any?>()
        structuredFields["message"] = event.message
        structuredFields["event"] = event.eventName
        structuredFields["service_instance_id"] = serviceInstanceId
        structuredFields.putAll(typedFields(event))
        if (diagnostic != null) {
            structuredFields["exception_types"] = diagnostic.exceptionTypes
            structuredFields["stack_trace"] = diagnostic.stackTrace
            structuredFields["diagnostic_truncated"] = diagnostic.truncated
        }
        val structuredMessage = TypedMapMessage()
        structuredFields.forEach { (key, value) ->
            if (value != null) structuredMessage.with(key, value)
        }
        structuredLogger.atLevel(event.level.log4jLevel()).log(structuredMessage)
    }

    private fun addEventFields(
        builder: LoggingEventBuilder,
        event: BackendLogEvent,
    ): LoggingEventBuilder = when (event) {
        is BackendLogEvent.ServiceStarting -> builder
            .addKeyValue("version", safe(event.version))
            .addKeyValue("configured_level", safe(event.configuredLevel))
        is BackendLogEvent.ServiceStarted -> builder
            .addKeyValue("browser_port", event.browserPort)
        is BackendLogEvent.ServiceStopping -> builder
            .addKeyValue("shutdown_reason", safe(event.reasonCategory))
        is BackendLogEvent.ServiceStopped -> builder
            .addKeyValue("duration_ms", event.durationMilliseconds)
        is BackendLogEvent.ServiceConfigurationFailed -> builder
            .addKeyValue("setting_code", safe(event.settingCode))
        is BackendLogEvent.ServiceStartFailed -> builder
            .addKeyValue("component", safe(event.component))
        is BackendLogEvent.ServiceStopFailed -> builder
            .addKeyValue("component", safe(event.component))
        is BackendLogEvent.HttpRequestCompleted -> builder
            .addKeyValue("request_id", safe(event.requestId))
            .addKeyValue("transport", safe(event.transport))
            .addKeyValue("method", safe(event.method))
            .addKeyValue("operation", safe(event.operation))
            .addKeyValue("status", event.status)
            .addKeyValue("outcome", safe(event.outcome))
            .addKeyValue("duration_ms", event.durationMilliseconds)
            .addKeyValue("mutation", event.mutation)
            .addOptional("refresh_run_id", event.refreshRunId)
            .addOptional("repository_id", event.repositoryId)
            .addOptional("pull_request_id", event.pullRequestId)
            .addOptional("action_item_id", event.actionItemId)
        is BackendLogEvent.HttpRequestRejected -> builder
            .addKeyValue("request_id", safe(event.requestId))
            .addKeyValue("transport", safe(event.transport))
            .addKeyValue("method", safe(event.method))
            .addKeyValue("operation", safe(event.operation))
            .addKeyValue("status", event.status)
            .addKeyValue("request_error_code", safe(event.requestErrorCode))
            .addKeyValue("duration_ms", event.durationMilliseconds)
            .addOptional("refresh_run_id", event.refreshRunId)
            .addOptional("repository_id", event.repositoryId)
            .addOptional("pull_request_id", event.pullRequestId)
            .addOptional("action_item_id", event.actionItemId)
        is BackendLogEvent.HttpRequestFailed -> builder
            .addKeyValue("request_id", safe(event.requestId))
            .addKeyValue("transport", safe(event.transport))
            .addKeyValue("method", safe(event.method))
            .addKeyValue("operation", safe(event.operation))
            .addKeyValue("status", event.status)
            .addKeyValue("duration_ms", event.durationMilliseconds)
            .addOptional("refresh_run_id", event.refreshRunId)
            .addOptional("repository_id", event.repositoryId)
            .addOptional("pull_request_id", event.pullRequestId)
            .addOptional("action_item_id", event.actionItemId)
        is BackendLogEvent.SchedulerStarted -> builder
            .addKeyValue("scheduler_state", safe(event.state))
        is BackendLogEvent.SchedulerStopped -> builder
            .addKeyValue("scheduler_state", safe(event.state))
        is BackendLogEvent.SchedulerJobStarted -> builder
            .addKeyValue("scheduler_execution_id", safe(event.schedulerExecutionId))
            .addKeyValue("job_key", safe(event.jobKey))
        is BackendLogEvent.SchedulerJobCompleted -> {
            val withFields = builder
                .addKeyValue("scheduler_execution_id", safe(event.schedulerExecutionId))
                .addKeyValue("job_key", safe(event.jobKey))
                .addKeyValue("duration_ms", event.durationMilliseconds)
            if (event.safeSummary == null) withFields
            else withFields.addKeyValue("summary", safe(event.safeSummary))
        }
        is BackendLogEvent.SchedulerJobTimedOut -> builder
            .addKeyValue("scheduler_execution_id", safe(event.schedulerExecutionId))
            .addKeyValue("job_key", safe(event.jobKey))
            .addKeyValue("duration_ms", event.durationMilliseconds)
        is BackendLogEvent.SchedulerJobInterrupted -> builder
            .addKeyValue("scheduler_execution_id", safe(event.schedulerExecutionId))
            .addKeyValue("job_key", safe(event.jobKey))
            .addKeyValue("duration_ms", event.durationMilliseconds)
        is BackendLogEvent.SchedulerJobFailed -> builder
            .addKeyValue("scheduler_execution_id", safe(event.schedulerExecutionId))
            .addKeyValue("job_key", safe(event.jobKey))
            .addKeyValue("duration_ms", event.durationMilliseconds)
        is BackendLogEvent.RefreshRunRegistered -> {
            val withFields = builder
                .addKeyValue("refresh_run_id", safe(event.refreshRunId))
                .addKeyValue("repository_count", event.repositoryCount)
                .addKeyValue("started_count", event.startedCount)
                .addKeyValue("joined_count", event.joinedCount)
                .addKeyValue("deferred_count", event.deferredCount)
                .addKeyValue("not_configured_count", event.notConfiguredCount)
            if (event.requestId == null) withFields
            else withFields.addKeyValue("request_id", safe(event.requestId))
        }
        is BackendLogEvent.RefreshRepositoryCompleted -> builder
            .addOptional("refresh_run_id", event.refreshRunId)
            .addKeyValue("repository_id", safe(event.repositoryId))
            .addKeyValue("outcome", safe(event.outcome))
            .addKeyValue("duration_ms", event.durationMilliseconds)
            .addOptional("failure_category", event.failureCategory)
            .addOptional("retryable", event.retryable)
            .addOptional("retry_at", event.retryAt)
        is BackendLogEvent.RefreshRepositoryPartial -> builder
            .addOptional("refresh_run_id", event.refreshRunId)
            .addKeyValue("repository_id", safe(event.repositoryId))
            .addKeyValue("failure_category", safe(event.failureCategory))
            .addOptional("retryable", event.retryable)
            .addKeyValue("duration_ms", event.durationMilliseconds)
        is BackendLogEvent.RefreshRepositoryFailed -> builder
            .addOptional("refresh_run_id", event.refreshRunId)
            .addKeyValue("repository_id", safe(event.repositoryId))
            .addKeyValue("failure_category", safe(event.failureCategory))
            .addOptional("retryable", event.retryable)
            .addOptional("retry_at", event.retryAt)
            .addKeyValue("duration_ms", event.durationMilliseconds)
        is BackendLogEvent.RefreshRepositoryDeferred -> builder
            .addOptional("refresh_run_id", event.refreshRunId)
            .addKeyValue("repository_id", safe(event.repositoryId))
            .addOptional("retry_at", event.retryAt)
            .addKeyValue("duration_ms", event.durationMilliseconds)
        is BackendLogEvent.RefreshRepositoryUnexpected -> builder
            .addOptional("refresh_run_id", event.refreshRunId)
            .addKeyValue("repository_id", safe(event.repositoryId))
            .addKeyValue("duration_ms", event.durationMilliseconds)
        is BackendLogEvent.BitbucketRequestCompleted -> builder
            .addKeyValue("operation", safe(event.operation))
            .addOptional("repository_id", event.repositoryId)
            .addOptional("status", event.status)
            .addKeyValue("duration_ms", event.durationMilliseconds)
        is BackendLogEvent.BitbucketRequestFailed -> builder
            .addKeyValue("operation", safe(event.operation))
            .addOptional("repository_id", event.repositoryId)
            .addKeyValue("failure_category", safe(event.category))
            .addOptional("retryable", event.retryable)
            .addOptional("status", event.status)
            .addKeyValue("duration_ms", event.durationMilliseconds)
        is BackendLogEvent.NotificationProviderCompleted -> builder
            .addKeyValue("duration_ms", event.durationMilliseconds)
        is BackendLogEvent.NotificationProviderFailed -> builder
            .addKeyValue("failure_category", safe(event.category))
            .addKeyValue("ambiguous", event.ambiguous)
            .addKeyValue("duration_ms", event.durationMilliseconds)
        is BackendLogEvent.NotificationDeliveryCompleted -> builder
            .addKeyValue("notification_intent_id", safe(event.intentId))
            .addKeyValue("notification_attempt_id", safe(event.attemptId))
            .addKeyValue("attempt_number", event.attemptNumber)
            .addKeyValue("outcome", safe(event.outcome))
            .addKeyValue("retry_decision", safe(event.retryDecision))
            .addKeyValue("duration_ms", event.durationMilliseconds)
        is BackendLogEvent.NotificationDeliveryFailed -> builder
            .addKeyValue("notification_intent_id", safe(event.intentId))
            .addKeyValue("notification_attempt_id", safe(event.attemptId))
            .addKeyValue("attempt_number", event.attemptNumber)
            .addKeyValue("failure_category", safe(event.category))
            .addOptional("ambiguous", event.ambiguous)
            .addKeyValue("retry_decision", safe(event.retryDecision))
            .addKeyValue("duration_ms", event.durationMilliseconds)
        is BackendLogEvent.NotificationCleanupFailed -> builder
            .addKeyValue("notification_intent_id", safe(event.intentId))
        is BackendLogEvent.PersistenceTransactionFailed -> builder
            .addKeyValue("operation", safe(event.operation))
        is BackendLogEvent.HealthProbeFailed -> builder
            .addKeyValue("component", safe(event.component))
    }

    private fun addDiagnostic(
        builder: LoggingEventBuilder,
        diagnostic: SafeExceptionDiagnostic,
    ): LoggingEventBuilder = builder
        .addKeyValue("exception_types", diagnostic.exceptionTypes.map(::safe))
        .addKeyValue("stack_trace", safe(diagnostic.stackTrace))
        .addKeyValue("diagnostic_truncated", diagnostic.truncated)

    private fun typedFields(event: BackendLogEvent): Map<String, Any?> = when (event) {
        is BackendLogEvent.ServiceStarting -> mapOf(
            "version" to event.version,
            "configured_level" to event.configuredLevel,
        )
        is BackendLogEvent.ServiceStarted -> mapOf("browser_port" to event.browserPort)
        is BackendLogEvent.ServiceStopping -> mapOf("shutdown_reason" to event.reasonCategory)
        is BackendLogEvent.ServiceStopped -> mapOf("duration_ms" to event.durationMilliseconds)
        is BackendLogEvent.ServiceConfigurationFailed -> mapOf("setting_code" to event.settingCode)
        is BackendLogEvent.ServiceStartFailed -> mapOf("component" to event.component)
        is BackendLogEvent.ServiceStopFailed -> mapOf("component" to event.component)
        is BackendLogEvent.HttpRequestCompleted -> mapOf(
            "request_id" to event.requestId,
            "transport" to event.transport,
            "method" to event.method,
            "operation" to event.operation,
            "status" to event.status,
            "outcome" to event.outcome,
            "duration_ms" to event.durationMilliseconds,
            "mutation" to event.mutation,
            "refresh_run_id" to event.refreshRunId,
            "repository_id" to event.repositoryId,
            "pull_request_id" to event.pullRequestId,
            "action_item_id" to event.actionItemId,
        )
        is BackendLogEvent.HttpRequestRejected -> mapOf(
            "request_id" to event.requestId,
            "transport" to event.transport,
            "method" to event.method,
            "operation" to event.operation,
            "status" to event.status,
            "request_error_code" to event.requestErrorCode,
            "duration_ms" to event.durationMilliseconds,
            "refresh_run_id" to event.refreshRunId,
            "repository_id" to event.repositoryId,
            "pull_request_id" to event.pullRequestId,
            "action_item_id" to event.actionItemId,
        )
        is BackendLogEvent.HttpRequestFailed -> mapOf(
            "request_id" to event.requestId,
            "transport" to event.transport,
            "method" to event.method,
            "operation" to event.operation,
            "status" to event.status,
            "duration_ms" to event.durationMilliseconds,
            "refresh_run_id" to event.refreshRunId,
            "repository_id" to event.repositoryId,
            "pull_request_id" to event.pullRequestId,
            "action_item_id" to event.actionItemId,
        )
        is BackendLogEvent.SchedulerStarted -> mapOf("scheduler_state" to event.state)
        is BackendLogEvent.SchedulerStopped -> mapOf("scheduler_state" to event.state)
        is BackendLogEvent.SchedulerJobStarted -> mapOf(
            "scheduler_execution_id" to event.schedulerExecutionId,
            "job_key" to event.jobKey,
        )
        is BackendLogEvent.SchedulerJobCompleted -> mapOf(
            "scheduler_execution_id" to event.schedulerExecutionId,
            "job_key" to event.jobKey,
            "duration_ms" to event.durationMilliseconds,
            "summary" to event.safeSummary,
        )
        is BackendLogEvent.SchedulerJobTimedOut -> mapOf(
            "scheduler_execution_id" to event.schedulerExecutionId,
            "job_key" to event.jobKey,
            "duration_ms" to event.durationMilliseconds,
        )
        is BackendLogEvent.SchedulerJobInterrupted -> mapOf(
            "scheduler_execution_id" to event.schedulerExecutionId,
            "job_key" to event.jobKey,
            "duration_ms" to event.durationMilliseconds,
        )
        is BackendLogEvent.SchedulerJobFailed -> mapOf(
            "scheduler_execution_id" to event.schedulerExecutionId,
            "job_key" to event.jobKey,
            "duration_ms" to event.durationMilliseconds,
        )
        is BackendLogEvent.RefreshRunRegistered -> mapOf(
            "refresh_run_id" to event.refreshRunId,
            "repository_count" to event.repositoryCount,
            "started_count" to event.startedCount,
            "joined_count" to event.joinedCount,
            "deferred_count" to event.deferredCount,
            "not_configured_count" to event.notConfiguredCount,
            "request_id" to event.requestId,
        )
        is BackendLogEvent.RefreshRepositoryCompleted -> mapOf(
            "refresh_run_id" to event.refreshRunId,
            "repository_id" to event.repositoryId,
            "outcome" to event.outcome,
            "duration_ms" to event.durationMilliseconds,
            "failure_category" to event.failureCategory,
            "retryable" to event.retryable,
            "retry_at" to event.retryAt,
        )
        is BackendLogEvent.RefreshRepositoryPartial -> mapOf(
            "refresh_run_id" to event.refreshRunId,
            "repository_id" to event.repositoryId,
            "failure_category" to event.failureCategory,
            "retryable" to event.retryable,
            "duration_ms" to event.durationMilliseconds,
        )
        is BackendLogEvent.RefreshRepositoryFailed -> mapOf(
            "refresh_run_id" to event.refreshRunId,
            "repository_id" to event.repositoryId,
            "failure_category" to event.failureCategory,
            "retryable" to event.retryable,
            "retry_at" to event.retryAt,
            "duration_ms" to event.durationMilliseconds,
        )
        is BackendLogEvent.RefreshRepositoryDeferred -> mapOf(
            "refresh_run_id" to event.refreshRunId,
            "repository_id" to event.repositoryId,
            "retry_at" to event.retryAt,
            "duration_ms" to event.durationMilliseconds,
        )
        is BackendLogEvent.RefreshRepositoryUnexpected -> mapOf(
            "refresh_run_id" to event.refreshRunId,
            "repository_id" to event.repositoryId,
            "duration_ms" to event.durationMilliseconds,
        )
        is BackendLogEvent.BitbucketRequestCompleted -> mapOf(
            "operation" to event.operation,
            "repository_id" to event.repositoryId,
            "status" to event.status,
            "duration_ms" to event.durationMilliseconds,
        )
        is BackendLogEvent.BitbucketRequestFailed -> mapOf(
            "operation" to event.operation,
            "repository_id" to event.repositoryId,
            "failure_category" to event.category,
            "retryable" to event.retryable,
            "status" to event.status,
            "duration_ms" to event.durationMilliseconds,
        )
        is BackendLogEvent.NotificationProviderCompleted -> mapOf("duration_ms" to event.durationMilliseconds)
        is BackendLogEvent.NotificationProviderFailed -> mapOf(
            "failure_category" to event.category,
            "ambiguous" to event.ambiguous,
            "duration_ms" to event.durationMilliseconds,
        )
        is BackendLogEvent.NotificationDeliveryCompleted -> mapOf(
            "notification_intent_id" to event.intentId,
            "notification_attempt_id" to event.attemptId,
            "attempt_number" to event.attemptNumber,
            "outcome" to event.outcome,
            "retry_decision" to event.retryDecision,
            "duration_ms" to event.durationMilliseconds,
        )
        is BackendLogEvent.NotificationDeliveryFailed -> mapOf(
            "notification_intent_id" to event.intentId,
            "notification_attempt_id" to event.attemptId,
            "attempt_number" to event.attemptNumber,
            "failure_category" to event.category,
            "ambiguous" to event.ambiguous,
            "retry_decision" to event.retryDecision,
            "duration_ms" to event.durationMilliseconds,
        )
        is BackendLogEvent.NotificationCleanupFailed -> mapOf("notification_intent_id" to event.intentId)
        is BackendLogEvent.PersistenceTransactionFailed -> mapOf("operation" to event.operation)
        is BackendLogEvent.HealthProbeFailed -> mapOf("component" to event.component)
    }

    private fun safe(value: String): String = escapeTerminalValue(value)

    private fun LoggingEventBuilder.addOptional(key: String, value: Any?): LoggingEventBuilder =
        if (value == null) this else addKeyValue(key, if (value is String) safe(value) else value)

    private fun BackendLogLevel.slf4jLevel(): Level = when (this) {
        BackendLogLevel.TRACE -> Level.TRACE
        BackendLogLevel.DEBUG -> Level.DEBUG
        BackendLogLevel.INFO -> Level.INFO
        BackendLogLevel.WARN -> Level.WARN
        BackendLogLevel.ERROR -> Level.ERROR
    }

    private fun BackendLogLevel.log4jLevel() = when (this) {
        BackendLogLevel.TRACE -> org.apache.logging.log4j.Level.TRACE
        BackendLogLevel.DEBUG -> org.apache.logging.log4j.Level.DEBUG
        BackendLogLevel.INFO -> org.apache.logging.log4j.Level.INFO
        BackendLogLevel.WARN -> org.apache.logging.log4j.Level.WARN
        BackendLogLevel.ERROR -> org.apache.logging.log4j.Level.ERROR
    }
}

private class TypedMapMessage : MapMessage<TypedMapMessage, Any?>()

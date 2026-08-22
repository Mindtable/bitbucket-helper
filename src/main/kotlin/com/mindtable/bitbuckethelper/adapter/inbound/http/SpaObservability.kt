package com.mindtable.bitbuckethelper.adapter.inbound.http

import com.mindtable.bitbuckethelper.observability.BackendEventRecorder
import com.mindtable.bitbuckethelper.observability.BackendLogEvent
import com.mindtable.bitbuckethelper.observability.MonotonicTimeSource
import com.mindtable.bitbuckethelper.observability.reportBackendEventRecorderFailure
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.hooks.ResponseSent
import io.ktor.server.request.httpMethod
import io.ktor.server.request.path
import io.ktor.util.AttributeKey

internal enum class SpaOperation(val wireName: String) {
    SHELL("spa_shell"),
    ASSET("spa_asset"),
    UNKNOWN("spa_unknown"),
}

private data class SpaObservation(
    val startNanos: Long,
    val method: String,
    val operation: SpaOperation,
    val requestErrorCode: String? = null,
    val failure: Throwable? = null,
    val terminalRecorded: Boolean = false,
)

private val SpaObservationAttribute = AttributeKey<SpaObservation>("spa-observation")

internal fun Application.installSpaObservability(
    backendEventRecorder: BackendEventRecorder,
    monotonicTimeSource: MonotonicTimeSource,
) {
    intercept(ApplicationCallPipeline.Setup) {
        if (!context.isApiV1Call()) {
            context.assignLocalRequestId()
            context.response.headers.append(HttpHeaders.XRequestId, context.localRequestId())
            context.attributes.put(
                SpaObservationAttribute,
                SpaObservation(
                    startNanos = monotonicTimeSource.nanoTime(),
                    method = fixedHttpMethod(context.request.httpMethod),
                    operation = inferSpaOperation(context.request.path()),
                ),
            )
        }
    }

    ResponseSent.install(this) { call ->
        call.finishSpaObservation(backendEventRecorder, monotonicTimeSource.nanoTime())
    }
}

internal fun ApplicationCall.observeSpaRequestError(requestErrorCode: String) {
    updateSpaObservation { copy(requestErrorCode = requestErrorCode) }
}

internal fun ApplicationCall.observeSpaFailure(failure: Throwable) {
    updateSpaObservation { copy(failure = failure, requestErrorCode = null) }
}

internal fun ApplicationCall.hasSpaObservation(): Boolean = attributes.contains(SpaObservationAttribute)

internal fun ApplicationCall.isSuccessfulSpaAssetObservation(): Boolean {
    if (!attributes.contains(SpaObservationAttribute)) return false
    val observation = attributes[SpaObservationAttribute]
    return observation.operation == SpaOperation.ASSET &&
        observation.requestErrorCode == null &&
        observation.failure == null
}

private fun ApplicationCall.updateSpaObservation(update: SpaObservation.() -> SpaObservation) {
    if (!attributes.contains(SpaObservationAttribute)) return
    attributes.put(SpaObservationAttribute, update(attributes[SpaObservationAttribute]))
}

private fun ApplicationCall.finishSpaObservation(
    backendEventRecorder: BackendEventRecorder,
    endNanos: Long,
) {
    if (!attributes.contains(SpaObservationAttribute)) return
    val observation = attributes[SpaObservationAttribute]
    if (observation.terminalRecorded) return
    attributes.put(SpaObservationAttribute, observation.copy(terminalRecorded = true))

    val status = response.status()?.value ?: 500
    val durationMilliseconds =
        ((endNanos - observation.startNanos).coerceAtLeast(0L)) / NANOS_PER_MILLISECOND
    val event = when {
        observation.failure != null -> BackendLogEvent.HttpRequestFailed(
            requestId = localRequestId(),
            transport = BROWSER_TRANSPORT,
            method = observation.method,
            operation = observation.operation.wireName,
            status = status,
            durationMilliseconds = durationMilliseconds,
            failure = observation.failure,
        )
        observation.requestErrorCode != null || status >= 400 -> BackendLogEvent.HttpRequestRejected(
            requestId = localRequestId(),
            transport = BROWSER_TRANSPORT,
            method = observation.method,
            operation = observation.operation.wireName,
            status = status,
            requestErrorCode = observation.requestErrorCode ?: fallbackSpaRequestErrorCode(status),
            durationMilliseconds = durationMilliseconds,
        )
        else -> BackendLogEvent.HttpRequestCompleted(
            requestId = localRequestId(),
            transport = BROWSER_TRANSPORT,
            method = observation.method,
            operation = observation.operation.wireName,
            status = status,
            outcome = SPA_SERVED_OUTCOME,
            durationMilliseconds = durationMilliseconds,
            mutation = false,
        )
    }
    try {
        backendEventRecorder.record(event)
    } catch (_: Throwable) {
        reportBackendEventRecorderFailure()
    }
}

private fun inferSpaOperation(path: String): SpaOperation {
    val requestPath = path.substringBefore('?')
    return when {
        requestPath.isEmpty() || requestPath == "/" || requestPath == "/index.html" -> SpaOperation.SHELL
        requestPath == "/assets" || requestPath.startsWith("/assets/") -> SpaOperation.ASSET
        else -> SpaOperation.UNKNOWN
    }
}

private fun fixedHttpMethod(method: HttpMethod): String = when (method) {
    HttpMethod.Get -> "GET"
    HttpMethod.Head -> "HEAD"
    HttpMethod.Post -> "POST"
    HttpMethod.Put -> "PUT"
    HttpMethod.Delete -> "DELETE"
    HttpMethod.Patch -> "PATCH"
    HttpMethod.Options -> "OPTIONS"
    else -> "OTHER"
}

private fun fallbackSpaRequestErrorCode(status: Int): String = when (status) {
    403 -> "FORBIDDEN"
    404 -> "ROUTE_NOT_FOUND"
    405 -> "METHOD_NOT_ALLOWED"
    500 -> "INTERNAL_SERVER_ERROR"
    else -> "INVALID_REQUEST"
}

private const val BROWSER_TRANSPORT = "browser"
private const val SPA_SERVED_OUTCOME = "spa_served"
private const val NANOS_PER_MILLISECOND = 1_000_000L

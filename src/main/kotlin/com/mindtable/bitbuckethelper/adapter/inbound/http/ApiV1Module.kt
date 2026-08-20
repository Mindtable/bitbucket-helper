package com.mindtable.bitbuckethelper.adapter.inbound.http

import com.mindtable.bitbuckethelper.observability.BackendEventRecorder
import com.mindtable.bitbuckethelper.observability.MonotonicTimeSource
import io.ktor.http.HttpHeaders
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.path
import io.ktor.server.routing.Route
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import kotlinx.serialization.json.Json

enum class TransportKind {
    BROWSER,
    UNIX,
}

fun Application.installApiV1(
    transportKind: TransportKind,
    backendEventRecorder: BackendEventRecorder = BackendEventRecorder.NONE,
    monotonicTimeSource: MonotonicTimeSource = MonotonicTimeSource.SYSTEM,
    installRoutes: Route.(TransportKind) -> Unit = {},
) {
    intercept(ApplicationCallPipeline.Setup) {
        if (context.isApiV1Call()) {
            context.assignApiV1RequestId()
            context.response.headers.append(HttpHeaders.CacheControl, "no-store")
        }
    }
    installApiV1Observability(transportKind, backendEventRecorder, monotonicTimeSource)
    install(ContentNegotiation) {
        json(
            Json {
                encodeDefaults = true
                explicitNulls = true
                ignoreUnknownKeys = false
                classDiscriminator = "type"
            },
        )
    }
    install(StatusPages) {
        installApiV1ErrorHandling()
    }
    routing {
        route(API_V1_PREFIX) {
            installRoutes(transportKind)
        }
    }
}

internal fun io.ktor.server.application.ApplicationCall.isApiV1Call(): Boolean {
    val path = request.path()
    return path == API_V1_PREFIX || path.startsWith("$API_V1_PREFIX/")
}

internal const val API_V1_PREFIX = "/api/v1"

package com.mindtable.bitbuckethelper.adapter.inbound.http

import com.mindtable.bitbuckethelper.observability.BackendEventRecorder
import com.mindtable.bitbuckethelper.observability.MonotonicTimeSource
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.call
import io.ktor.server.response.ApplicationSendPipeline
import io.ktor.server.response.appendIfAbsent
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.routing.get
import io.ktor.server.routing.head
import io.ktor.server.routing.routing
import java.util.concurrent.CancellationException

internal fun Application.installSpa(
    assets: SpaAssets,
    backendEventRecorder: BackendEventRecorder,
    monotonicTimeSource: MonotonicTimeSource = MonotonicTimeSource.SYSTEM,
) {
    installSpaObservability(backendEventRecorder, monotonicTimeSource)
    installSpaResponsePolicy()
    routing {
        get("/") { call.serveSpaAsset(assets, "/", headOnly = false) }
        head("/") { call.serveSpaAsset(assets, "/", headOnly = true) }
        get("/index.html") { call.serveSpaAsset(assets, "index.html", headOnly = false) }
        head("/index.html") { call.serveSpaAsset(assets, "index.html", headOnly = true) }
        get("/assets/{path...}") { call.serveSpaPathAsset(assets, headOnly = false) }
        head("/assets/{path...}") { call.serveSpaPathAsset(assets, headOnly = true) }
    }
}

private fun Application.installSpaResponsePolicy() {
    intercept(ApplicationCallPipeline.Setup) {
        if (!call.isApiV1Call()) {
            SPA_SECURITY_HEADERS.forEach { (name, value) -> call.response.headers.append(name, value) }
        }
    }
    sendPipeline.intercept(ApplicationSendPipeline.After) {
        if (call.hasSpaObservation()) {
            val outgoingStatus = (subject as? OutgoingContent)?.status
                ?: call.response.status()
                ?: HttpStatusCode.OK
            val cacheControl = if (
                outgoingStatus.value in 200..299 && call.isSuccessfulSpaAssetObservation()
            ) {
                IMMUTABLE_CACHE
            } else {
                NO_STORE
            }
            call.response.headers.appendIfAbsent(HttpHeaders.CacheControl, cacheControl)
        }
    }
}

private suspend fun ApplicationCall.serveSpaPathAsset(assets: SpaAssets, headOnly: Boolean) {
    val path = parameters.getAll("path")?.joinToString("/").orEmpty()
    serveSpaAsset(assets, "assets/$path", headOnly)
}

private suspend fun ApplicationCall.serveSpaAsset(
    assets: SpaAssets,
    relativePath: String,
    headOnly: Boolean,
) {
    try {
        val asset = assets.find(relativePath)
        if (asset == null) {
            observeSpaRequestError("ROUTE_NOT_FOUND")
            respond(HttpStatusCode.NotFound)
            return
        }
        respondSpaAsset(asset, headOnly)
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (failure: Throwable) {
        observeSpaFailure(failure)
        respond(HttpStatusCode.InternalServerError)
    }
}

private suspend fun ApplicationCall.respondSpaAsset(asset: SpaAsset, headOnly: Boolean) {
    if (headOnly) {
        response.header(HttpHeaders.ContentType, asset.contentType.toString())
        response.header(HttpHeaders.ContentLength, asset.bytes.size)
        respond(HttpStatusCode.OK)
    } else {
        respondBytes(asset.bytes, asset.contentType)
    }
}

private const val NO_STORE = "no-store"
private const val IMMUTABLE_CACHE = "public, max-age=31536000, immutable"
private const val CONTENT_SECURITY_POLICY =
    "default-src 'none'; script-src 'self'; style-src 'self'; img-src 'self'; font-src 'self'; connect-src 'self'; object-src 'none'; base-uri 'none'; form-action 'none'; frame-ancestors 'none'"
private val SPA_SECURITY_HEADERS = listOf(
    "Content-Security-Policy" to CONTENT_SECURITY_POLICY,
    "X-Content-Type-Options" to "nosniff",
    "Referrer-Policy" to "no-referrer",
    "Cross-Origin-Opener-Policy" to "same-origin",
    "Cross-Origin-Resource-Policy" to "same-origin",
    "X-Frame-Options" to "DENY",
    "Permissions-Policy" to "camera=(), geolocation=(), microphone=()",
)

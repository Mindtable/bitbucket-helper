package com.mindtable.bitbuckethelper.adapter.inbound.http

import com.mindtable.bitbuckethelper.generated.api.v1.model.ApiVersion
import com.mindtable.bitbuckethelper.generated.api.v1.model.BrowserSessionResponse
import com.mindtable.bitbuckethelper.generated.api.v1.model.BrowserSessionResult
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.call
import io.ktor.server.request.contentType
import io.ktor.server.request.httpMethod
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import java.security.SecureRandom
import java.util.Base64

class BrowserSecurity(
    private val resolvedPort: () -> Int,
    val serviceInstanceId: String = newServiceInstanceId(),
) {
    val csrfToken: String = randomUrlSafeValue(CSRF_BYTES)

    internal fun authorize(call: ApplicationCall) {
        val apiCall = call.isApiV1Call()
        val authority = "127.0.0.1:${resolvedPort()}"
        if (!call.request.headers.hasExactSingleValue(HttpHeaders.Host, authority)) {
            if (apiCall) throw ForbiddenApiRequestException()
            throw ForbiddenBrowserRequestException()
        }

        val expectedOrigin = "http://$authority"
        val origins = call.request.headers.getAll(HttpHeaders.Origin)
        if (!apiCall) {
            if (
                call.request.httpMethod in setOf(HttpMethod.Get, HttpMethod.Head) &&
                origins != null &&
                (origins.size != 1 || origins.single() != expectedOrigin)
            ) {
                throw ForbiddenBrowserRequestException()
            }
            return
        }

        if (call.request.httpMethod == HttpMethod.Get) {
            if (origins != null && (origins.size != 1 || origins.single() != expectedOrigin)) {
                throw ForbiddenApiRequestException()
            }
            return
        }

        if (origins?.singleOrNull() != expectedOrigin) {
            throw ForbiddenApiRequestException()
        }
        if (
            call.request.httpMethod in setOf(HttpMethod.Post, HttpMethod.Put) &&
            call.request.contentType().withoutParameters() != ContentType.Application.Json
        ) {
            throw UnsupportedApiContentTypeException()
        }
        if (!call.request.headers.hasExactSingleValue(CSRF_HEADER, csrfToken)) {
            throw ForbiddenApiRequestException()
        }
    }

    private companion object {
        const val CSRF_HEADER = "X-CSRF-Token"
        const val CSRF_BYTES = 32
        const val SERVICE_ID_BYTES = 24
        val secureRandom = SecureRandom()

        fun randomUrlSafeValue(byteCount: Int): String {
            val bytes = ByteArray(byteCount)
            secureRandom.nextBytes(bytes)
            return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
        }

        private fun newServiceInstanceId(): String = "svc_${randomUrlSafeValue(SERVICE_ID_BYTES)}"
    }
}

fun Application.installBrowserSecurity(security: BrowserSecurity) {
    intercept(ApplicationCallPipeline.Plugins) {
        security.authorize(call)
    }
}

fun Route.installBrowserSessionRoute(security: BrowserSecurity) {
    get("/browser-session") {
        call.observeApiOperation(ApiOperation.BROWSER_SESSION)
        call.respondApiV1(ApiOutcome.BROWSER_SESSION) { requestId ->
            BrowserSessionResponse(
                apiVersion = ApiVersion._1,
                requestId = requestId,
                result = BrowserSessionResult(
                    type = BrowserSessionResult.Type.browserSession,
                    csrfToken = security.csrfToken,
                    serviceInstanceId = security.serviceInstanceId,
                ),
            )
        }
    }
}

private fun io.ktor.http.Headers.hasExactSingleValue(name: String, expected: String): Boolean =
    getAll(name)?.singleOrNull() == expected

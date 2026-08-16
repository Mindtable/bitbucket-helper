package com.mindtable.bitbuckethelper.adapter.inbound.http

import com.mindtable.bitbuckethelper.generated.api.v1.model.ApiVersion
import com.mindtable.bitbuckethelper.generated.api.v1.model.RequestError
import com.mindtable.bitbuckethelper.generated.api.v1.model.RequestErrorCode
import com.mindtable.bitbuckethelper.generated.api.v1.model.RequestErrorEnvelope
import com.mindtable.bitbuckethelper.generated.api.v1.model.RequestViolation
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.plugins.ContentTransformationException
import io.ktor.server.plugins.UnsupportedMediaTypeException
import io.ktor.server.plugins.statuspages.StatusPagesConfig
import io.ktor.server.plugins.statuspages.exception
import io.ktor.server.request.contentType
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import java.util.concurrent.CancellationException

class InvalidApiRequestException(
    val violations: List<RequestViolation> = emptyList(),
) : RuntimeException(INVALID_REQUEST_MESSAGE)

class ForbiddenApiRequestException : RuntimeException(FORBIDDEN_MESSAGE)

@PublishedApi
internal class UnsupportedApiContentTypeException : RuntimeException(UNSUPPORTED_CONTENT_TYPE_MESSAGE)

suspend inline fun <reified T : Any> ApplicationCall.receiveApiV1(): T {
    if (request.contentType().withoutParameters() != ContentType.Application.Json) {
        throw UnsupportedApiContentTypeException()
    }
    return receive()
}

internal fun StatusPagesConfig.installApiV1ErrorHandling() {
    exception<CancellationException> { _, cause -> throw cause }
    exception<InvalidApiRequestException> { call, cause ->
        call.respondApiV1Error(
            status = HttpStatusCode.BadRequest,
            code = RequestErrorCode.INVALID_REQUEST,
            message = INVALID_REQUEST_MESSAGE,
            violations = cause.violations,
        )
    }
    exception<ForbiddenApiRequestException> { call, _ ->
        call.respondApiV1Error(
            status = HttpStatusCode.Forbidden,
            code = RequestErrorCode.FORBIDDEN,
            message = FORBIDDEN_MESSAGE,
        )
    }
    exception<UnsupportedApiContentTypeException> { call, _ ->
        call.respondApiV1Error(
            status = HttpStatusCode.UnsupportedMediaType,
            code = RequestErrorCode.UNSUPPORTED_CONTENT_TYPE,
            message = UNSUPPORTED_CONTENT_TYPE_MESSAGE,
        )
    }
    exception<UnsupportedMediaTypeException> { call, _ ->
        call.respondApiV1Error(
            status = HttpStatusCode.UnsupportedMediaType,
            code = RequestErrorCode.UNSUPPORTED_CONTENT_TYPE,
            message = UNSUPPORTED_CONTENT_TYPE_MESSAGE,
        )
    }
    exception<BadRequestException> { call, _ -> call.respondInvalidRequest() }
    exception<ContentTransformationException> { call, _ -> call.respondInvalidRequest() }
    exception<Exception> { call, _ ->
        call.respondApiV1Error(
            status = HttpStatusCode.InternalServerError,
            code = RequestErrorCode.INTERNAL_SERVER_ERROR,
            message = INTERNAL_SERVER_ERROR_MESSAGE,
        )
    }
    status(HttpStatusCode.NotFound) { call, status ->
        if (call.isApiV1Call()) {
            call.respondApiV1Error(
                status = status,
                code = RequestErrorCode.ROUTE_NOT_FOUND,
                message = ROUTE_NOT_FOUND_MESSAGE,
            )
        } else {
            call.respond(status)
        }
    }
    status(HttpStatusCode.MethodNotAllowed) { call, status ->
        if (call.isApiV1Call()) {
            call.respondApiV1Error(
                status = status,
                code = RequestErrorCode.METHOD_NOT_ALLOWED,
                message = METHOD_NOT_ALLOWED_MESSAGE,
            )
        } else {
            call.respond(status)
        }
    }
}

private suspend fun ApplicationCall.respondInvalidRequest() {
    respondApiV1Error(
        status = HttpStatusCode.BadRequest,
        code = RequestErrorCode.INVALID_REQUEST,
        message = INVALID_REQUEST_MESSAGE,
    )
}

private suspend fun ApplicationCall.respondApiV1Error(
    status: HttpStatusCode,
    code: RequestErrorCode,
    message: String,
    violations: List<RequestViolation> = emptyList(),
) {
    respond(
        status,
        RequestErrorEnvelope(
            apiVersion = ApiVersion._1,
            requestId = apiV1RequestId(),
            error = RequestError(
                code = code,
                message = message,
                violations = violations,
            ),
        ),
    )
}

internal const val INVALID_REQUEST_MESSAGE = "The request could not be parsed or validated."
internal const val FORBIDDEN_MESSAGE = "The request is not authorized for this transport."
internal const val ROUTE_NOT_FOUND_MESSAGE = "The requested API route does not exist."
internal const val METHOD_NOT_ALLOWED_MESSAGE = "The requested API route does not support this method."
internal const val UNSUPPORTED_CONTENT_TYPE_MESSAGE = "The request content type must be application/json."
internal const val INTERNAL_SERVER_ERROR_MESSAGE = "The server could not process the request."

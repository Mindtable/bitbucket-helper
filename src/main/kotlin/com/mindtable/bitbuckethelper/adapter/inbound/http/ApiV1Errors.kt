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

internal class InvalidApiRequestException(
    violations: List<ApiRequestViolation> = emptyList(),
) : RuntimeException(INVALID_REQUEST_MESSAGE) {
    val violations: List<ApiRequestViolation> = violations.take(MAX_API_REQUEST_VIOLATIONS)
}

class ForbiddenApiRequestException : RuntimeException(FORBIDDEN_MESSAGE)

internal enum class ApiRequestViolation(
    private val field: String,
    private val code: String,
    private val message: String,
) {
    INVALID_AFTER_REVISION(
        field = "afterRevision",
        code = "INVALID_IDENTIFIER",
        message = "must be a dashboard revision identifier",
    ),
    INVALID_PULL_REQUEST_ID(
        field = "pullRequestId",
        code = "INVALID_IDENTIFIER",
        message = "must be a pull request identifier",
    ),
    INVALID_ACTION_ITEM_ID(
        field = "actionItemId",
        code = "INVALID_IDENTIFIER",
        message = "must be an action item identifier",
    ),
    MISSING_ACTIVITY_VERSION(
        field = "activityVersion",
        code = "REQUIRED",
        message = "is required",
    ),
    INVALID_ACTIVITY_VERSION(
        field = "activityVersion",
        code = "INVALID_IDENTIFIER",
        message = "must be an activity version identifier",
    ),
    UNSUPPORTED_API_VERSION(
        field = "apiVersion",
        code = "UNSUPPORTED_VERSION",
        message = "must be API version 1",
    ),
    EMPTY_REFRESH_REPOSITORY_IDS(
        field = "target.repositoryIds",
        code = "EMPTY_COLLECTION",
        message = "must contain at least one repository identifier",
    ),
    INVALID_REFRESH_REPOSITORY_ID(
        field = "target.repositoryIds",
        code = "INVALID_IDENTIFIER",
        message = "must contain only repository identifiers",
    ),
    INVALID_REFRESH_RUN_ID(
        field = "refreshRunId",
        code = "INVALID_IDENTIFIER",
        message = "must be a refresh run identifier",
    ),
    INVALID_BITBUCKET_API_BASE_URL(
        field = "bitbucketApiBaseUrl",
        code = "INVALID_URL",
        message = "must be a canonical HTTPS URL ending in /2.0",
    ),
    INVALID_WORKSPACE_SLUG(
        field = "workspaceSlug",
        code = "INVALID_SLUG",
        message = "must be a valid workspace slug",
    ),
    INVALID_REPOSITORY_SLUG(
        field = "repositorySlug",
        code = "INVALID_SLUG",
        message = "must be a valid repository slug",
    ),
    INVALID_REPOSITORY_ID(
        field = "repositoryId",
        code = "INVALID_IDENTIFIER",
        message = "must be a repository identifier",
    ),
    ;

    fun toGenerated(): RequestViolation = RequestViolation(
        field = field,
        code = code,
        message = message,
    )
}

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
        call.rethrowOutsideApiV1(cause)
        call.respondApiV1Error(
            status = HttpStatusCode.BadRequest,
            code = RequestErrorCode.INVALID_REQUEST,
            message = INVALID_REQUEST_MESSAGE,
            violations = cause.violations,
        )
    }
    exception<ForbiddenApiRequestException> { call, cause ->
        call.rethrowOutsideApiV1(cause)
        call.respondApiV1Error(
            status = HttpStatusCode.Forbidden,
            code = RequestErrorCode.FORBIDDEN,
            message = FORBIDDEN_MESSAGE,
        )
    }
    exception<UnsupportedApiContentTypeException> { call, cause ->
        call.rethrowOutsideApiV1(cause)
        call.respondApiV1Error(
            status = HttpStatusCode.UnsupportedMediaType,
            code = RequestErrorCode.UNSUPPORTED_CONTENT_TYPE,
            message = UNSUPPORTED_CONTENT_TYPE_MESSAGE,
        )
    }
    exception<UnsupportedMediaTypeException> { call, cause ->
        call.rethrowOutsideApiV1(cause)
        call.respondApiV1Error(
            status = HttpStatusCode.UnsupportedMediaType,
            code = RequestErrorCode.UNSUPPORTED_CONTENT_TYPE,
            message = UNSUPPORTED_CONTENT_TYPE_MESSAGE,
        )
    }
    exception<BadRequestException> { call, cause ->
        call.rethrowOutsideApiV1(cause)
        call.respondInvalidRequest()
    }
    exception<ContentTransformationException> { call, cause ->
        call.rethrowOutsideApiV1(cause)
        call.respondInvalidRequest()
    }
    exception<Exception> { call, cause ->
        call.rethrowOutsideApiV1(cause)
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

private fun ApplicationCall.rethrowOutsideApiV1(cause: Throwable) {
    if (!isApiV1Call()) throw cause
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
    violations: List<ApiRequestViolation> = emptyList(),
) {
    respond(
        status,
        RequestErrorEnvelope(
            apiVersion = ApiVersion._1,
            requestId = apiV1RequestId(),
            error = RequestError(
                code = code,
                message = message,
                violations = violations
                    .take(MAX_API_REQUEST_VIOLATIONS)
                    .map(ApiRequestViolation::toGenerated),
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
internal const val MAX_API_REQUEST_VIOLATIONS = 8

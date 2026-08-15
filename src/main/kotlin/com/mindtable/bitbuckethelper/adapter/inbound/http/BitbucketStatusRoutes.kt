package com.mindtable.bitbuckethelper.adapter.inbound.http

import com.mindtable.bitbuckethelper.application.model.BitbucketConnectionSnapshot
import com.mindtable.bitbuckethelper.application.port.inbound.GetBitbucketConnectionStatus
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.plugins.statuspages.exception
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class BitbucketStatusResponse(
    val schemaVersion: Int = 1,
    val state: String,
    val lastAttemptAt: String?,
    val lastSuccessAt: String?,
    val account: AccountResponse?,
    val failure: FailureResponse?,
)

@Serializable
data class AccountResponse(
    val uuid: String,
    val displayName: String,
    val nickname: String?,
)

@Serializable
data class FailureResponse(
    val code: String,
    val message: String,
)

@Serializable
private data class InternalErrorResponse(
    val schemaVersion: Int = 1,
    val error: String = "internal_server_error",
)

fun Application.installBitbucketStatusApi(
    getStatus: GetBitbucketConnectionStatus,
) {
    install(ContentNegotiation) {
        json(Json { encodeDefaults = true; explicitNulls = true })
    }
    install(StatusPages) {
        exception<Exception> { call, cause ->
            if (cause is CancellationException) throw cause
            call.respond(HttpStatusCode.InternalServerError, InternalErrorResponse())
        }
    }
    routing {
        get("/api/v1/bitbucket/status") {
            call.respond(HttpStatusCode.OK, getStatus().toResponse())
        }
    }
}

private fun BitbucketConnectionSnapshot?.toResponse(): BitbucketStatusResponse =
    if (this == null) {
        BitbucketStatusResponse(
            state = "pending",
            lastAttemptAt = null,
            lastSuccessAt = null,
            account = null,
            failure = null,
        )
    } else {
        BitbucketStatusResponse(
            state = state.name.lowercase(),
            lastAttemptAt = lastAttemptAt.toString(),
            lastSuccessAt = lastSuccessAt?.toString(),
            account = account?.let {
                AccountResponse(
                    uuid = it.uuid,
                    displayName = it.displayName,
                    nickname = it.nickname,
                )
            },
            failure = failure?.let {
                FailureResponse(
                    code = it.code.name.lowercase(),
                    message = it.message,
                )
            },
        )
    }

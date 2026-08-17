package com.mindtable.bitbuckethelper.adapter.inbound.http

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respond

suspend inline fun <reified T : Any> ApplicationCall.respondApiV1(
    buildEnvelope: (requestId: String) -> T,
) {
    respond(HttpStatusCode.OK, buildEnvelope(apiV1RequestId()))
}

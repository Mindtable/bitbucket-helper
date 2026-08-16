package com.mindtable.bitbuckethelper.adapter.inbound.http

import io.ktor.server.application.ApplicationCall
import io.ktor.util.AttributeKey
import java.security.SecureRandom
import java.util.Base64

private val RequestIdAttribute = AttributeKey<String>("api-v1-request-id")
private val secureRandom = SecureRandom()
private val urlEncoder = Base64.getUrlEncoder().withoutPadding()

internal fun ApplicationCall.assignApiV1RequestId() {
    attributes.put(RequestIdAttribute, newRequestId())
}

fun ApplicationCall.apiV1RequestId(): String = attributes[RequestIdAttribute]

private fun newRequestId(): String {
    val entropy = ByteArray(18)
    secureRandom.nextBytes(entropy)
    return "req_${urlEncoder.encodeToString(entropy)}"
}

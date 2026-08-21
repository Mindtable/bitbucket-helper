package com.mindtable.bitbuckethelper.adapter.inbound.http

import io.ktor.server.application.ApplicationCall
import io.ktor.util.AttributeKey
import java.security.SecureRandom
import java.util.Base64

private val LocalRequestIdAttribute = AttributeKey<String>("local-request-id")
private val secureRandom = SecureRandom()
private val urlEncoder = Base64.getUrlEncoder().withoutPadding()

internal fun ApplicationCall.assignLocalRequestId() {
    attributes.put(LocalRequestIdAttribute, newRequestId())
}

internal fun ApplicationCall.localRequestId(): String = attributes[LocalRequestIdAttribute]

internal fun ApplicationCall.assignApiV1RequestId() = assignLocalRequestId()

fun ApplicationCall.apiV1RequestId(): String = localRequestId()

private fun newRequestId(): String {
    val entropy = ByteArray(18)
    secureRandom.nextBytes(entropy)
    return "req_${urlEncoder.encodeToString(entropy)}"
}

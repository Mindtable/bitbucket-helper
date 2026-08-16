package com.mindtable.bitbuckethelper.cli

import com.mindtable.bitbuckethelper.generated.api.v1.model.RequestErrorEnvelope
import io.ktor.http.HttpStatusCode

/** A versioned API response with its unmodified UTF-8 wire document. */
data class LocalApiResponse<out T>(
    val status: HttpStatusCode,
    val body: ByteArray,
    val value: T?,
    val error: RequestErrorEnvelope?,
) {
    val document: String
        get() = body.decodeToString()
}

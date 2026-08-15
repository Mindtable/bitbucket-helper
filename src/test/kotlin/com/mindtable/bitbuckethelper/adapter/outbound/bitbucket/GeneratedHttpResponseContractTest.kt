package com.mindtable.bitbuckethelper.adapter.outbound.bitbucket

import com.mindtable.bitbuckethelper.adapter.outbound.bitbucket.generated.infrastructure.BodyProvider
import com.mindtable.bitbuckethelper.adapter.outbound.bitbucket.generated.infrastructure.HttpResponse as GeneratedHttpResponse
import io.ktor.client.call.HttpClientCall
import io.ktor.http.Headers
import io.ktor.http.HttpProtocolVersion
import io.ktor.http.HttpStatusCode
import io.ktor.util.date.GMTDate
import io.ktor.util.reflect.TypeInfo
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.InternalAPI
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.EmptyCoroutineContext
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class GeneratedHttpResponseContractTest {
    @Test
    fun `generated response defers header copying until callers explicitly access headers`() {
        val headerCopies = AtomicInteger()
        val generated = GeneratedHttpResponse(
            response = responseWithTrackedHeaders(headerCopies),
            provider = unusedBodyProvider(),
        )

        assertEquals(0, headerCopies.get())

        assertEquals(mapOf("X-Provenance" to listOf("generated")), generated.headers)
        assertEquals(1, headerCopies.get())
        assertEquals(generated.headers, generated.headers)
        assertEquals(1, headerCopies.get())
    }

    @OptIn(InternalAPI::class)
    private fun responseWithTrackedHeaders(headerCopies: AtomicInteger) =
        object : io.ktor.client.statement.HttpResponse() {
            override val call: HttpClientCall
                get() = error("Body access is outside this contract")
            override val status = HttpStatusCode.Unauthorized
            override val version = HttpProtocolVersion.HTTP_1_1
            override val requestTime = GMTDate.START
            override val responseTime = GMTDate.START
            override val rawContent = ByteReadChannel.Empty
            override val coroutineContext = EmptyCoroutineContext
            override val headers: Headers = object : Headers by Headers.Empty {
                override fun entries(): Set<Map.Entry<String, List<String>>> {
                    headerCopies.incrementAndGet()
                    return mapOf("X-Provenance" to listOf("generated")).entries
                }
            }
        }

    private fun unusedBodyProvider() = object : BodyProvider<String> {
        override suspend fun body(response: io.ktor.client.statement.HttpResponse): String =
            error("Body access is outside this contract")

        override suspend fun <V> typedBody(
            response: io.ktor.client.statement.HttpResponse,
            type: TypeInfo,
        ): V = error("Body access is outside this contract")
    }
}

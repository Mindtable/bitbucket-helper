package com.mindtable.bitbuckethelper.cli

import com.mindtable.bitbuckethelper.generated.api.v1.model.RequestErrorEnvelope
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.engine.cio.endpoint
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.request.unixSocket
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.http.takeFrom
import io.ktor.utils.io.readRemaining
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlinx.io.readByteArray

/**
 * Replaceable boundary used by product commands to access the local service.
 *
 * Each operation is sent exclusively through the configured Unix-domain socket.
 */
interface LocalApiClient : AutoCloseable {
    suspend fun <Response> get(path: String, responseSerializer: KSerializer<Response>): LocalApiResponse<Response>

    suspend fun <Request, Response> post(
        path: String,
        request: Request,
        requestSerializer: KSerializer<Request>,
        responseSerializer: KSerializer<Response>,
    ): LocalApiResponse<Response>

    suspend fun <Request, Response> put(
        path: String,
        request: Request,
        requestSerializer: KSerializer<Request>,
        responseSerializer: KSerializer<Response>,
    ): LocalApiResponse<Response>

    suspend fun <Response> delete(path: String, responseSerializer: KSerializer<Response>): LocalApiResponse<Response>
}

data class LocalApiClientConfig(
    val requestTimeout: Duration = 10.seconds,
    val maxResponseBytes: Int = DEFAULT_MAX_RESPONSE_BYTES,
) {
    init {
        require(requestTimeout.isPositive()) { "requestTimeout must be positive" }
        require(maxResponseBytes > 0) { "maxResponseBytes must be positive" }
    }

    private companion object {
        const val DEFAULT_MAX_RESPONSE_BYTES = 1_048_576
    }
}

class LocalApiResponseTooLargeException(maxResponseBytes: Int) : IllegalStateException(
    "Local API response exceeds $maxResponseBytes bytes",
)

class UnixSocketLocalApiClient(
    private val socketPath: Path,
    private val config: LocalApiClientConfig = LocalApiClientConfig(),
    private val json: Json = strictJson,
) : LocalApiClient {
    private val closed = AtomicBoolean()
    private val httpClient = HttpClient(CIO) {
        expectSuccess = false
        install(HttpTimeout) {
            requestTimeoutMillis = config.requestTimeout.inWholeMilliseconds
            connectTimeoutMillis = config.requestTimeout.inWholeMilliseconds
            socketTimeoutMillis = config.requestTimeout.inWholeMilliseconds
        }
        engine {
            requestTimeout = config.requestTimeout.inWholeMilliseconds
            endpoint {
                connectAttempts = 1
                connectTimeout = config.requestTimeout.inWholeMilliseconds
                socketTimeout = config.requestTimeout.inWholeMilliseconds
            }
        }
    }

    override suspend fun <Response> get(
        path: String,
        responseSerializer: KSerializer<Response>,
    ): LocalApiResponse<Response> = execute(HttpMethod.Get, path, null, responseSerializer)

    override suspend fun <Request, Response> post(
        path: String,
        request: Request,
        requestSerializer: KSerializer<Request>,
        responseSerializer: KSerializer<Response>,
    ): LocalApiResponse<Response> = execute(
        HttpMethod.Post,
        path,
        json.encodeToString(requestSerializer, request),
        responseSerializer,
    )

    override suspend fun <Request, Response> put(
        path: String,
        request: Request,
        requestSerializer: KSerializer<Request>,
        responseSerializer: KSerializer<Response>,
    ): LocalApiResponse<Response> = execute(
        HttpMethod.Put,
        path,
        json.encodeToString(requestSerializer, request),
        responseSerializer,
    )

    override suspend fun <Response> delete(
        path: String,
        responseSerializer: KSerializer<Response>,
    ): LocalApiResponse<Response> = execute(HttpMethod.Delete, path, null, responseSerializer)

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            httpClient.close()
        }
    }

    private suspend fun <Response> execute(
        method: HttpMethod,
        path: String,
        requestBody: String?,
        responseSerializer: KSerializer<Response>,
    ): LocalApiResponse<Response> {
        require(path.startsWith('/')) { "Local API path must start with '/'" }
        val response = httpClient.request {
            this.method = method
            url { takeFrom("http://localhost$path") }
            unixSocket(socketPath.toString())
            if (requestBody != null) {
                contentType(ContentType.Application.Json)
                setBody(requestBody)
            }
        }
        val body = response.bodyAsChannel()
            .readRemaining(config.maxResponseBytes.toLong() + 1)
            .readByteArray()
        if (body.size > config.maxResponseBytes) {
            throw LocalApiResponseTooLargeException(config.maxResponseBytes)
        }
        val document = body.decodeToString()
        return if (response.status.isSuccess()) {
            LocalApiResponse(
                status = response.status,
                body = body,
                value = json.decodeFromString(responseSerializer, document),
                error = null,
            )
        } else {
            LocalApiResponse(
                status = response.status,
                body = body,
                value = null,
                error = json.decodeFromString(RequestErrorEnvelope.serializer(), document),
            )
        }
    }

    private companion object {
        val strictJson = Json {
            ignoreUnknownKeys = false
            explicitNulls = true
        }
    }
}

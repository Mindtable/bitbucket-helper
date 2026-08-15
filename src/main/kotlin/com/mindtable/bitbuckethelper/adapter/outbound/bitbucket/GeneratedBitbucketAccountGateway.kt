package com.mindtable.bitbuckethelper.adapter.outbound.bitbucket

import com.fasterxml.jackson.annotation.JsonTypeInfo
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.mindtable.bitbuckethelper.adapter.outbound.bitbucket.generated.api.UsersApi
import com.mindtable.bitbuckethelper.adapter.outbound.bitbucket.generated.model.Account as GeneratedAccount
import com.mindtable.bitbuckethelper.application.model.BitbucketAccount
import com.mindtable.bitbuckethelper.application.model.BitbucketAccountResult
import com.mindtable.bitbuckethelper.application.model.ConnectionFailure
import com.mindtable.bitbuckethelper.application.model.ConnectionFailureCode
import com.mindtable.bitbuckethelper.application.port.outbound.BitbucketAccountGateway
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.cio.CIO
import io.ktor.client.network.sockets.ConnectTimeoutException
import io.ktor.client.network.sockets.SocketTimeoutException
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.header
import io.ktor.http.HttpHeaders
import java.io.IOException
import java.net.URI
import java.nio.charset.StandardCharsets.UTF_8
import java.time.Duration
import java.util.Base64
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancel

class GeneratedBitbucketAccountGateway private constructor(
    private val usersApi: UsersApi,
    private val engine: HttpClientEngine,
) : BitbucketAccountGateway, AutoCloseable {
    private val closed = AtomicBoolean()

    override suspend fun fetchCurrentAccount(): BitbucketAccountResult = try {
        val response = usersApi.getCurrentUser()
        if (!response.success) {
            response.response.cancel()
            mapHttpFailure(response.status)
        } else {
            val generated = response.body()
            BitbucketAccountResult.Success(
                BitbucketAccount(
                    uuid = requireNotNull(generated.uuid) { "Bitbucket account UUID was absent" },
                    displayName = requireNotNull(generated.displayName) {
                        "Bitbucket display name was absent"
                    },
                    nickname = null,
                ),
            )
        }
    } catch (failure: CancellationException) {
        throw failure
    } catch (failure: Exception) {
        mapFailure(failure)
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            engine.close()
        }
    }

    private fun mapHttpFailure(status: Int): BitbucketAccountResult.Failure = when (status) {
        401 -> failure(ConnectionFailureCode.AUTHENTICATION, "Bitbucket rejected the credentials")
        403 -> failure(ConnectionFailureCode.AUTHORIZATION, "Bitbucket denied the required permission")
        429 -> failure(ConnectionFailureCode.RATE_LIMITED, "Bitbucket rate limit exceeded")
        in 500..599 -> failure(ConnectionFailureCode.UPSTREAM, "Bitbucket service failed")
        else -> failure(ConnectionFailureCode.UNEXPECTED, "Bitbucket request failed unexpectedly")
    }

    private fun mapFailure(cause: Exception): BitbucketAccountResult.Failure = when (cause) {
        is HttpRequestTimeoutException,
        is ConnectTimeoutException,
        is SocketTimeoutException,
        -> failure(ConnectionFailureCode.TIMEOUT, "Bitbucket request timed out")
        is IOException -> failure(ConnectionFailureCode.NETWORK, "Bitbucket is unreachable")
        else -> failure(ConnectionFailureCode.UNEXPECTED, "Bitbucket request failed unexpectedly")
    }

    private fun failure(
        code: ConnectionFailureCode,
        message: String,
    ): BitbucketAccountResult.Failure =
        BitbucketAccountResult.Failure(ConnectionFailure(code, message))

    companion object {
        fun create(
            baseUrl: URI,
            requestTimeout: Duration,
            username: String,
            apiToken: String,
        ): GeneratedBitbucketAccountGateway {
            val authorization = basicAuthorization(username, apiToken)
            return create(baseUrl, requestTimeout, authorization, CIO.create())
        }

        internal fun create(
            baseUrl: URI,
            requestTimeout: Duration,
            username: String,
            apiToken: String,
            engine: HttpClientEngine,
        ): GeneratedBitbucketAccountGateway =
            create(baseUrl, requestTimeout, basicAuthorization(username, apiToken), engine)

        private fun create(
            baseUrl: URI,
            requestTimeout: Duration,
            authorization: String,
            engine: HttpClientEngine,
        ): GeneratedBitbucketAccountGateway {
            return try {
                val timeoutMillis = requestTimeout.toMillis().coerceAtLeast(1L)
                val usersApi = UsersApi(
                    baseUrl = baseUrl.toASCIIString(),
                    httpClientEngine = engine,
                    httpClientConfig = { client ->
                        client.install(HttpTimeout) {
                            connectTimeoutMillis = timeoutMillis
                            requestTimeoutMillis = timeoutMillis
                        }
                        client.defaultRequest {
                            header(HttpHeaders.Authorization, authorization)
                        }
                    },
                    jsonBlock = {
                        registerModule(JavaTimeModule())
                        configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
                        addMixIn(GeneratedAccount::class.java, DirectAccountDeserialization::class.java)
                    },
                )
                GeneratedBitbucketAccountGateway(usersApi, engine)
            } catch (failure: Exception) {
                engine.close()
                throw failure
            }
        }

        private fun basicAuthorization(username: String, apiToken: String): String =
            "Basic " + Base64.getEncoder()
                .encodeToString("$username:$apiToken".toByteArray(UTF_8))
    }
}

@JsonTypeInfo(use = JsonTypeInfo.Id.NONE)
private abstract class DirectAccountDeserialization

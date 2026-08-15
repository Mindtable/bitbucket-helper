package com.mindtable.bitbuckethelper.adapter.outbound.bitbucket

import com.fasterxml.jackson.annotation.JsonTypeInfo
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.mindtable.bitbuckethelper.adapter.outbound.bitbucket.generated.api.RepositoriesApi
import com.mindtable.bitbuckethelper.adapter.outbound.bitbucket.generated.api.UsersApi
import com.mindtable.bitbuckethelper.adapter.outbound.bitbucket.generated.api.WorkspacesApi
import com.mindtable.bitbuckethelper.adapter.outbound.bitbucket.generated.infrastructure.HttpResponse
import com.mindtable.bitbuckethelper.adapter.outbound.bitbucket.generated.model.Account as GeneratedAccount
import com.mindtable.bitbuckethelper.adapter.outbound.bitbucket.generated.model.Repository as GeneratedRepository
import com.mindtable.bitbuckethelper.adapter.outbound.bitbucket.generated.model.Workspace as GeneratedWorkspace
import com.mindtable.bitbuckethelper.application.model.GatewayFailure
import com.mindtable.bitbuckethelper.application.model.GatewayFailureCategory
import com.mindtable.bitbuckethelper.application.model.GatewayRepositoryObservation
import com.mindtable.bitbuckethelper.application.model.GatewayResult
import com.mindtable.bitbuckethelper.application.model.GatewayUserObservation
import com.mindtable.bitbuckethelper.application.model.GatewayWorkspaceObservation
import io.ktor.client.HttpClientConfig
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
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.Base64
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancel

class GeneratedBitbucketGateway private constructor(
    private val requestTimeoutMillis: Long,
    private val authorization: String,
    private val engine: HttpClientEngine,
    private val clock: Clock,
) : AutoCloseable {
    private val closed = AtomicBoolean()
    private val clientsByBaseUrl = ConcurrentHashMap<String, GeneratedApiClients>()

    suspend fun currentUser(apiBaseUrl: URI): GatewayResult<GatewayUserObservation> =
        execute(apiBaseUrl, { users.getCurrentUser() }) { it.toGatewayUserObservation() }

    suspend fun resolveWorkspace(
        apiBaseUrl: URI,
        workspaceSlug: String,
    ): GatewayResult<GatewayWorkspaceObservation> =
        execute(apiBaseUrl, { workspaces.getWorkspace(workspaceSlug) }) { it.toGatewayWorkspaceObservation() }

    suspend fun resolveRepository(
        apiBaseUrl: URI,
        workspaceSlug: String,
        repositorySlug: String,
    ): GatewayResult<GatewayRepositoryObservation> =
        execute(apiBaseUrl, { repositories.getRepository(repositorySlug, workspaceSlug) }) {
            it.toGatewayRepositoryObservation()
        }

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            engine.close()
        }
    }

    private suspend fun <T, R> execute(
        apiBaseUrl: URI,
        request: suspend GeneratedApiClients.() -> HttpResponse<T>,
        map: (T) -> R,
    ): GatewayResult<R> = try {
        val response = clientsFor(apiBaseUrl).request()
        if (!response.success) {
            response.response.cancel()
            mapHttpFailure(response.status, response.headers)
        } else {
            GatewayResult.Success(map(response.body()))
        }
    } catch (failure: CancellationException) {
        throw failure
    } catch (_: IdentityMappingException) {
        malformedResponseFailure()
    } catch (failure: Exception) {
        mapException(failure)
    }

    private fun clientsFor(apiBaseUrl: URI): GeneratedApiClients =
        clientsByBaseUrl.computeIfAbsent(normalizedBaseUrl(apiBaseUrl), ::createClients)

    private fun createClients(baseUrl: String): GeneratedApiClients {
        val config: (HttpClientConfig<*>) -> Unit = { client ->
            client.install(HttpTimeout) {
                connectTimeoutMillis = requestTimeoutMillis
                requestTimeoutMillis = this@GeneratedBitbucketGateway.requestTimeoutMillis
            }
            client.defaultRequest {
                header(HttpHeaders.Authorization, authorization)
            }
        }
        val jsonBlock: ObjectMapper.() -> Unit = {
            registerModule(JavaTimeModule())
            configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
            addMixIn(GeneratedAccount::class.java, DirectAccountDeserialization::class.java)
            addMixIn(GeneratedWorkspace::class.java, DirectWorkspaceDeserialization::class.java)
            addMixIn(GeneratedRepository::class.java, DirectRepositoryDeserialization::class.java)
        }
        return GeneratedApiClients(
            users = UsersApi(baseUrl, engine, config, jsonBlock),
            workspaces = WorkspacesApi(baseUrl, engine, config, jsonBlock),
            repositories = RepositoriesApi(baseUrl, engine, config, jsonBlock),
        )
    }

    private fun normalizedBaseUrl(apiBaseUrl: URI): String {
        if (
            !apiBaseUrl.isAbsolute || apiBaseUrl.isOpaque || apiBaseUrl.host == null ||
            apiBaseUrl.rawQuery != null || apiBaseUrl.rawFragment != null ||
            apiBaseUrl.scheme.lowercase(Locale.ROOT) !in setOf("http", "https")
        ) {
            throw IdentityMappingException()
        }
        val normalizedPath = apiBaseUrl.path.orEmpty().trimEnd('/').ifEmpty { "/" }
        return URI(apiBaseUrl.scheme, apiBaseUrl.authority, normalizedPath, null, null).toASCIIString()
    }

    private fun mapHttpFailure(
        status: Int,
        headers: Map<String, List<String>>,
    ): GatewayResult<Nothing> = when (status) {
        401 -> failure(GatewayFailureCategory.AUTHENTICATION, retryable = false)
        403 -> failure(GatewayFailureCategory.AUTHORIZATION, retryable = false)
        404 -> GatewayResult.NotFound
        429 -> failure(GatewayFailureCategory.RATE_LIMITED, retryable = true, retryAt = retryAt(headers))
        in 500..599 -> failure(GatewayFailureCategory.UPSTREAM, retryable = true)
        else -> malformedResponseFailure()
    }

    private fun mapException(failure: Exception): GatewayResult.Failure = when (failure) {
        is HttpRequestTimeoutException,
        is ConnectTimeoutException,
        is SocketTimeoutException,
        -> failure(GatewayFailureCategory.TIMEOUT, retryable = true)
        is IOException -> failure(GatewayFailureCategory.NETWORK, retryable = true)
        else -> malformedResponseFailure()
    }

    private fun retryAt(headers: Map<String, List<String>>): Instant? =
        headerValue(headers, "Retry-After")?.let(::parseRetryAfter)
            ?: headerValue(headers, "X-RateLimit-Reset")?.toLongOrNull()?.let { epochSeconds ->
                runCatching { Instant.ofEpochSecond(epochSeconds) }.getOrNull()
            }

    private fun parseRetryAfter(value: String): Instant? =
        value.toLongOrNull()?.takeIf { it >= 0 }?.let { seconds ->
            runCatching { clock.instant().plusSeconds(seconds) }.getOrNull()
        } ?: runCatching {
            DateTimeFormatter.RFC_1123_DATE_TIME.parse(value, Instant::from)
        }.getOrNull()

    private fun headerValue(headers: Map<String, List<String>>, name: String): String? =
        headers.entries.firstOrNull { (headerName, _) -> headerName.equals(name, ignoreCase = true) }
            ?.value
            ?.firstOrNull()

    private fun malformedResponseFailure(): GatewayResult.Failure =
        failure(GatewayFailureCategory.MALFORMED_RESPONSE, retryable = false)

    private fun failure(
        category: GatewayFailureCategory,
        retryable: Boolean,
        retryAt: Instant? = null,
    ): GatewayResult.Failure = GatewayResult.Failure(GatewayFailure(category, retryable, retryAt))

    private data class GeneratedApiClients(
        val users: UsersApi,
        val workspaces: WorkspacesApi,
        val repositories: RepositoriesApi,
    )

    companion object {
        fun create(
            requestTimeout: Duration,
            username: String,
            appPassword: String,
        ): GeneratedBitbucketGateway = create(requestTimeout, username, appPassword, CIO.create())

        internal fun create(
            requestTimeout: Duration,
            username: String,
            appPassword: String,
            engine: HttpClientEngine,
            clock: Clock = Clock.systemUTC(),
        ): GeneratedBitbucketGateway = try {
            GeneratedBitbucketGateway(
                requestTimeoutMillis = requestTimeout.toMillis().coerceAtLeast(1),
                authorization = basicAuthorization(username, appPassword),
                engine = engine,
                clock = clock,
            )
        } catch (failure: Exception) {
            engine.close()
            throw failure
        }

        private fun basicAuthorization(username: String, appPassword: String): String =
            "Basic " + Base64.getEncoder().encodeToString("$username:$appPassword".toByteArray(UTF_8))
    }
}

@JsonTypeInfo(use = JsonTypeInfo.Id.NONE)
private abstract class DirectAccountDeserialization

@JsonTypeInfo(use = JsonTypeInfo.Id.NONE)
private abstract class DirectWorkspaceDeserialization

@JsonTypeInfo(use = JsonTypeInfo.Id.NONE)
private abstract class DirectRepositoryDeserialization

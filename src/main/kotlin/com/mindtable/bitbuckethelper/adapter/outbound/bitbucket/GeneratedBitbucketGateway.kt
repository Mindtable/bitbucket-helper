package com.mindtable.bitbuckethelper.adapter.outbound.bitbucket

import com.fasterxml.jackson.annotation.JsonTypeInfo
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.mindtable.bitbuckethelper.adapter.outbound.bitbucket.generated.api.CommitsApi
import com.mindtable.bitbuckethelper.adapter.outbound.bitbucket.generated.api.PullRequestsApi
import com.mindtable.bitbuckethelper.adapter.outbound.bitbucket.generated.api.RefsApi
import com.mindtable.bitbuckethelper.adapter.outbound.bitbucket.generated.api.RepositoriesApi
import com.mindtable.bitbuckethelper.adapter.outbound.bitbucket.generated.api.UsersApi
import com.mindtable.bitbuckethelper.adapter.outbound.bitbucket.generated.api.WorkspacesApi
import com.mindtable.bitbuckethelper.adapter.outbound.bitbucket.generated.infrastructure.HttpResponse
import com.mindtable.bitbuckethelper.adapter.outbound.bitbucket.generated.model.Account as GeneratedAccount
import com.mindtable.bitbuckethelper.adapter.outbound.bitbucket.generated.model.PaginatedPullrequests
import com.mindtable.bitbuckethelper.adapter.outbound.bitbucket.generated.model.Repository as GeneratedRepository
import com.mindtable.bitbuckethelper.adapter.outbound.bitbucket.generated.model.Workspace as GeneratedWorkspace
import com.mindtable.bitbuckethelper.application.model.GatewayFailure
import com.mindtable.bitbuckethelper.application.model.GatewayFailureCategory
import com.mindtable.bitbuckethelper.application.model.GatewayActivityObservation
import com.mindtable.bitbuckethelper.application.model.GatewayBuildObservation
import com.mindtable.bitbuckethelper.application.model.GatewayLiveActivityContent
import com.mindtable.bitbuckethelper.application.model.GatewayPullRequestDetail
import com.mindtable.bitbuckethelper.application.model.GatewayPullRequestSummary
import com.mindtable.bitbuckethelper.application.model.GatewayRepositoryAddress
import com.mindtable.bitbuckethelper.application.model.GatewayRepositoryObservation
import com.mindtable.bitbuckethelper.application.model.GatewayResult
import com.mindtable.bitbuckethelper.application.model.GatewayTaskObservation
import com.mindtable.bitbuckethelper.application.model.GatewayUserObservation
import com.mindtable.bitbuckethelper.application.model.GatewayWorkspaceObservation
import com.mindtable.bitbuckethelper.application.port.outbound.BitbucketGateway
import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.cio.CIO
import io.ktor.client.network.sockets.ConnectTimeoutException
import io.ktor.client.network.sockets.SocketTimeoutException
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import java.io.IOException
import java.net.URI
import java.net.URLDecoder
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
) : BitbucketGateway, AutoCloseable {
    private val closed = AtomicBoolean()
    private val clientsByBaseUrl = ConcurrentHashMap<String, GeneratedApiClients>()
    private val paginationClient by lazy {
        HttpClient(engine) {
            expectSuccess = false
            followRedirects = false
            install(HttpTimeout) {
                connectTimeoutMillis = requestTimeoutMillis
                requestTimeoutMillis = this@GeneratedBitbucketGateway.requestTimeoutMillis
            }
            defaultRequest {
                header(HttpHeaders.Authorization, authorization)
            }
        }
    }
    private val paginationMapper by lazy {
        ObjectMapper().apply {
            registerModule(JavaTimeModule())
            configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
            addMixIn(GeneratedAccount::class.java, DirectAccountDeserialization::class.java)
            addMixIn(GeneratedWorkspace::class.java, DirectWorkspaceDeserialization::class.java)
            addMixIn(GeneratedRepository::class.java, DirectRepositoryDeserialization::class.java)
        }
    }

    override suspend fun currentUser(apiBaseUrl: URI): GatewayResult<GatewayUserObservation> =
        execute(apiBaseUrl, { users.getCurrentUser() }) { it.toGatewayUserObservation() }

    override suspend fun resolveWorkspace(
        apiBaseUrl: URI,
        workspaceSlug: String,
    ): GatewayResult<GatewayWorkspaceObservation> =
        execute(apiBaseUrl, { workspaces.getWorkspace(workspaceSlug) }) { it.toGatewayWorkspaceObservation() }

    override suspend fun resolveRepository(
        apiBaseUrl: URI,
        workspaceSlug: String,
        repositorySlug: String,
    ): GatewayResult<GatewayRepositoryObservation> =
        execute(apiBaseUrl, { repositories.getRepository(repositorySlug, workspaceSlug) }) {
            it.toGatewayRepositoryObservation()
        }

    override suspend fun listAuthoredOpenPullRequests(
        repository: GatewayRepositoryAddress,
        currentUserStableId: String,
    ): GatewayResult<List<GatewayPullRequestSummary>> {
        try {
            val expectedAuthorStableId = currentUserStableId.requiredBitbucketStableId()
            val configuredApiUrl = URI(normalizedBaseUrl(repository.apiBaseUrl))
            var requestUrl = initialPullRequestUrl(configuredApiUrl, repository, expectedAuthorStableId)
            var initialRequest = true
            val visitedUrls = mutableSetOf<String>()
            val pullRequests = mutableListOf<GatewayPullRequestSummary>()
            var pageCount = 0

            while (true) {
                if (!initialRequest && !visitedUrls.add(requestUrl.toASCIIString())) {
                    return unsafePaginationFailure()
                }

                val pageResult = if (initialRequest) {
                    initialRequest = false
                    val generatedResponse = clientsFor(configuredApiUrl).pullRequests.listAuthoredOpenPullRequests(
                        repository.repositorySlug,
                        repository.workspaceSlug,
                        "OPEN",
                        "author.uuid=\"$expectedAuthorStableId\"",
                    )
                    requestUrl = URI(generatedResponse.response.call.request.url.toString())
                    if (!visitedUrls.add(requestUrl.toASCIIString())) {
                        return unsafePaginationFailure()
                    }
                    fetchGeneratedPullRequestPage(
                        generatedResponse,
                    )
                } else {
                    fetchOpaquePullRequestPage(requestUrl)
                }
                when (pageResult) {
                    is GatewayResult.Failure -> return pageResult
                    GatewayResult.NotFound -> return GatewayResult.NotFound
                    is GatewayResult.Success -> {
                        val page = pageResult.value
                        val mapped = page.values.map { it.toGatewayPullRequestSummary(repository.id) }
                        if (mapped.any { it.authorStableId != expectedAuthorStableId }) {
                            return malformedResponseFailure()
                        }
                        pullRequests += mapped
                        pageCount += 1

                        val opaqueNext = page.next ?: return GatewayResult.Success(pullRequests)
                        if (pageCount >= MAXIMUM_PULL_REQUEST_PAGES) {
                            return unsafePaginationFailure()
                        }
                        requestUrl = resolveSafeNextUrl(configuredApiUrl, requestUrl, opaqueNext)
                    }
                }
            }
        } catch (failure: CancellationException) {
            throw failure
        } catch (_: UnsafePaginationException) {
            return unsafePaginationFailure()
        } catch (_: IdentityMappingException) {
            return malformedResponseFailure()
        } catch (failure: Exception) {
            return mapException(failure)
        }
    }

    override suspend fun getPullRequest(
        repository: GatewayRepositoryAddress,
        upstreamNumber: Long,
    ): GatewayResult<GatewayPullRequestDetail> {
        val pullRequestId = upstreamNumber.requiredPullRequestId() ?: return malformedResponseFailure()
        val detail = fetchGeneratedResource(
            repository,
            pullRequestPath(repository, upstreamNumber),
            {
                pullRequests.getPullRequest(
                    pullRequestId,
                    repository.repositorySlug,
                    repository.workspaceSlug,
                )
            },
        ) { root ->
            val generated = paginationMapper.treeToValue(
                root,
                com.mindtable.bitbuckethelper.adapter.outbound.bitbucket.generated.model.Pullrequest::class.java,
            )
            val coordinates = root.toPullRequestReadinessCoordinates()
            val summary = generated.toGatewayPullRequestSummary(repository.id)
            if (coordinates.sourceCommit != summary.headCommit) throw IdentityMappingException()
            PullRequestDetailSeed(generated, root, coordinates)
        }
        val seed = when (detail) {
            is GatewayResult.Failure -> return detail
            GatewayResult.NotFound -> return GatewayResult.NotFound
            is GatewayResult.Success -> detail.value
        }
        val branches = traverseCollection(
            repository,
            repositoryPath(repository, "refs/branches"),
            {
                refs.listDestinationBranches(
                    repository.repositorySlug,
                    repository.workspaceSlug,
                    exactBranchNameQuery(seed.coordinates.destinationBranchName),
                    null,
                )
            },
        ) { root -> root.toBranchTargetObservation() }
        val branchValues = when (branches) {
            is GatewayResult.Failure -> return branches
            GatewayResult.NotFound -> return GatewayResult.NotFound
            is GatewayResult.Success -> branches.value
        }
        val currentDestinationCommit = branchValues
            .filter { it.name == seed.coordinates.destinationBranchName }
            .singleOrNull()
            ?.targetCommit
            ?: return malformedResponseFailure()
        val commitRange = "$currentDestinationCommit..${seed.coordinates.sourceCommit}"
        val mergeBase = fetchGeneratedResource(
            repository,
            repositoryPath(repository, "merge-base/$commitRange"),
            {
                commits.getMergeBase(
                    repository.repositorySlug,
                    commitRange,
                    repository.workspaceSlug,
                )
            },
        ) { root -> root.toMergeBaseCommit() }
        val mergeBaseCommit = when (mergeBase) {
            is GatewayResult.Failure -> return mergeBase
            GatewayResult.NotFound -> return GatewayResult.NotFound
            is GatewayResult.Success -> mergeBase.value
        }
        val conflicts = traverseCollection(
            repository,
            repositoryPath(repository, "file-conflicts/$commitRange"),
            {
                commits.listFileConflicts(
                    repository.repositorySlug,
                    commitRange,
                    repository.workspaceSlug,
                )
            },
        ) { conflict -> conflict.toFileConflictMarker() }
        val hasMergeConflicts = when (conflicts) {
            is GatewayResult.Failure -> return conflicts
            GatewayResult.NotFound -> return GatewayResult.NotFound
            is GatewayResult.Success -> conflicts.value.isNotEmpty()
        }
        return GatewayResult.Success(
            seed.generated.toGatewayPullRequestDetail(
                repository.id,
                seed.raw,
                destinationBranchIsCurrent =
                    seed.coordinates.observedDestinationCommit == currentDestinationCommit &&
                        mergeBaseCommit == currentDestinationCommit,
                hasMergeConflicts = hasMergeConflicts,
            ),
        )
    }

    override suspend fun getEffectiveDefaultReviewers(
        repository: GatewayRepositoryAddress,
        upstreamNumber: Long,
    ): GatewayResult<List<GatewayUserObservation>> = traverseCollection(
        repository,
        repositoryPath(repository, "effective-default-reviewers"),
        {
            pullRequests.listEffectiveDefaultReviewers(
                repository.repositorySlug,
                repository.workspaceSlug,
            )
        },
    ) { it.toGatewayDefaultReviewer() }

    override suspend fun listBuilds(
        repository: GatewayRepositoryAddress,
        upstreamNumber: Long,
    ): GatewayResult<List<GatewayBuildObservation>> {
        val pullRequestId = upstreamNumber.requiredPullRequestId() ?: return malformedResponseFailure()
        return traverseCollection(
            repository,
            "${pullRequestPath(repository, upstreamNumber)}/statuses",
            {
                pullRequests.listPullRequestStatuses(
                    pullRequestId,
                    repository.repositorySlug,
                    repository.workspaceSlug,
                    null,
                    null,
                )
            },
        ) { it.toGatewayBuildObservation() }
    }

    override suspend fun listTasks(
        repository: GatewayRepositoryAddress,
        upstreamNumber: Long,
    ): GatewayResult<List<GatewayTaskObservation>> {
        val pullRequestId = upstreamNumber.requiredPullRequestId() ?: return malformedResponseFailure()
        return traverseCollection(
            repository,
            "${pullRequestPath(repository, upstreamNumber)}/tasks",
            {
                pullRequests.listPullRequestTasks(
                    pullRequestId,
                    repository.repositorySlug,
                    repository.workspaceSlug,
                    null,
                    null,
                    null,
                )
            },
        ) { it.toGatewayTaskObservation() }
    }

    override suspend fun listActivity(
        repository: GatewayRepositoryAddress,
        upstreamNumber: Long,
    ): GatewayResult<List<GatewayActivityObservation>> {
        val pullRequestId = upstreamNumber.requiredPullRequestId() ?: return malformedResponseFailure()
        return traverseCollectionNotNull(
            repository,
            "${pullRequestPath(repository, upstreamNumber)}/activity",
            {
                pullRequests.listPullRequestActivity(
                    pullRequestId,
                    repository.repositorySlug,
                    repository.workspaceSlug,
                )
            },
        ) { it.toGatewayActivityObservation() }
    }

    override suspend fun getLiveActivityContent(
        repository: GatewayRepositoryAddress,
        upstreamNumber: Long,
        sourceId: String,
    ): GatewayResult<GatewayLiveActivityContent> {
        val commentId = sourceId.toLongOrNull()?.takeIf { it > 0 } ?: return GatewayResult.NotFound
        val pullRequestId = upstreamNumber.requiredPullRequestId() ?: return malformedResponseFailure()
        return fetchGeneratedResource(
            repository,
            "${pullRequestPath(repository, upstreamNumber)}/comments/$commentId",
            {
                pullRequests.getPullRequestComment(
                    commentId,
                    pullRequestId,
                    repository.repositorySlug,
                    repository.workspaceSlug,
                )
            },
        ) { root -> root.toGatewayLiveActivityContent(clock.instant()) }
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
    } catch (_: com.fasterxml.jackson.core.JacksonException) {
        malformedResponseFailure()
    } catch (_: IdentityMappingException) {
        malformedResponseFailure()
    } catch (failure: Exception) {
        mapException(failure)
    }

    private suspend fun <T> fetchGeneratedResource(
        repository: GatewayRepositoryAddress,
        resourcePath: String,
        request: suspend GeneratedApiClients.() -> HttpResponse<*>,
        map: (ObjectNode) -> T,
    ): GatewayResult<T> = try {
        val configuredApiUrl = URI(normalizedBaseUrl(repository.apiBaseUrl))
        resourceUrl(configuredApiUrl, resourcePath)
        when (val response = fetchGeneratedObject(clientsFor(configuredApiUrl).request())) {
            is GatewayResult.Failure -> response
            GatewayResult.NotFound -> GatewayResult.NotFound
            is GatewayResult.Success -> GatewayResult.Success(map(response.value))
        }
    } catch (failure: CancellationException) {
        throw failure
    } catch (_: UnsafePaginationException) {
        unsafePaginationFailure()
    } catch (_: IdentityMappingException) {
        malformedResponseFailure()
    } catch (failure: Exception) {
        mapException(failure)
    }

    private suspend fun <T> traverseCollection(
        repository: GatewayRepositoryAddress,
        resourcePath: String,
        initialRequest: suspend GeneratedApiClients.() -> HttpResponse<*>,
        map: (ObjectNode) -> T,
    ): GatewayResult<List<T>> = traverseCollectionNotNull(repository, resourcePath, initialRequest) { map(it) }

    private suspend fun <T : Any> traverseCollectionNotNull(
        repository: GatewayRepositoryAddress,
        resourcePath: String,
        initialRequest: suspend GeneratedApiClients.() -> HttpResponse<*>,
        map: (ObjectNode) -> T?,
    ): GatewayResult<List<T>> {
        try {
            val configuredApiUrl = URI(normalizedBaseUrl(repository.apiBaseUrl))
            var requestUrl = resourceUrl(configuredApiUrl, resourcePath)
            var firstPage = true
            val visitedUrls = mutableSetOf<String>()
            val observations = mutableListOf<T>()
            var pageCount = 0

            while (true) {
                if (!firstPage && !visitedUrls.add(requestUrl.toASCIIString())) {
                    return unsafePaginationFailure()
                }
                val pageResult = if (firstPage) {
                    firstPage = false
                    val generatedResponse = clientsFor(configuredApiUrl).initialRequest()
                    requestUrl = URI(generatedResponse.response.call.request.url.toString())
                    if (!visitedUrls.add(requestUrl.toASCIIString())) {
                        return unsafePaginationFailure()
                    }
                    fetchGeneratedObject(generatedResponse)
                } else {
                    fetchOpaqueObject(requestUrl)
                }
                when (pageResult) {
                    is GatewayResult.Failure -> return pageResult
                    GatewayResult.NotFound -> return GatewayResult.NotFound
                    is GatewayResult.Success -> {
                        val root = pageResult.value
                        val values = root.get("values")?.takeIf { it.isArray } ?: throw IdentityMappingException()
                        values.forEach { value ->
                            val item = value as? ObjectNode ?: throw IdentityMappingException()
                            map(item)?.let(observations::add)
                        }
                        pageCount += 1
                        val nextNode = root.get("next") ?: return GatewayResult.Success(observations)
                        if (!nextNode.isTextual || pageCount >= MAXIMUM_PULL_REQUEST_PAGES) {
                            return unsafePaginationFailure()
                        }
                        val opaqueNext = try {
                            URI(nextNode.textValue())
                        } catch (_: Exception) {
                            return unsafePaginationFailure()
                        }
                        requestUrl = resolveSafeNextUrl(configuredApiUrl, requestUrl, opaqueNext)
                    }
                }
            }
        } catch (failure: CancellationException) {
            throw failure
        } catch (_: UnsafePaginationException) {
            return unsafePaginationFailure()
        } catch (_: IdentityMappingException) {
            return malformedResponseFailure()
        } catch (failure: Exception) {
            return mapException(failure)
        }
    }

    private suspend fun fetchOpaqueObject(requestUrl: URI): GatewayResult<ObjectNode> = try {
        val response = paginationClient.get(requestUrl.toASCIIString())
        if (response.status.value !in 200..299) {
            response.call.cancel()
            mapHttpFailure(response.status.value, response.headers.entries().associate { it.key to it.value })
        } else {
            val root = paginationMapper.readTree(response.bodyAsText()) as? ObjectNode ?: throw IdentityMappingException()
            GatewayResult.Success(root)
        }
    } catch (failure: CancellationException) {
        throw failure
    } catch (_: com.fasterxml.jackson.core.JacksonException) {
        malformedResponseFailure()
    } catch (_: IdentityMappingException) {
        malformedResponseFailure()
    } catch (failure: Exception) {
        mapException(failure)
    }

    private suspend fun fetchGeneratedObject(response: HttpResponse<*>): GatewayResult<ObjectNode> = try {
        if (!response.success) {
            response.response.cancel()
            mapHttpFailure(response.status, response.headers)
        } else {
            val root = paginationMapper.readTree(response.response.bodyAsText()) as? ObjectNode
                ?: throw IdentityMappingException()
            GatewayResult.Success(root)
        }
    } catch (failure: CancellationException) {
        throw failure
    } catch (_: com.fasterxml.jackson.core.JacksonException) {
        malformedResponseFailure()
    } catch (_: IdentityMappingException) {
        malformedResponseFailure()
    } catch (failure: Exception) {
        mapException(failure)
    }

    private suspend fun fetchOpaquePullRequestPage(requestUrl: URI): GatewayResult<PullRequestPage> = try {
        val response = paginationClient.get(requestUrl.toASCIIString())
        if (response.status.value !in 200..299) {
            response.call.cancel()
            mapHttpFailure(response.status.value, response.headers.entries().associate { it.key to it.value })
        } else {
            GatewayResult.Success(parsePullRequestPage(response.bodyAsText()))
        }
    } catch (failure: CancellationException) {
        throw failure
    } catch (_: UnsafePaginationException) {
        unsafePaginationFailure()
    } catch (_: IdentityMappingException) {
        malformedResponseFailure()
    } catch (failure: Exception) {
        mapException(failure)
    }

    private suspend fun fetchGeneratedPullRequestPage(response: HttpResponse<*>): GatewayResult<PullRequestPage> = try {
        if (!response.success) {
            response.response.cancel()
            mapHttpFailure(response.status, response.headers)
        } else {
            GatewayResult.Success(parsePullRequestPage(response.response.bodyAsText()))
        }
    } catch (failure: CancellationException) {
        throw failure
    } catch (_: UnsafePaginationException) {
        unsafePaginationFailure()
    } catch (_: IdentityMappingException) {
        malformedResponseFailure()
    } catch (failure: Exception) {
        mapException(failure)
    }

    private fun parsePullRequestPage(body: String): PullRequestPage {
        val root = paginationMapper.readTree(body) as? ObjectNode ?: throw IdentityMappingException()
        if (!root.has("values") || !root["values"].isArray) {
            throw IdentityMappingException()
        }
        val next = root.get("next")?.let { nextNode ->
            if (!nextNode.isTextual) {
                throw UnsafePaginationException()
            }
            try {
                URI(nextNode.textValue())
            } catch (_: Exception) {
                throw UnsafePaginationException()
            }
        }
        val page = paginationMapper.treeToValue(root.deepCopy().also { it.remove("next") }, PaginatedPullrequests::class.java)
        return PullRequestPage(page.propertyValues.orEmpty(), next)
    }

    private fun initialPullRequestUrl(
        configuredApiUrl: URI,
        repository: GatewayRepositoryAddress,
        expectedAuthorStableId: String,
    ): URI = URI(
        configuredApiUrl.scheme,
        null,
        configuredApiUrl.host,
        configuredApiUrl.port,
        "${configuredApiUrl.path.trimEnd('/')}/repositories/${repository.workspaceSlug}/" +
            "${repository.repositorySlug}/pullrequests",
        "state=OPEN&q=author.uuid=\"$expectedAuthorStableId\"",
        null,
    )

    private fun repositoryPath(repository: GatewayRepositoryAddress, suffix: String): String =
        "repositories/${repository.workspaceSlug}/${repository.repositorySlug}/$suffix"

    private fun exactBranchNameQuery(branchName: String): String =
        "name = \"${branchName.replace("\\", "\\\\").replace("\"", "\\\"")}\""

    private fun Long.requiredPullRequestId(): Int? =
        takeIf { it in 1..Int.MAX_VALUE.toLong() }?.toInt()

    private fun pullRequestPath(repository: GatewayRepositoryAddress, upstreamNumber: Long): String {
        val number = upstreamNumber.requiredPullRequestId() ?: throw IdentityMappingException()
        return repositoryPath(repository, "pullrequests/$number")
    }

    private fun resourceUrl(configuredApiUrl: URI, resourcePath: String): URI = URI(
        configuredApiUrl.scheme,
        null,
        configuredApiUrl.host,
        configuredApiUrl.port,
        "${configuredApiUrl.path.trimEnd('/')}/${resourcePath.trimStart('/')}",
        null,
        null,
    )

    private fun resolveSafeNextUrl(
        configuredApiUrl: URI,
        currentRequestUrl: URI,
        opaqueNext: URI,
    ): URI {
        val resolved = currentRequestUrl.resolve(opaqueNext).normalize()
        val rawPath = resolved.rawPath.orEmpty().lowercase(Locale.ROOT)
        if (
            !resolved.isAbsolute || resolved.isOpaque || resolved.host == null ||
            resolved.userInfo != null || resolved.rawFragment != null ||
            !sameOrigin(configuredApiUrl, resolved) ||
            !isWithinConfiguredApiScope(configuredApiUrl, resolved) ||
            rawPath.split('/').any(::isUnsafePathSegment)
        ) {
            throw UnsafePaginationException()
        }
        return resolved
    }

    private fun isUnsafePathSegment(segment: String): Boolean {
        var decoded = segment
        repeat(MAXIMUM_PATH_DECODING_PASSES) {
            if (decoded == "." || decoded == ".." || "%2f" in decoded || "%5c" in decoded) {
                return true
            }
            if ('%' !in decoded) {
                return false
            }
            decoded = try {
                URLDecoder.decode(decoded, UTF_8)
            } catch (_: IllegalArgumentException) {
                return true
            }
        }
        return true
    }

    private fun sameOrigin(left: URI, right: URI): Boolean =
        left.scheme.equals(right.scheme, ignoreCase = true) &&
            left.host.equals(right.host, ignoreCase = true) &&
            effectivePort(left) == effectivePort(right)

    private fun effectivePort(uri: URI): Int = when {
        uri.port >= 0 -> uri.port
        uri.scheme.equals("http", ignoreCase = true) -> 80
        uri.scheme.equals("https", ignoreCase = true) -> 443
        else -> -1
    }

    private fun isWithinConfiguredApiScope(configuredApiUrl: URI, candidate: URI): Boolean {
        val scope = configuredApiUrl.path.trimEnd('/').ifEmpty { "/" }
        return scope == "/" || candidate.path.startsWith("$scope/")
    }

    private fun clientsFor(apiBaseUrl: URI): GeneratedApiClients =
        clientsByBaseUrl.computeIfAbsent(normalizedBaseUrl(apiBaseUrl), ::createClients)

    private fun createClients(baseUrl: String): GeneratedApiClients {
        val config: (HttpClientConfig<*>) -> Unit = { client ->
            client.followRedirects = false
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
            commits = CommitsApi(baseUrl, engine, config, jsonBlock),
            users = UsersApi(baseUrl, engine, config, jsonBlock),
            workspaces = WorkspacesApi(baseUrl, engine, config, jsonBlock),
            repositories = RepositoriesApi(baseUrl, engine, config, jsonBlock),
            pullRequests = PullRequestsApi(baseUrl, engine, config, jsonBlock),
            refs = RefsApi(baseUrl, engine, config, jsonBlock),
        )
    }

    private fun normalizedBaseUrl(apiBaseUrl: URI): String {
        if (
            !apiBaseUrl.isAbsolute || apiBaseUrl.isOpaque || apiBaseUrl.host == null ||
            apiBaseUrl.userInfo != null ||
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

    private fun unsafePaginationFailure(): GatewayResult.Failure =
        failure(GatewayFailureCategory.UNSAFE_PAGINATION, retryable = false)

    private fun failure(
        category: GatewayFailureCategory,
        retryable: Boolean,
        retryAt: Instant? = null,
    ): GatewayResult.Failure = GatewayResult.Failure(GatewayFailure(category, retryable, retryAt))

    private data class GeneratedApiClients(
        val commits: CommitsApi,
        val users: UsersApi,
        val workspaces: WorkspacesApi,
        val repositories: RepositoriesApi,
        val pullRequests: PullRequestsApi,
        val refs: RefsApi,
    )

    private data class PullRequestDetailSeed(
        val generated: com.mindtable.bitbuckethelper.adapter.outbound.bitbucket.generated.model.Pullrequest,
        val raw: ObjectNode,
        val coordinates: PullRequestReadinessCoordinates,
    )

    private data class PullRequestPage(
        val values: Set<com.mindtable.bitbuckethelper.adapter.outbound.bitbucket.generated.model.Pullrequest>,
        val next: URI?,
    )

    private class UnsafePaginationException : RuntimeException()

    companion object {
        private const val MAXIMUM_PULL_REQUEST_PAGES = 100
        private const val MAXIMUM_PATH_DECODING_PASSES = 4

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

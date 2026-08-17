package com.mindtable.bitbuckethelper.adapter.inbound.http

import com.mindtable.bitbuckethelper.application.model.AddRepositoryCommand
import com.mindtable.bitbuckethelper.application.model.ConfigureWorkspaceCommand
import com.mindtable.bitbuckethelper.application.model.RemoveRepositoryCommand
import com.mindtable.bitbuckethelper.application.port.inbound.AddRepository
import com.mindtable.bitbuckethelper.application.port.inbound.ConfigureWorkspace
import com.mindtable.bitbuckethelper.application.port.inbound.GetWorkspaceConfiguration
import com.mindtable.bitbuckethelper.application.port.inbound.RemoveRepository
import com.mindtable.bitbuckethelper.domain.configuration.InstallationConfiguration
import com.mindtable.bitbuckethelper.domain.shared.RepositoryId
import com.mindtable.bitbuckethelper.generated.api.v1.model.AddRepositoryRequest
import com.mindtable.bitbuckethelper.generated.api.v1.model.ConfigureWorkspaceRequest
import io.ktor.server.application.call
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import java.net.URI
import java.net.URISyntaxException

data class ConfigurationApiV1Dependencies(
    val getWorkspaceConfiguration: GetWorkspaceConfiguration,
    val configureWorkspace: ConfigureWorkspace,
    val addRepository: AddRepository,
    val removeRepository: RemoveRepository,
)

fun Route.installConfigurationRoutes(dependencies: ConfigurationApiV1Dependencies) {
    get("/configuration/workspace") {
        val result = dependencies.getWorkspaceConfiguration()
        call.respondApiV1 { requestId -> result.toGetWorkspaceConfigurationResponse(requestId) }
    }
    put("/configuration/workspace") {
        val request = call.receiveApiV1<ConfigureWorkspaceRequest>()
        val command = ConfigureWorkspaceCommand(
            bitbucketApiBaseUrl = request.bitbucketApiBaseUrl.toApiBaseUrl(),
            workspaceSlug = request.workspaceSlug.toWorkspaceSlug(),
        )
        val result = dependencies.configureWorkspace(command)
        call.respondApiV1 { requestId -> result.toConfigureWorkspaceResponse(requestId) }
    }
    post("/configuration/workspace/repositories") {
        val request = call.receiveApiV1<AddRepositoryRequest>()
        val result = dependencies.addRepository(AddRepositoryCommand(request.repositorySlug.toRepositorySlug()))
        call.respondApiV1 { requestId -> result.toAddRepositoryResponse(requestId) }
    }
    delete("/configuration/workspace/repositories/{repositoryId}") {
        val repositoryId = checkNotNull(call.parameters["repositoryId"]).toRepositoryId()
        val result = dependencies.removeRepository(RemoveRepositoryCommand(repositoryId))
        call.respondApiV1 { requestId -> result.toRemoveRepositoryResponse(requestId) }
    }
}

private fun String.toApiBaseUrl(): URI {
    return try {
        InstallationConfiguration.normalizeApiBaseUrl(URI(this))
    } catch (_: URISyntaxException) {
        throw InvalidApiRequestException(listOf(ApiRequestViolation.INVALID_BITBUCKET_API_BASE_URL))
    } catch (_: IllegalArgumentException) {
        throw InvalidApiRequestException(listOf(ApiRequestViolation.INVALID_BITBUCKET_API_BASE_URL))
    }
}

private fun String.toWorkspaceSlug(): String {
    if (!matches(SLUG_PATTERN)) {
        throw InvalidApiRequestException(listOf(ApiRequestViolation.INVALID_WORKSPACE_SLUG))
    }
    return this
}

private fun String.toRepositorySlug(): String {
    if (!matches(SLUG_PATTERN)) {
        throw InvalidApiRequestException(listOf(ApiRequestViolation.INVALID_REPOSITORY_SLUG))
    }
    return this
}

private fun String.toRepositoryId(): RepositoryId = try {
    RepositoryId(this)
} catch (_: IllegalArgumentException) {
    throw InvalidApiRequestException(listOf(ApiRequestViolation.INVALID_REPOSITORY_ID))
}

private val SLUG_PATTERN = Regex("[A-Za-z0-9._-]+")

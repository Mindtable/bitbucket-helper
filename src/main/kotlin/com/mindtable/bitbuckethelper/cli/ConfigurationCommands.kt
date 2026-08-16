package com.mindtable.bitbuckethelper.cli

import com.mindtable.bitbuckethelper.generated.api.v1.model.AddRepositoryRequest
import com.mindtable.bitbuckethelper.generated.api.v1.model.AddRepositoryResponse
import com.mindtable.bitbuckethelper.generated.api.v1.model.ApiVersion
import com.mindtable.bitbuckethelper.generated.api.v1.model.ConfigureWorkspaceRequest
import com.mindtable.bitbuckethelper.generated.api.v1.model.ConfigureWorkspaceResponse
import com.mindtable.bitbuckethelper.generated.api.v1.model.GetWorkspaceConfigurationResponse
import com.mindtable.bitbuckethelper.generated.api.v1.model.RepositoryAddedResult
import com.mindtable.bitbuckethelper.generated.api.v1.model.RepositoryAlreadyConfiguredResult
import com.mindtable.bitbuckethelper.generated.api.v1.model.RepositoryNotConfiguredResult
import com.mindtable.bitbuckethelper.generated.api.v1.model.RepositoryNotFoundResult
import com.mindtable.bitbuckethelper.generated.api.v1.model.RepositoryRemovedResult
import com.mindtable.bitbuckethelper.generated.api.v1.model.RepositoryResolutionUnavailableResult
import com.mindtable.bitbuckethelper.generated.api.v1.model.RemoveRepositoryResponse
import com.mindtable.bitbuckethelper.generated.api.v1.model.WorkspaceAlreadyConfiguredResult
import com.mindtable.bitbuckethelper.generated.api.v1.model.WorkspaceConfiguration
import com.mindtable.bitbuckethelper.generated.api.v1.model.WorkspaceConfigurationAvailableResult
import com.mindtable.bitbuckethelper.generated.api.v1.model.WorkspaceConfiguredResult
import com.mindtable.bitbuckethelper.generated.api.v1.model.WorkspaceIdentityMismatchResult
import com.mindtable.bitbuckethelper.generated.api.v1.model.WorkspaceNotConfiguredResult
import com.mindtable.bitbuckethelper.generated.api.v1.model.WorkspaceNotFoundResult
import com.mindtable.bitbuckethelper.generated.api.v1.model.WorkspaceResolutionUnavailableResult
import io.ktor.http.HttpStatusCode
import java.io.IOException
import java.net.URI
import kotlinx.serialization.SerializationException

/** Local-service commands for inspecting and establishing the immutable workspace identity. */
class WorkspaceCommands(
    private val client: LocalApiClient,
    private val output: CliOutput,
) {
    suspend fun show(mode: OutputMode): CliExit = executeConfiguration(output, mode) {
        val response = client.get(WORKSPACE_PATH, GetWorkspaceConfigurationResponse.serializer())
        when (val result = response.value?.result) {
            is WorkspaceConfigurationAvailableResult -> success(response) { renderWorkspace(result.configuration, it) }
            is WorkspaceNotConfiguredResult -> business(response) {
                "Workspace is not configured. Run ${result.setupCommand}."
            }
            null -> unavailable()
        }
    }

    suspend fun configure(apiBaseUrl: String, slug: String, mode: OutputMode): CliExit {
        if (!isApiBaseUrl(apiBaseUrl) || !SLUG.matches(slug)) {
            return CliExit.USAGE_ERROR
        }
        return executeConfiguration(output, mode) {
            val response = client.put(
                WORKSPACE_PATH,
                ConfigureWorkspaceRequest(ApiVersion._1, apiBaseUrl, slug),
                ConfigureWorkspaceRequest.serializer(),
                ConfigureWorkspaceResponse.serializer(),
            )
            when (val result = response.value?.result) {
                is WorkspaceConfiguredResult -> success(response) {
                    "Workspace ${result.configuration.workspaceDisplayName} configured."
                }
                is WorkspaceAlreadyConfiguredResult -> success(response) {
                    "Workspace ${result.configuration.workspaceDisplayName} is already configured."
                }
                is WorkspaceIdentityMismatchResult -> business(response) {
                    "Workspace identity cannot be changed in place. Current workspace: ${result.current.workspaceId}. Use the explicit reset or reconfigure workflow."
                }
                is WorkspaceNotFoundResult -> business(response) { "Workspace was not found." }
                is WorkspaceResolutionUnavailableResult -> business(response) {
                    "Workspace could not be resolved right now. Try again later."
                }
                null -> unavailable()
            }
        }
    }

    private fun isApiBaseUrl(value: String): Boolean = runCatching {
        URI(value).let { uri ->
            uri.isAbsolute &&
                uri.host != null &&
                uri.userInfo == null &&
                uri.query == null &&
                uri.fragment == null &&
                uri.scheme.lowercase() in setOf("http", "https")
        }
    }.getOrDefault(false)
}

/** Local-service commands for maintaining the configured repository allowlist. */
class RepositoryCommands(
    private val client: LocalApiClient,
    private val output: CliOutput,
) {
    suspend fun add(slug: String, mode: OutputMode): CliExit {
        if (!SLUG.matches(slug)) {
            return CliExit.USAGE_ERROR
        }
        return executeConfiguration(output, mode) {
            val response = client.post(
                REPOSITORIES_PATH,
                AddRepositoryRequest(ApiVersion._1, slug),
                AddRepositoryRequest.serializer(),
                AddRepositoryResponse.serializer(),
            )
            when (val result = response.value?.result) {
                is RepositoryAddedResult -> success(response) {
                    "Repository ${result.repository.displayName} added."
                }
                is RepositoryAlreadyConfiguredResult -> success(response) {
                    "Repository ${result.repository.displayName} is already configured."
                }
                is RepositoryNotFoundResult -> business(response) { "Repository was not found." }
                is RepositoryResolutionUnavailableResult -> business(response) {
                    "Repository could not be resolved right now. Try again later."
                }
                is WorkspaceNotConfiguredResult -> business(response) {
                    "Workspace is not configured. Run ${result.setupCommand}."
                }
                null -> unavailable()
            }
        }
    }

    suspend fun remove(repositoryId: String, mode: OutputMode): CliExit {
        if (!REPOSITORY_ID.matches(repositoryId)) {
            return CliExit.USAGE_ERROR
        }
        return executeConfiguration(output, mode) {
            val response = client.delete("$REPOSITORIES_PATH/$repositoryId", RemoveRepositoryResponse.serializer())
            when (val result = response.value?.result) {
                is RepositoryRemovedResult -> success(response) {
                    "Repository ${result.repositoryId} removed."
                }
                is RepositoryNotConfiguredResult -> business(response) {
                    "Repository ${result.repositoryId} is not configured."
                }
                null -> unavailable()
            }
        }
    }
}

private suspend fun executeConfiguration(
    output: CliOutput,
    mode: OutputMode,
    execute: suspend ConfigurationRenderScope.() -> CliExit,
): CliExit = try {
    ConfigurationRenderScope(output, mode).execute()
} catch (_: IOException) {
    output.render(mode, CliOutcome.serviceUnavailable())
} catch (_: LocalApiResponseTooLargeException) {
    output.render(mode, CliOutcome.serviceUnavailable())
} catch (_: SerializationException) {
    output.render(mode, CliOutcome.serviceUnavailable())
}

private class ConfigurationRenderScope(
    private val output: CliOutput,
    private val mode: OutputMode,
) {
    fun <Response> success(
        response: LocalApiResponse<Response>,
        human: (TerminalCapability) -> String,
    ): CliExit = render(response, CliExit.SUCCESS, human)

    fun <Response> business(
        response: LocalApiResponse<Response>,
        human: (TerminalCapability) -> String,
    ): CliExit = render(response, CliExit.BUSINESS_NOT_ACHIEVED, human)

    fun unavailable(): CliExit = output.render(mode, CliOutcome.serviceUnavailable())

    private fun <Response> render(
        response: LocalApiResponse<Response>,
        exit: CliExit,
        human: (TerminalCapability) -> String,
    ): CliExit = if (response.status == HttpStatusCode.OK && response.error == null) {
        output.render(mode, CliOutcome.api(response, exit, human))
    } else {
        unavailable()
    }
}

private fun renderWorkspace(configuration: WorkspaceConfiguration, terminal: TerminalCapability): String = buildString {
    appendLine(terminal.bold("Workspace: ${configuration.workspaceDisplayName}"))
    appendLine("ID: ${configuration.workspaceId}")
    appendLine("Slug: ${configuration.workspaceSlug}")
    appendLine("API base URL: ${configuration.bitbucketApiBaseUrl}")
    appendLine("URL: ${configuration.workspaceWebUrl}")
    appendLine("Retention: ${configuration.retentionDays} days")
    appendLine("Repositories:")
    if (configuration.repositories.isEmpty()) {
        append("  none")
    } else {
        configuration.repositories.forEach { repository ->
            appendLine("  Repository: ${repository.displayName} (${repository.repositoryId})")
            appendLine("    Slug: ${repository.slug}")
            appendLine("    URL: ${repository.webUrl}")
        }
    }
}.trimEnd()

private const val WORKSPACE_PATH = "/api/v1/configuration/workspace"
private const val REPOSITORIES_PATH = "/api/v1/configuration/workspace/repositories"
private val SLUG = Regex("^[A-Za-z0-9][A-Za-z0-9._-]*$")
private val REPOSITORY_ID = Regex("^repo_[A-Za-z0-9_-]+$")

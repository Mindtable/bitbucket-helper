package com.mindtable.bitbuckethelper.adapter.outbound.bitbucket

import com.mindtable.bitbuckethelper.adapter.outbound.bitbucket.generated.model.Account
import com.mindtable.bitbuckethelper.adapter.outbound.bitbucket.generated.model.Repository
import com.mindtable.bitbuckethelper.adapter.outbound.bitbucket.generated.model.Workspace
import com.mindtable.bitbuckethelper.application.model.GatewayRepositoryObservation
import com.mindtable.bitbuckethelper.application.model.GatewayUserObservation
import com.mindtable.bitbuckethelper.application.model.GatewayWorkspaceObservation
import com.mindtable.bitbuckethelper.domain.shared.RepositoryId
import com.mindtable.bitbuckethelper.domain.shared.WorkspaceId
import java.net.URI
import java.util.Locale
import java.util.UUID

internal fun Account.toGatewayUserObservation(): GatewayUserObservation =
    GatewayUserObservation(
        stableId = "{${uuid.requiredBitbucketUuid()}}",
        displayName = displayName.requiredText(),
        nickname = null,
    )

internal fun Workspace.toGatewayWorkspaceObservation(): GatewayWorkspaceObservation =
    GatewayWorkspaceObservation(
        id = WorkspaceId("ws_${uuid.requiredBitbucketUuid()}"),
        slug = slug.requiredText(),
        displayName = name.requiredText(),
        webUrl = links.requiredHtmlWebUrl(),
    )

internal fun Repository.toGatewayRepositoryObservation(): GatewayRepositoryObservation {
    val fullNameParts = fullName.requiredText().split('/')
    if (fullNameParts.size != 2 || fullNameParts.any { it.isBlank() }) {
        throw IdentityMappingException()
    }

    return GatewayRepositoryObservation(
        id = RepositoryId("repo_${uuid.requiredBitbucketUuid()}"),
        workspaceId = WorkspaceId("ws_${owner?.uuid.requiredBitbucketUuid()}"),
        slug = fullNameParts[1],
        displayName = name.requiredText(),
        webUrl = links.requiredHtmlWebUrl(),
    )
}

private fun String?.requiredText(): String =
    takeIf { !it.isNullOrBlank() } ?: throw IdentityMappingException()

private fun String?.requiredBitbucketUuid(): String {
    val source = requiredText()
    val candidate = when {
        source.startsWith('{') && source.endsWith('}') && source.length > 2 -> source.substring(1, source.length - 1)
        source.startsWith('{') || source.endsWith('}') -> throw IdentityMappingException()
        else -> source
    }
    return try {
        UUID.fromString(candidate).toString().also { canonical ->
            if (!candidate.equals(canonical, ignoreCase = true)) {
                throw IdentityMappingException()
            }
        }
    } catch (_: IllegalArgumentException) {
        throw IdentityMappingException()
    }
}

private fun Any?.requiredHtmlWebUrl(): URI {
    val links = this as? Map<*, *> ?: throw IdentityMappingException()
    val html = links["html"] as? Map<*, *> ?: throw IdentityMappingException()
    val href = html["href"] as? String ?: throw IdentityMappingException()
    val uri = try {
        URI(href)
    } catch (_: IllegalArgumentException) {
        throw IdentityMappingException()
    }
    if (
        !uri.isAbsolute || uri.isOpaque || uri.host == null ||
        uri.scheme.lowercase(Locale.ROOT) !in setOf("http", "https")
    ) {
        throw IdentityMappingException()
    }
    return uri
}

internal class IdentityMappingException : RuntimeException()

package com.mindtable.bitbuckethelper.adapter.outbound.bitbucket

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode
import com.mindtable.bitbuckethelper.adapter.outbound.bitbucket.generated.model.Account
import com.mindtable.bitbuckethelper.adapter.outbound.bitbucket.generated.model.Pullrequest
import com.mindtable.bitbuckethelper.adapter.outbound.bitbucket.generated.model.Repository
import com.mindtable.bitbuckethelper.adapter.outbound.bitbucket.generated.model.Workspace
import com.mindtable.bitbuckethelper.application.model.GatewayActivityKind
import com.mindtable.bitbuckethelper.application.model.GatewayActivityObservation
import com.mindtable.bitbuckethelper.application.model.GatewayBuildObservation
import com.mindtable.bitbuckethelper.application.model.GatewayBuildStatus
import com.mindtable.bitbuckethelper.application.model.GatewayLiveActivityContent
import com.mindtable.bitbuckethelper.application.model.GatewayPullRequestDetail
import com.mindtable.bitbuckethelper.application.model.GatewayPullRequestSummary
import com.mindtable.bitbuckethelper.application.model.GatewayRepositoryObservation
import com.mindtable.bitbuckethelper.application.model.GatewayTaskObservation
import com.mindtable.bitbuckethelper.application.model.GatewayUserObservation
import com.mindtable.bitbuckethelper.application.model.GatewayWorkspaceObservation
import com.mindtable.bitbuckethelper.domain.shared.ActivityVersion
import com.mindtable.bitbuckethelper.domain.shared.RepositoryId
import com.mindtable.bitbuckethelper.domain.shared.WorkspaceId
import java.net.URI
import java.time.Instant
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

internal fun Pullrequest.toGatewayPullRequestSummary(repositoryId: RepositoryId): GatewayPullRequestSummary {
    if (state != Pullrequest.State.OPEN) {
        throw IdentityMappingException()
    }
    val author = author ?: throw IdentityMappingException()

    return GatewayPullRequestSummary(
        repositoryId = repositoryId,
        upstreamNumber = id?.toLong()?.takeIf { it > 0 } ?: throw IdentityMappingException(),
        title = title.requiredText(),
        authorStableId = author.uuid.requiredBitbucketStableId(),
        authorDisplayName = author.displayName.requiredText(),
        draft = draft ?: false,
        headCommit = source?.commit.requiredCommitHash().requiredBitbucketCommitHash(),
        webUrl = links.requiredHtmlWebUrl(),
        createdAt = createdOn?.toInstant() ?: throw IdentityMappingException(),
        updatedAt = updatedOn?.toInstant() ?: throw IdentityMappingException(),
    )
}

internal fun Pullrequest.toGatewayPullRequestDetail(
    repositoryId: RepositoryId,
    raw: ObjectNode,
    destinationBranchIsCurrent: Boolean,
    hasMergeConflicts: Boolean,
): GatewayPullRequestDetail {
    val summary = toGatewayPullRequestSummary(repositoryId)
    val participants = participants ?: throw IdentityMappingException()
    val approvedBy = participants.filter { it.approved == true }.map { participant ->
        participant.user?.uuid.requiredBitbucketStableId()
    }.toSet()
    val changesRequested = participants.any { participant ->
        participant.state?.value == "changes_requested"
    }
    val unresolvedComments = raw.requiredNonNegativeInt("unresolved_comment_count")

    return GatewayPullRequestDetail(
        repositoryId = summary.repositoryId,
        upstreamNumber = summary.upstreamNumber,
        title = summary.title,
        authorStableId = summary.authorStableId,
        authorDisplayName = summary.authorDisplayName,
        draft = summary.draft,
        headCommit = summary.headCommit,
        webUrl = summary.webUrl,
        createdAt = summary.createdAt,
        updatedAt = summary.updatedAt,
        approvalCount = approvedBy.size,
        approvedByStableIds = approvedBy,
        hasChangesRequested = changesRequested,
        unresolvedCommentCount = unresolvedComments,
        destinationBranchIsCurrent = destinationBranchIsCurrent,
        hasMergeConflicts = hasMergeConflicts,
    )
}

internal data class PullRequestReadinessCoordinates(
    val destinationBranchName: String,
    val observedDestinationCommit: String,
    val sourceCommit: String,
)

internal fun ObjectNode.toPullRequestReadinessCoordinates(): PullRequestReadinessCoordinates {
    val sourceCommit = requiredObject("source").requiredObject("commit")
        .requiredText("hash").requiredBitbucketCommitHash()
    val destination = requiredObject("destination")
    return PullRequestReadinessCoordinates(
        destinationBranchName = destination.requiredObject("branch")
            .requiredText("name").requiredBitbucketBranchName(),
        observedDestinationCommit = destination.requiredObject("commit")
            .requiredText("hash").requiredBitbucketCommitHash(),
        sourceCommit = sourceCommit,
    )
}

internal data class BranchTargetObservation(val name: String, val targetCommit: String)

internal fun ObjectNode.toBranchTargetObservation(): BranchTargetObservation {
    if (requiredText("type") != "branch") {
        throw IdentityMappingException()
    }
    return BranchTargetObservation(
        name = requiredText("name").requiredBitbucketBranchName(),
        targetCommit = requiredObject("target").requiredText("hash").requiredBitbucketCommitHash(),
    )
}

internal fun ObjectNode.toMergeBaseCommit(): String {
    if (requiredText("type") != "commit") throw IdentityMappingException()
    return requiredText("hash").requiredBitbucketCommitHash()
}

internal fun ObjectNode.toFileConflictMarker() {
    if (requiredText("type") != "file_conflict") {
        throw IdentityMappingException()
    }
}

internal fun ObjectNode.toGatewayDefaultReviewer(): GatewayUserObservation {
    val user = requiredObject("user")
    return GatewayUserObservation(
        stableId = user.requiredText("uuid").requiredBitbucketStableId(),
        displayName = user.requiredText("display_name"),
        nickname = user.optionalText("nickname"),
    )
}

internal fun ObjectNode.toGatewayBuildObservation(): GatewayBuildObservation {
    val status = when (requiredText("state").uppercase(Locale.ROOT)) {
        "SUCCESSFUL" -> GatewayBuildStatus.SUCCESSFUL
        "FAILED" -> GatewayBuildStatus.FAILED
        "STOPPED" -> GatewayBuildStatus.STOPPED
        "INPROGRESS", "IN_PROGRESS" -> GatewayBuildStatus.IN_PROGRESS
        else -> GatewayBuildStatus.UNKNOWN
    }
    return GatewayBuildObservation(
        key = requiredText("key"),
        status = status,
        observedAt = requiredInstant("updated_on"),
    )
}

internal fun ObjectNode.toGatewayTaskObservation(): GatewayTaskObservation {
    val resolved = when (requiredText("state")) {
        "RESOLVED" -> true
        "UNRESOLVED" -> false
        else -> throw IdentityMappingException()
    }
    return GatewayTaskObservation(
        key = requiredPositiveLong("id").toString(),
        resolved = resolved,
        observedAt = requiredInstant("updated_on"),
    )
}

internal fun ObjectNode.toGatewayActivityObservation(): GatewayActivityObservation? = when {
    has("comment") -> requiredObject("comment").toGatewayCommentActivity()
    has("changes_request") -> requiredObject("changes_request").toGatewayChangesRequestedActivity(this)
    else -> null
}

internal fun ObjectNode.toGatewayLiveActivityContent(fetchedAt: Instant): GatewayLiveActivityContent {
    val id = requiredPositiveLong("id")
    val updatedAt = requiredInstant("updated_on")
    val deleted = requiredBoolean("deleted")
    val resolved = get("resolution")?.let { !it.isNull } ?: false
    val sourceKind = if (get("parent")?.let { !it.isNull } == true) {
        requiredObject("parent").requiredPositiveLong("id")
        GatewayActivityKind.REPLY
    } else {
        GatewayActivityKind.COMMENT
    }
    val markdown = requiredObject("content").requiredText("raw")
    return GatewayLiveActivityContent(
        activityVersion = commentVersion(id, updatedAt, deleted, resolved, sourceKind),
        markdown = markdown,
        fetchedAt = fetchedAt,
    )
}

private fun ObjectNode.toGatewayCommentActivity(): GatewayActivityObservation {
    val id = requiredPositiveLong("id")
    val actor = requiredObject("user")
    val updatedAt = requiredInstant("updated_on")
    val deleted = requiredBoolean("deleted")
    val resolved = get("resolution")?.let { !it.isNull } ?: false
    val parentId = get("parent")?.takeUnless { it.isNull }?.let {
        requiredObject("parent").requiredPositiveLong("id")
    }
    val sourceKind = if (parentId == null) GatewayActivityKind.COMMENT else GatewayActivityKind.REPLY
    return GatewayActivityObservation(
        sourceKind = sourceKind,
        sourceId = (parentId ?: id).toString(),
        actorStableId = actor.requiredText("uuid").requiredBitbucketStableId(),
        actorDisplayName = actor.requiredText("display_name"),
        activityAt = updatedAt,
        activityVersion = commentVersion(id, updatedAt, deleted, resolved, sourceKind),
        resolved = resolved,
        deleted = deleted,
        webUrl = requiredWebUrl(expectedCommentId = id),
    )
}

private fun ObjectNode.toGatewayChangesRequestedActivity(event: ObjectNode): GatewayActivityObservation {
    val actor = requiredObject("user")
    val actorStableId = actor.requiredText("uuid").requiredBitbucketStableId()
    val actorToken = actorStableId.removeSurrounding("{", "}")
    val activityAt = requiredInstant("date")
    val versionEpoch = activityAt.toEpochMilli()
    return GatewayActivityObservation(
        sourceKind = GatewayActivityKind.CHANGES_REQUESTED,
        sourceId = "changes-request-$actorToken-$versionEpoch",
        actorStableId = actorStableId,
        actorDisplayName = actor.requiredText("display_name"),
        activityAt = activityAt,
        activityVersion = ActivityVersion("av_changes-request-$actorToken-$versionEpoch"),
        resolved = false,
        deleted = false,
        webUrl = event.requiredObject("pull_request").requiredWebUrl(),
    )
}

private fun commentVersion(
    id: Long,
    updatedAt: Instant,
    deleted: Boolean,
    resolved: Boolean,
    kind: GatewayActivityKind,
): ActivityVersion {
    val kindToken = when (kind) {
        GatewayActivityKind.COMMENT -> "comment"
        GatewayActivityKind.REPLY -> "reply"
        GatewayActivityKind.CHANGES_REQUESTED -> throw IdentityMappingException()
    }
    return ActivityVersion(
        "av_$kindToken-$id-${updatedAt.toEpochMilli()}-d${deleted.asInt()}-r${resolved.asInt()}",
    )
}

private fun Boolean.asInt(): Int = if (this) 1 else 0

private fun JsonNode.requiredObject(name: String): ObjectNode =
    get(name) as? ObjectNode ?: throw IdentityMappingException()

private fun JsonNode.requiredText(name: String): String =
    get(name)?.takeIf { it.isTextual }?.textValue().requiredText()

private fun JsonNode.optionalText(name: String): String? {
    val value = get(name) ?: return null
    if (value.isNull) return null
    return value.takeIf { it.isTextual }?.textValue()?.takeIf { it.isNotBlank() } ?: throw IdentityMappingException()
}

private fun JsonNode.requiredPositiveLong(name: String): Long =
    get(name)?.takeIf { it.isIntegralNumber }?.longValue()?.takeIf { it > 0 } ?: throw IdentityMappingException()

private fun JsonNode.requiredNonNegativeInt(name: String): Int =
    optionalNonNegativeInt(name) ?: throw IdentityMappingException()

private fun JsonNode.optionalNonNegativeInt(name: String): Int? {
    val value = get(name) ?: return null
    if (value.isNull) return null
    return value.takeIf { it.isIntegralNumber }?.intValue()?.takeIf { it >= 0 } ?: throw IdentityMappingException()
}

private fun JsonNode.requiredBoolean(name: String): Boolean =
    get(name)?.takeIf { it.isBoolean }?.booleanValue() ?: throw IdentityMappingException()

private fun JsonNode.requiredInstant(name: String): Instant = try {
    Instant.parse(requiredText(name))
} catch (_: Exception) {
    throw IdentityMappingException()
}

private fun JsonNode.requiredWebUrl(expectedCommentId: Long? = null): URI {
    val href = requiredObject("links").requiredObject("html").requiredText("href")
    val uri = try {
        URI(href)
    } catch (_: Exception) {
        throw IdentityMappingException()
    }
    if (
        !uri.isAbsolute || uri.isOpaque || uri.host == null || uri.userInfo != null ||
        uri.rawQuery != null || uri.scheme != "https"
    ) {
        throw IdentityMappingException()
    }
    if (expectedCommentId != null && uri.rawFragment != "comment-$expectedCommentId") {
        throw IdentityMappingException()
    }
    return uri
}

private fun String?.requiredText(): String =
    takeIf { !it.isNullOrBlank() } ?: throw IdentityMappingException()

private fun String.requiredBitbucketCommitHash(): String =
    takeIf { BITBUCKET_COMMIT_HASH.matches(it) }
        ?.lowercase(Locale.ROOT)
        ?: throw IdentityMappingException()

private fun String.requiredBitbucketBranchName(): String {
    val segments = split('/')
    return takeIf {
        length <= 255 && segments.none { segment -> segment.isEmpty() || segment == "." || segment == ".." } &&
            none { character ->
                character == '?' || character == '#' || character == '\\' || Character.isISOControl(character)
            }
    } ?: throw IdentityMappingException()
}

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

internal fun String?.requiredBitbucketStableId(): String = "{${requiredBitbucketUuid()}}"

private fun Any?.requiredCommitHash(): String {
    val commit = this as? Map<*, *> ?: throw IdentityMappingException()
    return (commit["hash"] as? String).requiredText()
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
        !uri.isAbsolute || uri.isOpaque || uri.host == null || uri.userInfo != null ||
        uri.rawQuery != null || uri.rawFragment != null ||
        uri.scheme != "https"
    ) {
        throw IdentityMappingException()
    }
    return uri
}

internal class IdentityMappingException : RuntimeException()

private val BITBUCKET_COMMIT_HASH = Regex("[0-9a-fA-F]{6,64}")

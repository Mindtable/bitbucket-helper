package com.mindtable.bitbuckethelper.domain.shared

private val opaqueIdentifierSuffix = Regex("[A-Za-z0-9_-]+")

private fun requireOpaqueIdentifier(value: String, prefix: String) {
    require(value.startsWith(prefix)) { "Identifier must start with $prefix" }
    require(value.removePrefix(prefix).matches(opaqueIdentifierSuffix)) {
        "Identifier must contain a non-empty URL-safe opaque suffix"
    }
}

@JvmInline
value class WorkspaceId(val value: String) {
    init { requireOpaqueIdentifier(value, "ws_") }
}

@JvmInline
value class RepositoryId(val value: String) {
    init { requireOpaqueIdentifier(value, "repo_") }
}

@JvmInline
value class PullRequestId(val value: String) {
    init { requireOpaqueIdentifier(value, "pr_") }
}

@JvmInline
value class ActionItemId(val value: String) {
    init { requireOpaqueIdentifier(value, "ai_") }
}

@JvmInline
value class ActivityVersion(val value: String) {
    init { requireOpaqueIdentifier(value, "av_") }
}

@JvmInline
value class BuildGreenTransitionId(val value: String) {
    init { requireOpaqueIdentifier(value, "bgt_") }
}

@JvmInline
value class RefreshRunId(val value: String) {
    init { requireOpaqueIdentifier(value, "rr_") }
}

@JvmInline
value class NotificationIntentId(val value: String) {
    init { requireOpaqueIdentifier(value, "ni_") }
}

@JvmInline
value class NotificationAttemptId(val value: String) {
    init { requireOpaqueIdentifier(value, "na_") }
}

@JvmInline
value class DashboardRevision(val value: String) {
    init { requireOpaqueIdentifier(value, "dr_") }
}

@JvmInline
value class RepositoryRevision(val value: String) {
    init { requireOpaqueIdentifier(value, "rrev_") }
}

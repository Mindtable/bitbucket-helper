package com.mindtable.bitbuckethelper.application.contract

import com.mindtable.bitbuckethelper.application.model.*
import com.mindtable.bitbuckethelper.domain.shared.*
import java.net.URI
import java.time.Instant

val t0: Instant = Instant.parse("2026-08-15T08:00:00Z")
    val repositoryA = RepositoryId("repo_alpha")
    val repositoryB = RepositoryId("repo_beta")
    val pullRequestA = PullRequestId("pr_alpha")
    val pullRequestB = PullRequestId("pr_beta")
    val actionItemA = ActionItemId("ai_alpha")
    val actionItemB = ActionItemId("ai_beta")
    val versionA = ActivityVersion("av_alpha")
    val versionB = ActivityVersion("av_beta")

    fun configuration(repositories: List<StoredConfiguredRepository> = listOf(repository(repositoryA), repository(repositoryB))) =
        StoredInstallationConfiguration(
            WorkspaceId("ws_alpha"), URI("https://api.bitbucket.org"), "workspace", "Workspace",
            URI("https://bitbucket.org/workspace"), "user-1", "User One", t0, 30, repositories,
        )

    fun repository(id: RepositoryId, removedAt: Instant? = null) = StoredConfiguredRepository(
        id, WorkspaceId("ws_alpha"), id.value, id.value.uppercase(), URI("https://bitbucket.org/workspace/${id.value}"), removedAt,
    )

    fun pullRequest(id: PullRequestId = pullRequestA, repositoryId: RepositoryId = repositoryA, number: Long = 1, active: Boolean = true, inactiveAt: Instant? = null) =
        StoredPullRequestSnapshot(
            id, repositoryId, number, "PR $number", "author-$number", "Author $number", false, "commit-$number",
            URI("https://bitbucket.org/workspace/repo/pull-requests/$number"), t0, t0, t0, active, inactiveAt,
            StoredReadiness.Available(1, 1, listOf(StoredReadinessCheck("review", true, null))),
            listOf(StoredBuildObservation("build", BuildState.SUCCESSFUL, t0)), true,
        )

    fun actionItem(id: ActionItemId = actionItemA, pullRequestId: PullRequestId = pullRequestA, repositoryId: RepositoryId = repositoryA,
                   version: ActivityVersion = versionA, state: ActionItemState = ActionItemState.OPEN,
                   acknowledgedVersion: ActivityVersion? = null, acknowledgedAt: Instant? = null) = StoredActionItemSnapshot(
        id, pullRequestId, repositoryId, "COMMENT", id.value, "actor", "Actor", t0, t0, version, state,
        acknowledgedVersion, acknowledgedAt, URI("https://bitbucket.org/workspace/repo/pull-requests/1#comment"),
    )

    fun synchronization(repositoryId: RepositoryId = repositoryA) = StoredSynchronizationSnapshot(
        repositoryId, SynchronizationActivity.IDLE, t0, SynchronizationAttemptOutcome.SUCCEEDED, t0, t0,
        SynchronizationProblem.None, 0, null, "pr-cursor", "activity-cursor",
    )

    fun intent(id: NotificationIntentId = NotificationIntentId("ni_alpha"), createdAt: Instant = t0, nextAttemptAt: Instant? = t0) = StoredNotificationIntent(
        id, NotificationRequest(NotificationDeliveryKey("delivery-${id.value}"), "Title", "Body", null, NotificationSound.DEFAULT),
        createdAt, NotificationIntentState.PENDING, 0, nextAttemptAt, null,
    )

fun attempt(intentId: NotificationIntentId = NotificationIntentId("ni_alpha"), number: Int = 1) = StoredNotificationAttempt(
        NotificationAttemptId("na_$number"), intentId, number, t0.plusSeconds(number.toLong()), NotificationDeliveryResult.Accepted,
    )

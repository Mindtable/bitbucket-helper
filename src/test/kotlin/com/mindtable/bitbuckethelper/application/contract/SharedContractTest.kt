package com.mindtable.bitbuckethelper.application.contract

import com.mindtable.bitbuckethelper.application.model.AcknowledgeActionItemResult
import com.mindtable.bitbuckethelper.application.model.ActivePollingAdvice
import com.mindtable.bitbuckethelper.application.model.AddRepositoryResult
import com.mindtable.bitbuckethelper.application.model.ActionItemProjection
import com.mindtable.bitbuckethelper.application.model.ActionItemState
import com.mindtable.bitbuckethelper.application.model.ActorProjection
import com.mindtable.bitbuckethelper.application.model.BuildState
import com.mindtable.bitbuckethelper.application.model.ConfigureWorkspaceResult
import com.mindtable.bitbuckethelper.application.model.DashboardResult
import com.mindtable.bitbuckethelper.application.model.Freshness
import com.mindtable.bitbuckethelper.application.model.GatewayFailure
import com.mindtable.bitbuckethelper.application.model.GatewayFailureCategory
import com.mindtable.bitbuckethelper.application.model.GatewayLiveActivityContent
import com.mindtable.bitbuckethelper.application.model.GatewayPullRequestDetail
import com.mindtable.bitbuckethelper.application.model.GatewayResult
import com.mindtable.bitbuckethelper.application.model.GetRefreshRunResult
import com.mindtable.bitbuckethelper.application.model.GetInboxResult
import com.mindtable.bitbuckethelper.application.model.GetPullRequestResult
import com.mindtable.bitbuckethelper.application.model.GetSynchronizationStatusResult
import com.mindtable.bitbuckethelper.application.model.GetWorkspaceConfigurationResult
import com.mindtable.bitbuckethelper.application.model.ListPullRequestsResult
import com.mindtable.bitbuckethelper.application.model.LiveActivityContentResult
import com.mindtable.bitbuckethelper.application.model.NewNotificationIntent
import com.mindtable.bitbuckethelper.application.model.NotificationDeliveryFailureCategory
import com.mindtable.bitbuckethelper.application.model.NotificationDeliveryKey
import com.mindtable.bitbuckethelper.application.model.NotificationDeliveryResult
import com.mindtable.bitbuckethelper.application.model.NotificationIntentState
import com.mindtable.bitbuckethelper.application.model.NotificationAttemptCompletion
import com.mindtable.bitbuckethelper.application.model.NotificationLease
import com.mindtable.bitbuckethelper.application.model.NotificationRequest
import com.mindtable.bitbuckethelper.application.model.NotificationSound
import com.mindtable.bitbuckethelper.application.model.NotificationTransitionFact
import com.mindtable.bitbuckethelper.application.model.NotificationIntentInsertResult
import com.mindtable.bitbuckethelper.application.model.PartialFailureMetadata
import com.mindtable.bitbuckethelper.application.model.RefreshRepositoryResult
import com.mindtable.bitbuckethelper.application.model.RefreshRunSnapshot
import com.mindtable.bitbuckethelper.application.model.RefreshRunRepositoryEntry
import com.mindtable.bitbuckethelper.application.model.ReminderActionItemProjection
import com.mindtable.bitbuckethelper.application.model.ReminderRepositoryProjection
import com.mindtable.bitbuckethelper.application.model.RemoveRepositoryResult
import com.mindtable.bitbuckethelper.application.model.StartRefreshRunResult
import com.mindtable.bitbuckethelper.application.model.StoredActionItemSnapshot
import com.mindtable.bitbuckethelper.application.model.StoredAcknowledgmentResult
import com.mindtable.bitbuckethelper.application.model.StoredBuildObservation
import com.mindtable.bitbuckethelper.application.model.StoredConfiguredRepository
import com.mindtable.bitbuckethelper.application.model.StoredInstallationConfiguration
import com.mindtable.bitbuckethelper.application.model.StoredNotificationIntent
import com.mindtable.bitbuckethelper.application.model.StoredNotificationAttempt
import com.mindtable.bitbuckethelper.application.model.StoredPullRequestSnapshot
import com.mindtable.bitbuckethelper.application.model.StoredReadiness
import com.mindtable.bitbuckethelper.application.model.StoredSynchronizationSnapshot
import com.mindtable.bitbuckethelper.application.model.SynchronizationActivity
import com.mindtable.bitbuckethelper.application.model.SynchronizationAttemptOutcome
import com.mindtable.bitbuckethelper.application.model.SynchronizationFailure
import com.mindtable.bitbuckethelper.application.model.SynchronizationFailureCategory
import com.mindtable.bitbuckethelper.application.model.SynchronizationProblem
import com.mindtable.bitbuckethelper.domain.shared.ActionItemId
import com.mindtable.bitbuckethelper.domain.shared.ActivityVersion
import com.mindtable.bitbuckethelper.domain.shared.BuildGreenTransitionId
import com.mindtable.bitbuckethelper.domain.shared.DashboardRevision
import com.mindtable.bitbuckethelper.domain.shared.NotificationAttemptId
import com.mindtable.bitbuckethelper.domain.shared.NotificationIntentId
import com.mindtable.bitbuckethelper.domain.shared.PullRequestId
import com.mindtable.bitbuckethelper.domain.shared.RefreshRunId
import com.mindtable.bitbuckethelper.domain.shared.RepositoryId
import com.mindtable.bitbuckethelper.domain.shared.RepositoryRevision
import com.mindtable.bitbuckethelper.domain.shared.WorkspaceId
import com.mindtable.bitbuckethelper.application.port.outbound.NotificationIntentStore
import java.net.URI
import java.time.Duration
import java.time.Instant
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SharedContractTest {
    @Test
    fun `opaque identifiers require their URL-safe prefix`() {
        val values = listOf(
            WorkspaceId("ws_workspace-01").value,
            RepositoryId("repo_repository-01").value,
            PullRequestId("pr_pull-request-01").value,
            ActionItemId("ai_action-item-01").value,
            ActivityVersion("av_activity-version-01").value,
            BuildGreenTransitionId("bgt_green-edge-01").value,
            RefreshRunId("rr_refresh-run-01").value,
            NotificationIntentId("ni_notification-intent-01").value,
            NotificationAttemptId("na_notification-attempt-01").value,
            DashboardRevision("dr_dashboard-revision-01").value,
            RepositoryRevision("rrev_repository-revision-01").value,
        )

        assertTrue(values.all { it.matches(Regex("[A-Za-z0-9_-]+")) })
        assertThrows(IllegalArgumentException::class.java) { RepositoryId("pr_wrong-prefix") }
        assertThrows(IllegalArgumentException::class.java) { ActivityVersion("av_not/url-safe") }
        assertThrows(IllegalArgumentException::class.java) { RefreshRunId("rr_") }
        assertThrows(IllegalArgumentException::class.java) { NotificationAttemptId("ni_wrong-prefix") }
        assertThrows(IllegalArgumentException::class.java) { BuildGreenTransitionId("av_wrong-prefix") }
    }

    @Test
    fun `stored snapshots retain only complete safe synchronization state`() {
        val attemptedAt = Instant.parse("2026-08-15T09:00:00Z")
        val snapshotAt = Instant.parse("2026-08-15T08:55:00Z")
        val retryAt = Instant.parse("2026-08-15T09:05:00Z")
        val failure = SynchronizationFailure(
            category = SynchronizationFailureCategory.RATE_LIMITED,
            retryable = true,
            retryAt = retryAt,
        )
        val partial = PartialFailureMetadata(
            attemptedCount = 5,
            succeededCount = 3,
            failures = listOf(failure, failure),
        )
        val synchronization = StoredSynchronizationSnapshot(
            repositoryId = RepositoryId("repo_alpha"),
            activity = SynchronizationActivity.IDLE,
            lastAttemptAt = attemptedAt,
            lastAttemptOutcome = SynchronizationAttemptOutcome.PARTIAL_FAILURE,
            lastSuccessAt = snapshotAt,
            snapshotAt = snapshotAt,
            problem = SynchronizationProblem.Present(partial),
            consecutiveFailureCount = 1,
            backoffUntil = retryAt,
            pullRequestCursor = "cursor-pr-2",
            activityCursor = "cursor-activity-9",
        )

        assertEquals(snapshotAt, synchronization.snapshotAt)
        assertEquals(2, (synchronization.problem as SynchronizationProblem.Present).metadata.failedCount)
        assertEquals(retryAt, synchronization.backoffUntil)
    }

    @Test
    fun `freshness represents never fresh and stale without nullable state guessing`() {
        val snapshotAt = Instant.parse("2026-08-15T08:00:00Z")
        val staleSince = Instant.parse("2026-08-15T08:10:00Z")

        val values: List<Freshness> = listOf(
            Freshness.NeverSynchronized,
            Freshness.Fresh(snapshotAt, Duration.ofMinutes(2)),
            Freshness.Stale(snapshotAt, Duration.ofMinutes(20), staleSince),
        )

        assertEquals(listOf("never", "fresh", "stale"), values.map(::freshnessKind))
    }

    @Test
    fun `partial failure counts reject impossible combinations`() {
        assertThrows(IllegalArgumentException::class.java) {
            PartialFailureMetadata(-1, 0, emptyList())
        }
        assertThrows(IllegalArgumentException::class.java) {
            PartialFailureMetadata(1, 2, emptyList())
        }
        assertThrows(IllegalArgumentException::class.java) {
            PartialFailureMetadata(1, 0, emptyList())
        }
        assertThrows(IllegalArgumentException::class.java) {
            PartialFailureMetadata(0, 0, emptyList())
        }
        assertThrows(IllegalArgumentException::class.java) {
            PartialFailureMetadata(
                attemptedCount = 1,
                succeededCount = 1,
                failures = listOf(
                    SynchronizationFailure(SynchronizationFailureCategory.NETWORK, true, null),
                ),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            PartialFailureMetadata(
                attemptedCount = 3,
                succeededCount = 1,
                failures = listOf(
                    SynchronizationFailure(SynchronizationFailureCategory.NETWORK, true, null),
                ),
            )
        }
    }

    @Test
    fun `notification intent preserves immutable delivery identity and a finite lease`() {
        val createdAt = Instant.parse("2026-08-15T09:00:00Z")
        val request = NotificationRequest(
            deliveryKey = NotificationDeliveryKey("reminder:repo_alpha:20260815T09Z"),
            title = "Bitbucket Helper reminder",
            body = "Alpha: 2 items still need attention",
            openUrl = URI("https://bitbucket.org/acme/alpha"),
            sound = NotificationSound.DEFAULT,
        )
        val draft = NewNotificationIntent(request, createdAt)
        val lease = NotificationLease(
            owner = "worker-1",
            acquiredAt = createdAt,
            expiresAt = createdAt.plusSeconds(120),
        )
        val stored = StoredNotificationIntent(
            id = NotificationIntentId("ni_reminder-alpha-09"),
            request = draft.request,
            createdAt = draft.createdAt,
            state = NotificationIntentState.PENDING,
            attemptCount = 0,
            nextAttemptAt = createdAt,
            lease = lease,
        )
        val attempt = StoredNotificationAttempt(
            id = NotificationAttemptId("na_reminder-alpha-09-1"),
            intentId = stored.id,
            attemptNumber = 1,
            completedAt = createdAt.plusSeconds(1),
            result = NotificationDeliveryResult.Failed(
                NotificationDeliveryFailureCategory.DELIVERY_TIMEOUT,
                ambiguous = true,
            ),
        )

        assertEquals("reminder:repo_alpha:20260815T09Z", stored.request.deliveryKey.value)
        assertEquals(Duration.ofMinutes(2), Duration.between(lease.acquiredAt, lease.expiresAt))
        assertEquals(request, stored.request)
        assertEquals(stored.id, attempt.intentId)
    }

    @Test
    fun `each builds-green edge has a stable distinct transition identity`() {
        val createdAt = Instant.parse("2026-08-15T09:00:00Z")
        val first = NotificationTransitionFact.BuildsBecameGreen(
            repositoryId = RepositoryId("repo_alpha"),
            repositoryDisplayName = "Alpha",
            repositoryWebUrl = URI("https://bitbucket.org/acme/alpha"),
            pullRequestId = PullRequestId("pr_alpha-42"),
            pullRequestNumber = 42,
            pullRequestTitle = "Keep contracts boring",
            pullRequestWebUrl = URI("https://bitbucket.org/acme/alpha/pull-requests/42"),
            headCommit = "abc123",
            transitionId = BuildGreenTransitionId("bgt_alpha-42-edge-1"),
            createdAt = createdAt,
        )
        val replay = first.copy()
        val second = first.copy(transitionId = BuildGreenTransitionId("bgt_alpha-42-edge-2"))

        assertEquals(first.transitionId, replay.transitionId)
        assertNotEquals(first.transitionId, second.transitionId)
    }

    @Test
    fun `notification transition facts preserve Core observation or commit time`() {
        val createdAt = Instant.parse("2026-08-15T09:00:00Z")
        val repositoryId = RepositoryId("repo_alpha")
        val repositoryWebUrl = URI("https://bitbucket.org/acme/alpha")
        val pullRequestId = PullRequestId("pr_alpha-42")
        val pullRequestWebUrl = URI("https://bitbucket.org/acme/alpha/pull-requests/42")
        val facts: List<NotificationTransitionFact> = listOf(
            NotificationTransitionFact.InitialRepositoryDigest(
                repositoryId = repositoryId,
                repositoryDisplayName = "Alpha",
                repositoryWebUrl = repositoryWebUrl,
                actionableItemCount = 2,
                createdAt = createdAt,
            ),
            NotificationTransitionFact.ActionableActivity(
                repositoryId = repositoryId,
                repositoryDisplayName = "Alpha",
                repositoryWebUrl = repositoryWebUrl,
                pullRequestId = pullRequestId,
                pullRequestNumber = 42,
                pullRequestTitle = "Keep contracts boring",
                pullRequestWebUrl = pullRequestWebUrl,
                actionItemId = ActionItemId("ai_alpha-42-comment-1"),
                activityVersion = ActivityVersion("av_alpha-42-comment-1"),
                createdAt = createdAt,
            ),
            NotificationTransitionFact.BuildsBecameGreen(
                repositoryId = repositoryId,
                repositoryDisplayName = "Alpha",
                repositoryWebUrl = repositoryWebUrl,
                pullRequestId = pullRequestId,
                pullRequestNumber = 42,
                pullRequestTitle = "Keep contracts boring",
                pullRequestWebUrl = pullRequestWebUrl,
                headCommit = "abc123",
                transitionId = BuildGreenTransitionId("bgt_alpha-42-edge-1"),
                createdAt = createdAt,
            ),
        )

        assertEquals(listOf(createdAt, createdAt, createdAt), facts.map { it.createdAt })
    }

    @Test
    fun `gateway failures expose typed safe retry metadata`() {
        val retryAt = Instant.parse("2026-08-15T09:05:00Z")
        val result: GatewayResult<String> = GatewayResult.Failure(
            GatewayFailure(
                category = GatewayFailureCategory.RATE_LIMITED,
                retryable = true,
                retryAt = retryAt,
            ),
        )

        assertEquals("failure", gatewayKind(result))
        val typedFailure = (result as GatewayResult.Failure).failure
        assertEquals(GatewayFailureCategory.RATE_LIMITED, typedFailure.category)
        assertTrue(typedFailure.retryable)
        assertEquals(retryAt, typedFailure.retryAt)
    }

    @Test
    fun `gateway live content keeps markdown out of diagnostic rendering`() {
        val markdown = "private review body: do not expose"
        val content = GatewayLiveActivityContent(
            activityVersion = ActivityVersion("av_comment-7-v2"),
            markdown = markdown,
            fetchedAt = Instant.parse("2026-08-15T09:00:00Z"),
        )
        val result: GatewayResult<GatewayLiveActivityContent> = GatewayResult.Success(content)

        assertEquals(markdown, content.markdown)
        assertFalse(content.toString().contains(markdown))
        assertFalse(result.toString().contains(markdown))
    }

    @Test
    fun `application live content keeps markdown out of diagnostic rendering`() {
        val markdown = "private application body: do not expose"
        val content = LiveActivityContentResult.ContentAvailable(
            actionItemId = ActionItemId("ai_alpha-42-comment-7"),
            requestedVersion = ActivityVersion("av_comment-7-v2"),
            markdown = markdown,
            fetchedAt = Instant.parse("2026-08-15T09:00:00Z"),
        )
        val result: LiveActivityContentResult = content
        val wrapped: List<LiveActivityContentResult> = listOf(result)
        val changedCopy = content.copy(markdown = "replacement body")

        assertEquals(markdown, content.markdown)
        assertEquals("replacement body", changedCopy.markdown)
        assertNotEquals(content, changedCopy)
        assertFalse(content.toString().contains(markdown))
        assertFalse(result.toString().contains(markdown))
        assertFalse(wrapped.toString().contains(markdown))
    }

    @Test
    fun `stored configuration pull request and action item snapshots round trip without bodies`() {
        val observedAt = Instant.parse("2026-08-15T09:00:00Z")
        val repository = StoredConfiguredRepository(
            id = RepositoryId("repo_alpha"),
            workspaceId = WorkspaceId("ws_acme"),
            slug = "alpha",
            displayName = "Alpha",
            webUrl = URI("https://bitbucket.org/acme/alpha"),
            removedAt = null,
        )
        val configuration = StoredInstallationConfiguration(
            workspaceId = WorkspaceId("ws_acme"),
            bitbucketApiBaseUrl = URI("https://api.bitbucket.org/2.0"),
            workspaceSlug = "acme",
            workspaceDisplayName = "Acme",
            workspaceWebUrl = URI("https://bitbucket.org/acme"),
            currentUserStableId = "{user-uuid}",
            currentUserDisplayName = "Ada",
            configuredAt = observedAt,
            retentionDays = 30,
            repositories = listOf(repository),
        )
        val pullRequest = StoredPullRequestSnapshot(
            id = PullRequestId("pr_alpha-42"),
            repositoryId = repository.id,
            upstreamNumber = 42,
            title = "Keep contracts boring",
            authorStableId = "{user-uuid}",
            authorDisplayName = "Ada",
            draft = false,
            headCommit = "abc123",
            webUrl = URI("https://bitbucket.org/acme/alpha/pull-requests/42"),
            createdAt = observedAt.minusSeconds(60),
            updatedAt = observedAt,
            observedAt = observedAt,
            active = true,
            inactiveAt = null,
            readiness = StoredReadiness.Available(passed = 6, total = 7, checks = emptyList()),
            builds = listOf(
                StoredBuildObservation("build-1", BuildState.SUCCESSFUL, observedAt),
            ),
            buildsWereGreen = false,
        )
        val actionItem = StoredActionItemSnapshot(
            id = ActionItemId("ai_alpha-42-comment-7"),
            pullRequestId = pullRequest.id,
            repositoryId = repository.id,
            sourceKind = "comment",
            upstreamSourceId = "comment-7",
            actorStableId = "{reviewer-uuid}",
            actorDisplayName = "Grace",
            activityAt = observedAt,
            observedAt = observedAt,
            activityVersion = ActivityVersion("av_comment-7-v2"),
            state = ActionItemState.OPEN,
            acknowledgedVersion = null,
            acknowledgedAt = null,
            webUrl = URI("https://bitbucket.org/acme/alpha/pull-requests/42#comment-7"),
        )

        assertEquals(repository, configuration.repositories.single())
        assertEquals(pullRequest.id, actionItem.pullRequestId)
        assertEquals("av_comment-7-v2", actionItem.activityVersion.value)
        assertEquals(BuildState.SUCCESSFUL, pullRequest.builds.single().state)
    }

    @Test
    fun `gateway pull request facts allow reviewer approval joins`() {
        val detail = GatewayPullRequestDetail(
            repositoryId = RepositoryId("repo_alpha"),
            upstreamNumber = 42,
            title = "Keep contracts boring",
            authorStableId = "{author}",
            authorDisplayName = "Ada",
            draft = false,
            headCommit = "abc123",
            webUrl = URI("https://bitbucket.org/acme/alpha/pull-requests/42"),
            createdAt = Instant.EPOCH,
            updatedAt = Instant.EPOCH,
            approvalCount = 2,
            approvedByStableIds = setOf("{reviewer-1}", "{reviewer-2}"),
            hasChangesRequested = false,
            unresolvedCommentCount = 0,
            destinationBranchIsCurrent = true,
            hasMergeConflicts = false,
        )

        assertEquals(setOf("{reviewer-1}", "{reviewer-2}"), detail.approvedByStableIds)
    }

    @Test
    fun `reminder projections contain safe identities and counts without aggregate types`() {
        val repository = ReminderRepositoryProjection(
            repositoryId = RepositoryId("repo_alpha"),
            displayName = "Alpha",
            webUrl = URI("https://bitbucket.org/acme/alpha"),
        )
        val action = ReminderActionItemProjection(
            actionItemId = ActionItemId("ai_alpha-42-comment-7"),
            repositoryId = repository.repositoryId,
            activityVersion = ActivityVersion("av_comment-7-v2"),
        )

        assertEquals(repository.repositoryId, action.repositoryId)
    }

    @Test
    fun `exact version acknowledgment results always identify the targeted version`() {
        val item = ActionItemProjection(
            id = ActionItemId("ai_alpha-42-comment-7"),
            pullRequestId = PullRequestId("pr_alpha-42"),
            repositoryId = RepositoryId("repo_alpha"),
            repositoryDisplayName = "Alpha",
            pullRequestNumber = 42,
            pullRequestTitle = "Keep contracts boring",
            activityVersion = ActivityVersion("av_current"),
            kind = "comment",
            actor = ActorProjection("{reviewer-uuid}", "Grace"),
            activityAt = Instant.parse("2026-08-15T09:00:00Z"),
            state = ActionItemState.OPEN,
            acknowledgedAt = null,
            webUrl = URI("https://bitbucket.org/acme/alpha/pull-requests/42#comment-7"),
        )
        val requested = ActivityVersion("av_requested")
        val results = listOf(
            AcknowledgeActionItemResult.Acknowledged(item.id, requested, Instant.parse("2026-08-15T09:01:00Z")),
            AcknowledgeActionItemResult.AlreadyAcknowledged(item.id, requested),
            AcknowledgeActionItemResult.StaleActivityVersion(item.id, requested, item),
            AcknowledgeActionItemResult.AcknowledgmentRejected(item.id, requested),
            AcknowledgeActionItemResult.ActionItemNotFound(item.id, requested),
        )

        assertEquals(
            listOf("acknowledged", "already", "stale", "rejected", "missing"),
            results.map(::acknowledgmentKind),
        )
        assertTrue(results.all { it.requestedVersion == requested })
        assertTrue((results[2] as AcknowledgeActionItemResult.StaleActivityVersion).hasNewerActivity)
    }

    @Test
    fun `sealed business and delivery results require exhaustive handling`() {
        val failure = SynchronizationFailure(SynchronizationFailureCategory.NETWORK, true, null)
        val deliveryResults = listOf<NotificationDeliveryResult>(
            NotificationDeliveryResult.Accepted,
            NotificationDeliveryResult.Failed(NotificationDeliveryFailureCategory.DELIVERY_TIMEOUT, ambiguous = true),
        )

        assertEquals(listOf("accepted", "failed"), deliveryResults.map(::deliveryKind))
        assertEquals(
            listOf("succeeded", "partial", "failed", "deferred", "missing"),
            listOf(
                RefreshRepositoryResult.Succeeded(RepositoryId("repo_alpha"), Instant.EPOCH, synchronizationProjection()),
                RefreshRepositoryResult.PartiallySucceeded(RepositoryId("repo_alpha"), Instant.EPOCH, PartialFailureMetadata(1, 0, listOf(failure)), synchronizationProjection()),
                RefreshRepositoryResult.Failed(RepositoryId("repo_alpha"), failure, synchronizationProjection()),
                RefreshRepositoryResult.DeferredByBackoff(RepositoryId("repo_alpha"), Instant.EPOCH, synchronizationProjection()),
                RefreshRepositoryResult.RepositoryNotConfigured(RepositoryId("repo_alpha")),
            ).map(::refreshKind),
        )
    }

    @Test
    fun `in-progress refresh runs carry positive server polling advice`() {
        val refreshRun = RefreshRunSnapshot(
            id = RefreshRunId("rr_active-run"),
            createdAt = Instant.parse("2026-08-15T09:00:00Z"),
            expiresAt = Instant.parse("2026-08-15T09:10:00Z"),
            repositories = emptyList(),
        )
        val result: GetRefreshRunResult = GetRefreshRunResult.RefreshRunInProgress(
            refreshRun = refreshRun,
            polling = ActivePollingAdvice(afterMilliseconds = 750),
        )

        assertEquals("running", getRefreshKind(result))
        assertEquals(750, (result as GetRefreshRunResult.RefreshRunInProgress).polling.afterMilliseconds)
        assertThrows(IllegalArgumentException::class.java) {
            ActivePollingAdvice(afterMilliseconds = 0)
        }
    }
}

private fun synchronizationProjection() = com.mindtable.bitbuckethelper.application.model.SynchronizationProjection(
    repositoryId = RepositoryId("repo_alpha"),
    activity = SynchronizationActivity.IDLE,
    lastAttemptAt = Instant.EPOCH,
    lastAttemptOutcome = SynchronizationAttemptOutcome.SUCCEEDED,
    lastSuccessAt = Instant.EPOCH,
    freshness = Freshness.Fresh(Instant.EPOCH, Duration.ZERO),
    problem = SynchronizationProblem.None,
)

private fun freshnessKind(value: Freshness): String = when (value) {
    Freshness.NeverSynchronized -> "never"
    is Freshness.Fresh -> "fresh"
    is Freshness.Stale -> "stale"
}

private fun gatewayKind(value: GatewayResult<*>): String = when (value) {
    is GatewayResult.Success -> "success"
    GatewayResult.NotFound -> "missing"
    is GatewayResult.Failure -> "failure"
}

private fun acknowledgmentKind(value: AcknowledgeActionItemResult): String = when (value) {
    is AcknowledgeActionItemResult.Acknowledged -> "acknowledged"
    is AcknowledgeActionItemResult.AlreadyAcknowledged -> "already"
    is AcknowledgeActionItemResult.StaleActivityVersion -> "stale"
    is AcknowledgeActionItemResult.AcknowledgmentRejected -> "rejected"
    is AcknowledgeActionItemResult.ActionItemNotFound -> "missing"
}

private fun deliveryKind(value: NotificationDeliveryResult): String = when (value) {
    NotificationDeliveryResult.Accepted -> "accepted"
    is NotificationDeliveryResult.Failed -> "failed"
}

private fun refreshKind(value: RefreshRepositoryResult): String = when (value) {
    is RefreshRepositoryResult.Succeeded -> "succeeded"
    is RefreshRepositoryResult.PartiallySucceeded -> "partial"
    is RefreshRepositoryResult.Failed -> "failed"
    is RefreshRepositoryResult.DeferredByBackoff -> "deferred"
    is RefreshRepositoryResult.RepositoryNotConfigured -> "missing"
}

@Suppress("unused")
private fun configureWorkspaceKind(value: ConfigureWorkspaceResult): String = when (value) {
    is ConfigureWorkspaceResult.WorkspaceConfigured -> "configured"
    is ConfigureWorkspaceResult.WorkspaceAlreadyConfigured -> "already"
    is ConfigureWorkspaceResult.WorkspaceIdentityMismatch -> "mismatch"
    ConfigureWorkspaceResult.WorkspaceNotFound -> "missing"
    is ConfigureWorkspaceResult.WorkspaceResolutionUnavailable -> "unavailable"
}

@Suppress("unused")
private fun getWorkspaceConfigurationKind(value: GetWorkspaceConfigurationResult): String = when (value) {
    is GetWorkspaceConfigurationResult.Configured -> "configured"
    GetWorkspaceConfigurationResult.WorkspaceNotConfigured -> "unconfigured"
}

@Suppress("unused")
private fun addRepositoryKind(value: AddRepositoryResult): String = when (value) {
    is AddRepositoryResult.RepositoryAdded -> "added"
    is AddRepositoryResult.RepositoryAlreadyConfigured -> "already"
    AddRepositoryResult.RepositoryNotFound -> "missing"
    is AddRepositoryResult.RepositoryResolutionUnavailable -> "unavailable"
    AddRepositoryResult.WorkspaceNotConfigured -> "unconfigured"
}

@Suppress("unused")
private fun removeRepositoryKind(value: RemoveRepositoryResult): String = when (value) {
    is RemoveRepositoryResult.RepositoryRemoved -> "removed"
    is RemoveRepositoryResult.RepositoryNotConfigured -> "missing"
}

@Suppress("unused")
private fun dashboardKind(value: DashboardResult): String = when (value) {
    is DashboardResult.SnapshotChanged -> "changed"
    is DashboardResult.SnapshotUnchanged -> "unchanged"
    DashboardResult.WorkspaceNotConfigured -> "unconfigured"
}

@Suppress("unused")
private fun listPullRequestsKind(value: ListPullRequestsResult): String = when (value) {
    is ListPullRequestsResult.Available -> "available"
    ListPullRequestsResult.WorkspaceNotConfigured -> "unconfigured"
}

@Suppress("unused")
private fun getPullRequestKind(value: GetPullRequestResult): String = when (value) {
    is GetPullRequestResult.Found -> "found"
    is GetPullRequestResult.PullRequestNotFound -> "missing"
    GetPullRequestResult.WorkspaceNotConfigured -> "unconfigured"
}

@Suppress("unused")
private fun getInboxKind(value: GetInboxResult): String = when (value) {
    is GetInboxResult.Available -> "available"
    GetInboxResult.WorkspaceNotConfigured -> "unconfigured"
}

@Suppress("unused")
private fun getSynchronizationKind(value: GetSynchronizationStatusResult): String = when (value) {
    is GetSynchronizationStatusResult.Available -> "available"
    GetSynchronizationStatusResult.WorkspaceNotConfigured -> "unconfigured"
}

@Suppress("unused")
private fun liveContentKind(value: LiveActivityContentResult): String = when (value) {
    is LiveActivityContentResult.ContentAvailable -> "available"
    is LiveActivityContentResult.StaleActivityVersion -> "stale"
    is LiveActivityContentResult.NewerActivityObserved -> "newer"
    is LiveActivityContentResult.ContentUnavailable -> "unavailable"
    is LiveActivityContentResult.ActionItemNotFound -> "missing"
}

@Suppress("unused")
private fun startRefreshKind(value: StartRefreshRunResult): String = when (value) {
    StartRefreshRunResult.WorkspaceNotConfigured -> "unconfigured"
    StartRefreshRunResult.NoRepositoriesConfigured -> "empty"
    is StartRefreshRunResult.RefreshRunRegistered -> "registered"
}

@Suppress("unused")
private fun getRefreshKind(value: GetRefreshRunResult): String = when (value) {
    is GetRefreshRunResult.RefreshRunInProgress -> "running"
    is GetRefreshRunResult.RefreshRunCompleted -> "completed"
    is GetRefreshRunResult.RefreshRunUnavailable -> "unavailable"
}

@Suppress("unused")
private fun refreshRunRepositoryKind(value: RefreshRunRepositoryEntry): String = when (value) {
    is RefreshRunRepositoryEntry.Queued -> "queued"
    is RefreshRunRepositoryEntry.Running -> "running"
    is RefreshRunRepositoryEntry.Succeeded -> "succeeded"
    is RefreshRunRepositoryEntry.PartiallySucceeded -> "partial"
    is RefreshRunRepositoryEntry.Failed -> "failed"
    is RefreshRunRepositoryEntry.DeferredByBackoff -> "deferred"
}

@Suppress("unused")
private fun storedAcknowledgmentKind(value: StoredAcknowledgmentResult): String = when (value) {
    is StoredAcknowledgmentResult.Updated -> "updated"
    is StoredAcknowledgmentResult.AlreadyApplied -> "already"
    is StoredAcknowledgmentResult.VersionMismatch -> "mismatch"
    is StoredAcknowledgmentResult.NotActionable -> "rejected"
    StoredAcknowledgmentResult.Missing -> "missing"
}

@Suppress("unused")
private fun notificationInsertKind(value: NotificationIntentInsertResult): String = when (value) {
    is NotificationIntentInsertResult.Inserted -> "inserted"
    is NotificationIntentInsertResult.Existing -> "existing"
}

@Suppress("unused")
private suspend fun consumeNotificationLeaseContract(
    store: NotificationIntentStore,
    intentId: NotificationIntentId,
    owner: String,
    now: Instant,
    completion: NotificationAttemptCompletion,
) {
    store.findDue(now, limit = 10)
    store.tryClaim(intentId, owner, now, now.plusSeconds(120))
    store.releaseClaim(intentId, owner)
    store.completeAttempt(intentId, owner, completion)
    store.listAttempts(intentId)
}

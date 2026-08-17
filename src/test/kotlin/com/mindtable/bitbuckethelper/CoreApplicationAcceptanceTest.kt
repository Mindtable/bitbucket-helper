package com.mindtable.bitbuckethelper

import com.mindtable.bitbuckethelper.adapter.outbound.persistence.JooqApplicationPersistence
import com.mindtable.bitbuckethelper.application.model.*
import com.mindtable.bitbuckethelper.application.port.inbound.RefreshRepository
import com.mindtable.bitbuckethelper.application.port.outbound.BitbucketGateway
import com.mindtable.bitbuckethelper.application.port.outbound.NotificationIntentPolicy
import com.mindtable.bitbuckethelper.application.port.outbound.PostCommitNotificationDispatcher
import com.mindtable.bitbuckethelper.application.service.ActionItemServices
import com.mindtable.bitbuckethelper.application.service.PruneInactivePullRequestsService
import com.mindtable.bitbuckethelper.application.service.ReadQueryServices
import com.mindtable.bitbuckethelper.application.service.RefreshRepositoryService
import com.mindtable.bitbuckethelper.application.service.RepositoryRefreshCoordinator
import com.mindtable.bitbuckethelper.application.service.WorkspaceConfigurationServices
import com.mindtable.bitbuckethelper.domain.shared.ActivityVersion
import com.mindtable.bitbuckethelper.domain.shared.NotificationIntentId
import com.mindtable.bitbuckethelper.domain.shared.RepositoryId
import com.mindtable.bitbuckethelper.domain.shared.WorkspaceId
import java.net.URI
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

@OptIn(ExperimentalCoroutinesApi::class)
class CoreApplicationAcceptanceTest {
    @TempDir
    lateinit var temporaryDirectory: Path

    @Test
    fun `core journey is durable authoritative private and prunable`() = runTest {
        val databasePath = temporaryDirectory.resolve("core-acceptance.sqlite")
        val gateway = AcceptanceBitbucketGateway()
        val policy = AcceptanceNotificationPolicy()
        val dispatcher = RecordingDispatcher()
        val clock = MutableClock(initialTime)
        var persistence = JooqApplicationPersistence.open(databasePath)

        try {
            val configuration = WorkspaceConfigurationServices(persistence, gateway, clock)
            val configured = assertInstanceOf(
                ConfigureWorkspaceResult.WorkspaceConfigured::class.java,
                configuration.configure(ConfigureWorkspaceCommand(apiBaseUrl, "platform")),
            )
            assertEquals(workspaceId, configured.configuration.workspaceId)
            val added = assertInstanceOf(
                AddRepositoryResult.RepositoryAdded::class.java,
                configuration.add(AddRepositoryCommand("payments")),
            )
            assertEquals(repositoryId, added.repository.repositoryId)

            val firstRefresh = refresh(persistence, gateway, policy, dispatcher, clock, backgroundScope)
            assertInstanceOf(
                RefreshRepositoryResult.Succeeded::class.java,
                firstRefresh(RefreshRepositoryCommand(repositoryId)),
            )
            assertTrue(dispatcher.intentIds.isNotEmpty())

            val dashboard = assertInstanceOf(
                DashboardResult.SnapshotChanged::class.java,
                ReadQueryServices(persistence, clock).dashboard(GetDashboardSnapshotQuery(null)),
            ).snapshot
            val pullRequest = dashboard.repositoryGroups.single().pullRequests.single()
            val actionItem = pullRequest.actionItems.single()
            assertEquals("Payments API", pullRequest.title)
            assertEquals(ActionItemState.OPEN, actionItem.state)

            val liveContent = assertInstanceOf(
                LiveActivityContentResult.ContentAvailable::class.java,
                ActionItemServices(persistence, gateway, clock).getLiveContent(
                    GetLiveActivityContentCommand(actionItem.id, actionItem.activityVersion),
                ),
            )
            assertEquals(liveContentMarker, liveContent.markdown)
            assertEquals(listOf("17"), gateway.liveContentSourceIds)

            val actionServices = ActionItemServices(persistence, gateway, clock)
            val acknowledged = assertInstanceOf(
                AcknowledgeActionItemResult.Acknowledged::class.java,
                actionServices.acknowledge(AcknowledgeActionItemCommand(actionItem.id, actionItem.activityVersion)),
            )
            assertEquals(initialTime, acknowledged.acknowledgedAt)
            assertInstanceOf(
                AcknowledgeActionItemResult.AlreadyAcknowledged::class.java,
                actionServices.acknowledge(AcknowledgeActionItemCommand(actionItem.id, actionItem.activityVersion)),
            )

            val leasedIntentId = dispatcher.intentIds.flatten().first()
            val leaseAcquiredAt = clock.instant()
            val leased = persistence.inTransaction {
                notificationIntentStore.tryClaim(
                    leasedIntentId,
                    leaseOwner,
                    leaseAcquiredAt,
                    leaseAcquiredAt.plusSeconds(300),
                )
            }
            assertNotNull(leased?.lease)

            persistence.close()
            val databaseBytes = String(Files.readAllBytes(databasePath), StandardCharsets.ISO_8859_1)
            assertFalse(databaseBytes.contains(liveContentMarker), "live activity content must not be persisted")

            persistence = JooqApplicationPersistence.open(databasePath)
            val durable = persistence.inTransaction {
                DurableState(
                    configuration = configurationStore.find(),
                    pullRequest = pullRequestStore.find(pullRequest.id),
                    actionItem = actionItemStore.find(actionItem.id),
                    synchronization = synchronizationCheckpointStore.find(repositoryId),
                    intent = notificationIntentStore.find(leasedIntentId),
                )
            }
            assertEquals(workspaceId, durable.configuration?.workspaceId)
            assertTrue(durable.pullRequest?.active == true)
            assertEquals(ActionItemState.ACKNOWLEDGED, durable.actionItem?.state)
            assertEquals(actionItem.activityVersion, durable.actionItem?.acknowledgedVersion)
            assertEquals(SynchronizationAttemptOutcome.SUCCEEDED, durable.synchronization?.lastAttemptOutcome)
            assertEquals(leaseOwner, durable.intent?.lease?.owner)
            assertEquals(leaseAcquiredAt.plusSeconds(300), durable.intent?.lease?.expiresAt)

            gateway.authoredOpenPullRequests = emptyList()
            clock.current = initialTime.plusSeconds(60)
            val authoritativeRefresh = refresh(persistence, gateway, policy, dispatcher, clock, backgroundScope)
            assertInstanceOf(
                RefreshRepositoryResult.Succeeded::class.java,
                authoritativeRefresh(RefreshRepositoryCommand(repositoryId)),
            )
            assertFalse(persistence.inTransaction { pullRequestStore.find(pullRequest.id) }!!.active)

            clock.current = initialTime.plusSeconds(31 * 24 * 60 * 60L)
            val pruned = PruneInactivePullRequestsService(persistence, clock)()
            assertEquals(1, pruned.prunedPullRequestCount)
            persistence.inTransaction {
                assertNull(pullRequestStore.find(pullRequest.id))
                assertNull(actionItemStore.find(actionItem.id))
                assertEquals(leaseOwner, notificationIntentStore.find(leasedIntentId)?.lease?.owner)
                assertEquals(workspaceId, configurationStore.find()?.workspaceId)
            }
        } finally {
            persistence.close()
        }
    }

    private fun refresh(
        persistence: JooqApplicationPersistence,
        gateway: BitbucketGateway,
        policy: NotificationIntentPolicy,
        dispatcher: PostCommitNotificationDispatcher,
        clock: Clock,
        serviceScope: CoroutineScope,
    ): RepositoryRefreshCoordinator {
        val delegate = RefreshRepositoryService(persistence, gateway, policy, dispatcher, clock)
        return RepositoryRefreshCoordinator(
            transactions = persistence,
            delegate = RefreshRepository(delegate::refresh),
            serviceScope = serviceScope,
            clock = clock,
        )
    }

    private data class DurableState(
        val configuration: StoredInstallationConfiguration?,
        val pullRequest: StoredPullRequestSnapshot?,
        val actionItem: StoredActionItemSnapshot?,
        val synchronization: StoredSynchronizationSnapshot?,
        val intent: StoredNotificationIntent?,
    )

    private class MutableClock(var current: Instant) : Clock() {
        override fun instant(): Instant = current
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId): Clock = Clock.fixed(current, zone)
    }

    private class RecordingDispatcher : PostCommitNotificationDispatcher {
        val intentIds = mutableListOf<List<NotificationIntentId>>()
        override suspend fun dispatchCommitted(intentIds: List<NotificationIntentId>) {
            this.intentIds += intentIds
        }
    }

    private class AcceptanceNotificationPolicy : NotificationIntentPolicy {
        override fun createIntents(facts: List<NotificationTransitionFact>): List<NewNotificationIntent> =
            facts.map { fact ->
                NewNotificationIntent(
                    request = NotificationRequest(
                        deliveryKey = NotificationDeliveryKey(
                            "acceptance-${fact.javaClass.simpleName}-${fact.repositoryId.value}",
                        ),
                        title = "Bitbucket update",
                        body = "A safe repository update is available.",
                        openUrl = fact.repositoryWebUrl,
                        sound = NotificationSound.DEFAULT,
                    ),
                    createdAt = fact.createdAt,
                )
            }

        override fun createReminder(fact: ReminderNotificationFact): NewNotificationIntent =
            error("reminders are outside this acceptance journey")
    }

    private class AcceptanceBitbucketGateway : BitbucketGateway {
        var authoredOpenPullRequests: List<GatewayPullRequestSummary> = listOf(
            GatewayPullRequestSummary(
                repositoryId = repositoryId,
                upstreamNumber = 17,
                title = "Payments API",
                authorStableId = "user-current",
                authorDisplayName = "Current User",
                draft = false,
                headCommit = "abcdef123456",
                webUrl = pullRequestUrl,
                createdAt = initialTime.minusSeconds(3_600),
                updatedAt = initialTime.minusSeconds(60),
            ),
        )
        val liveContentSourceIds = mutableListOf<String>()

        override suspend fun currentUser(apiBaseUrl: URI): GatewayResult<GatewayUserObservation> {
            check(apiBaseUrl == CoreApplicationAcceptanceTest.apiBaseUrl)
            return GatewayResult.Success(GatewayUserObservation("user-current", "Current User", "current"))
        }

        override suspend fun resolveWorkspace(
            apiBaseUrl: URI,
            workspaceSlug: String,
        ): GatewayResult<GatewayWorkspaceObservation> {
            check(apiBaseUrl == CoreApplicationAcceptanceTest.apiBaseUrl && workspaceSlug == "platform")
            return GatewayResult.Success(GatewayWorkspaceObservation(
                workspaceId,
                "platform",
                "Platform",
                URI("https://bitbucket.test/platform"),
            ))
        }

        override suspend fun resolveRepository(
            apiBaseUrl: URI,
            workspaceSlug: String,
            repositorySlug: String,
        ): GatewayResult<GatewayRepositoryObservation> {
            check(
                apiBaseUrl == CoreApplicationAcceptanceTest.apiBaseUrl &&
                    workspaceSlug == "platform" && repositorySlug == "payments",
            )
            return GatewayResult.Success(GatewayRepositoryObservation(
                repositoryId,
                workspaceId,
                "payments",
                "Payments",
                URI("https://bitbucket.test/platform/payments"),
            ))
        }

        override suspend fun listAuthoredOpenPullRequests(
            repository: GatewayRepositoryAddress,
            currentUserStableId: String,
        ): GatewayResult<List<GatewayPullRequestSummary>> {
            requireAcceptanceAddress(repository)
            check(currentUserStableId == "user-current")
            return GatewayResult.Success(authoredOpenPullRequests)
        }

        override suspend fun getPullRequest(
            repository: GatewayRepositoryAddress,
            upstreamNumber: Long,
        ): GatewayResult<GatewayPullRequestDetail> {
            requireAcceptancePullRequest(repository, upstreamNumber)
            return GatewayResult.Success(GatewayPullRequestDetail(
                repositoryId = repositoryId,
                upstreamNumber = 17,
                title = "Payments API",
                authorStableId = "user-current",
                authorDisplayName = "Current User",
                draft = false,
                headCommit = "abcdef123456",
                webUrl = pullRequestUrl,
                createdAt = initialTime.minusSeconds(3_600),
                updatedAt = initialTime.minusSeconds(60),
                approvalCount = 1,
                approvedByStableIds = setOf("reviewer-1"),
                hasChangesRequested = false,
                unresolvedCommentCount = 0,
                destinationBranchIsCurrent = true,
                hasMergeConflicts = false,
            ))
        }

        override suspend fun getEffectiveDefaultReviewers(
            repository: GatewayRepositoryAddress,
            upstreamNumber: Long,
        ): GatewayResult<List<GatewayUserObservation>> {
            requireAcceptancePullRequest(repository, upstreamNumber)
            return GatewayResult.Success(listOf(GatewayUserObservation("reviewer-1", "Reviewer", "reviewer")))
        }

        override suspend fun listBuilds(
            repository: GatewayRepositoryAddress,
            upstreamNumber: Long,
        ): GatewayResult<List<GatewayBuildObservation>> {
            requireAcceptancePullRequest(repository, upstreamNumber)
            return GatewayResult.Success(
                listOf(GatewayBuildObservation("build-main", GatewayBuildStatus.SUCCESSFUL, initialTime)),
            )
        }

        override suspend fun listTasks(
            repository: GatewayRepositoryAddress,
            upstreamNumber: Long,
        ): GatewayResult<List<GatewayTaskObservation>> {
            requireAcceptancePullRequest(repository, upstreamNumber)
            return GatewayResult.Success(emptyList())
        }

        override suspend fun listActivity(
            repository: GatewayRepositoryAddress,
            upstreamNumber: Long,
        ): GatewayResult<List<GatewayActivityObservation>> {
            requireAcceptancePullRequest(repository, upstreamNumber)
            return GatewayResult.Success(listOf(
                GatewayActivityObservation(
                    sourceKind = GatewayActivityKind.COMMENT,
                    sourceId = "comment-17",
                    actorStableId = "reviewer-1",
                    actorDisplayName = "Reviewer",
                    activityAt = initialTime.minusSeconds(30),
                    activityVersion = activityVersion,
                    resolved = false,
                    deleted = false,
                    webUrl = URI("$pullRequestUrl#comment-17"),
                ),
            ))
        }

        override suspend fun getLiveActivityContent(
            repository: GatewayRepositoryAddress,
            upstreamNumber: Long,
            sourceId: String,
        ): GatewayResult<GatewayLiveActivityContent> {
            requireAcceptancePullRequest(repository, upstreamNumber)
            check(sourceId == "17")
            liveContentSourceIds += sourceId
            return GatewayResult.Success(
                GatewayLiveActivityContent(activityVersion, liveContentMarker, initialTime.plusSeconds(1)),
            )
        }

        private fun requireAcceptancePullRequest(
            repository: GatewayRepositoryAddress,
            upstreamNumber: Long,
        ) {
            requireAcceptanceAddress(repository)
            check(upstreamNumber == 17L)
        }

        private fun requireAcceptanceAddress(repository: GatewayRepositoryAddress) {
            check(
                repository == GatewayRepositoryAddress(
                    repositoryId,
                    apiBaseUrl,
                    "platform",
                    "payments",
                ),
            )
        }
    }

    private companion object {
        val initialTime: Instant = Instant.parse("2026-08-15T10:00:00Z")
        val apiBaseUrl: URI = URI("https://api.bitbucket.test/2.0")
        val workspaceId = WorkspaceId("ws_platform")
        val repositoryId = RepositoryId("repo_payments")
        val activityVersion = ActivityVersion("av_comment_17_v1")
        val pullRequestUrl: URI = URI("https://bitbucket.test/platform/payments/pull-requests/17")
        const val liveContentMarker = "LIVE_ONLY_MARKER_do_not_persist_785c"
        const val leaseOwner = "acceptance-worker"
    }
}

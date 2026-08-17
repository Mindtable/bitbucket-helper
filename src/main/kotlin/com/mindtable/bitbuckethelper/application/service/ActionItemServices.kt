package com.mindtable.bitbuckethelper.application.service

import com.mindtable.bitbuckethelper.application.model.*
import com.mindtable.bitbuckethelper.application.port.inbound.AcknowledgeActionItem
import com.mindtable.bitbuckethelper.application.port.inbound.GetLiveActivityContent
import com.mindtable.bitbuckethelper.application.port.outbound.ApplicationTransaction
import com.mindtable.bitbuckethelper.application.port.outbound.ApplicationTransactionRunner
import com.mindtable.bitbuckethelper.application.port.outbound.BitbucketGateway
import java.time.Clock

class ActionItemServices(
    private val transactions: ApplicationTransactionRunner,
    private val gateway: BitbucketGateway,
    private val clock: Clock,
) {
    val getLiveActivityContent: GetLiveActivityContent = GetLiveActivityContent(::getLiveContent)
    val acknowledgeActionItem: AcknowledgeActionItem = AcknowledgeActionItem(::acknowledge)

    suspend fun getLiveContent(command: GetLiveActivityContentCommand): LiveActivityContentResult {
        val initial = transactions.inTransaction { resolveContext(command.actionItemId) }
            ?: return LiveActivityContentResult.ActionItemNotFound(command.actionItemId, command.activityVersion)
        if (initial.action.activityVersion != command.activityVersion) {
            return LiveActivityContentResult.StaleActivityVersion(
                command.actionItemId,
                command.activityVersion,
                initial.projection(),
            )
        }

        val gatewayResult = gateway.getLiveActivityContent(
            initial.gatewayAddress,
            initial.pullRequest.upstreamNumber,
            initial.action.upstreamSourceId,
        )
        return when (gatewayResult) {
            GatewayResult.NotFound -> LiveActivityContentResult.ContentUnavailable(
                command.actionItemId,
                command.activityVersion,
                LiveContentUnavailableReason.DELETED,
                retryable = false,
                retryAt = null,
            )
            is GatewayResult.Failure -> gatewayResult.failure.unavailable(command)
            is GatewayResult.Success -> {
                val live = gatewayResult.value
                if (live.activityVersion != command.activityVersion) {
                    LiveActivityContentResult.NewerActivityObserved(
                        command.actionItemId,
                        command.activityVersion,
                        live.activityVersion,
                        initial.action.repositoryId,
                    )
                } else {
                    val current = transactions.inTransaction { resolveContext(command.actionItemId) }
                        ?: return LiveActivityContentResult.ActionItemNotFound(
                            command.actionItemId,
                            command.activityVersion,
                        )
                    if (current.action.activityVersion != command.activityVersion) {
                        LiveActivityContentResult.StaleActivityVersion(
                            command.actionItemId,
                            command.activityVersion,
                            current.projection(),
                        )
                    } else {
                        LiveActivityContentResult.ContentAvailable(
                            command.actionItemId,
                            command.activityVersion,
                            live.markdown,
                            live.fetchedAt,
                        )
                    }
                }
            }
        }
    }

    suspend fun acknowledge(command: AcknowledgeActionItemCommand): AcknowledgeActionItemResult {
        return transactions.inTransaction {
            val current = actionItemStore.find(command.actionItemId)
            val clockInstant = clock.instant()
            val acknowledgedAt = if (current != null && clockInstant < current.activityAt) {
                current.activityAt
            } else {
                clockInstant
            }
            if (
                current?.activityVersion == command.activityVersion &&
                current.state == ActionItemState.CLOSED
            ) {
                return@inTransaction AcknowledgeActionItemResult.AcknowledgmentRejected(
                    command.actionItemId,
                    command.activityVersion,
                )
            }
            when (
                val result = actionItemStore.acknowledge(
                    command.actionItemId,
                    command.activityVersion,
                    acknowledgedAt,
                )
            ) {
                is StoredAcknowledgmentResult.Updated -> AcknowledgeActionItemResult.Acknowledged(
                    command.actionItemId,
                    command.activityVersion,
                    result.snapshot.acknowledgedAt ?: acknowledgedAt,
                )
                is StoredAcknowledgmentResult.AlreadyApplied -> AcknowledgeActionItemResult.AlreadyAcknowledged(
                    command.actionItemId,
                    command.activityVersion,
                )
                is StoredAcknowledgmentResult.VersionMismatch -> {
                    val context = resolveContext(result.snapshot)
                    if (context == null) {
                        AcknowledgeActionItemResult.ActionItemNotFound(
                            command.actionItemId,
                            command.activityVersion,
                        )
                    } else {
                        AcknowledgeActionItemResult.StaleActivityVersion(
                            command.actionItemId,
                            command.activityVersion,
                            context.projection(),
                        )
                    }
                }
                is StoredAcknowledgmentResult.NotActionable -> AcknowledgeActionItemResult.AcknowledgmentRejected(
                    command.actionItemId,
                    command.activityVersion,
                )
                StoredAcknowledgmentResult.Missing -> AcknowledgeActionItemResult.ActionItemNotFound(
                    command.actionItemId,
                    command.activityVersion,
                )
            }
        }
    }
}

private suspend fun ApplicationTransaction.resolveContext(
    actionItemId: com.mindtable.bitbuckethelper.domain.shared.ActionItemId,
): DurableActionContext? = actionItemStore.find(actionItemId)?.let { resolveContext(it) }

private suspend fun ApplicationTransaction.resolveContext(
    action: StoredActionItemSnapshot,
): DurableActionContext? {
    val configuration = configurationStore.find() ?: return null
    val repository = configuration.repositories.singleOrNull {
        it.id == action.repositoryId && it.removedAt == null
    } ?: return null
    val pullRequest = pullRequestStore.find(action.pullRequestId)
        ?.takeIf { it.active && it.repositoryId == repository.id }
        ?: return null
    if (action.repositoryId != pullRequest.repositoryId) return null
    return DurableActionContext(configuration, repository, pullRequest, action)
}

private data class DurableActionContext(
    val configuration: StoredInstallationConfiguration,
    val repository: StoredConfiguredRepository,
    val pullRequest: StoredPullRequestSnapshot,
    val action: StoredActionItemSnapshot,
) {
    val gatewayAddress = GatewayRepositoryAddress(
        repository.id,
        configuration.bitbucketApiBaseUrl,
        configuration.workspaceSlug,
        repository.slug,
    )

    fun projection(): ActionItemProjection = projectAction(action, repository, pullRequest)
}

private fun GatewayFailure.unavailable(
    command: GetLiveActivityContentCommand,
) = LiveActivityContentResult.ContentUnavailable(
    actionItemId = command.actionItemId,
    requestedVersion = command.activityVersion,
    reason = when (category) {
        GatewayFailureCategory.AUTHENTICATION -> LiveContentUnavailableReason.AUTHENTICATION
        GatewayFailureCategory.AUTHORIZATION -> LiveContentUnavailableReason.AUTHORIZATION
        GatewayFailureCategory.RATE_LIMITED -> LiveContentUnavailableReason.RATE_LIMITED
        GatewayFailureCategory.TIMEOUT -> LiveContentUnavailableReason.TIMEOUT
        GatewayFailureCategory.NETWORK -> LiveContentUnavailableReason.NETWORK
        GatewayFailureCategory.UPSTREAM -> LiveContentUnavailableReason.UPSTREAM
        GatewayFailureCategory.MALFORMED_RESPONSE,
        GatewayFailureCategory.UNSAFE_PAGINATION,
        -> LiveContentUnavailableReason.MALFORMED_UPSTREAM
    },
    retryable = retryable,
    retryAt = retryAt,
)

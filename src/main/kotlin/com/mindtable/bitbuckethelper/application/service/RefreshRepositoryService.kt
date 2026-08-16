package com.mindtable.bitbuckethelper.application.service

import com.mindtable.bitbuckethelper.application.model.*
import com.mindtable.bitbuckethelper.application.port.outbound.*
import com.mindtable.bitbuckethelper.domain.pullrequest.*
import com.mindtable.bitbuckethelper.domain.actionitem.*
import com.mindtable.bitbuckethelper.domain.shared.*
import java.time.Clock
import java.time.Duration

class RefreshRepositoryService(
    private val transactions: ApplicationTransactionRunner,
    private val gateway: BitbucketGateway,
    private val intentPolicy: NotificationIntentPolicy,
    private val dispatcher: PostCommitNotificationDispatcher,
    private val clock: Clock,
    private val assembler: ObservationAssembler = ObservationAssembler(),
    private val actionAssembler: ActionObservationAssembler = ActionObservationAssembler(),
) {
    suspend fun refresh(command: RefreshRepositoryCommand): RefreshRepositoryResult {
        val configuration = transactions.inTransaction { configurationStore.find() }
        val repository = configuration?.repositories?.singleOrNull { it.id == command.repositoryId && it.removedAt == null }
            ?: return RefreshRepositoryResult.RepositoryNotConfigured(command.repositoryId)
        val address = GatewayRepositoryAddress(repository.id, configuration.bitbucketApiBaseUrl, configuration.workspaceSlug, repository.slug)
        val completedAt = clock.instant()
        val listed = gateway.listAuthoredOpenPullRequests(address, configuration.currentUserStableId)
        if (listed !is GatewayResult.Success) {
            val failure = listed.failureOrNotFound()
            val sync = transactions.inTransaction {
                val currentRepository = configurationStore.find()?.repositories?.singleOrNull {
                    it.id == command.repositoryId && it.removedAt == null
                } ?: return@inTransaction null
                val previous = synchronizationCheckpointStore.find(repository.id)
                val saved = failureSnapshot(currentRepository.id, completedAt, failure, previous)
                synchronizationCheckpointStore.save(saved); saved
            } ?: return RefreshRepositoryResult.RepositoryNotConfigured(command.repositoryId)
            return RefreshRepositoryResult.Failed(repository.id, failure, sync.projection(completedAt))
        }

        val successes = mutableListOf<PullRequestObservation>()
        val activities = mutableMapOf<PullRequestId, List<GatewayActivityObservation>>()
        val failures = mutableListOf<SynchronizationFailure>()
        val summariesByNumber = listed.value.groupBy(GatewayPullRequestSummary::upstreamNumber).toSortedMap()
        for ((_, summaries) in summariesByNumber) {
            if (summaries.size != 1) {
                failures += malformedUpstream()
                continue
            }
            val summary = summaries.single()
            if (summary.repositoryId != repository.id) {
                failures += malformedUpstream()
                continue
            }
            val detail = gateway.getPullRequest(address, summary.upstreamNumber)
            val reviewers = gateway.getEffectiveDefaultReviewers(address, summary.upstreamNumber)
            val builds = gateway.listBuilds(address, summary.upstreamNumber)
            val tasks = gateway.listTasks(address, summary.upstreamNumber)
            val activity = gateway.listActivity(address, summary.upstreamNumber)
            val failure = listOf(detail, reviewers, builds, tasks, activity).firstNotNullOfOrNull { it.failureOrNull() }
            if (failure != null) { failures += failure; continue }
            val detailValue = (detail as GatewayResult.Success).value
            if (detailValue.repositoryId != repository.id || detailValue.upstreamNumber != summary.upstreamNumber) {
                failures += malformedUpstream()
                continue
            }
            successes += assembler.assemble(detailValue, (reviewers as GatewayResult.Success).value,
                (builds as GatewayResult.Success).value, (tasks as GatewayResult.Success).value, completedAt)
            activities[successes.last().id] = (activity as GatewayResult.Success).value
        }
        val activeIds = summariesByNumber.keys.mapTo(mutableSetOf()) { ObservationAssembler.idFor(repository.id.value, it) }
        val transactionResult = transactions.inTransaction {
            val currentRepository = configurationStore.find()?.repositories?.singleOrNull {
                it.id == command.repositoryId && it.removedAt == null
            } ?: return@inTransaction null
            val transitionFacts = mutableListOf<NotificationTransitionFact>()
            successes.forEach { observation ->
                val previous = pullRequestStore.find(observation.id)?.domain()
                val transition = previous?.observe(observation) ?: PullRequest.from(observation)
                pullRequestStore.save(transition.pullRequest.stored())
                if (transition.facts.any { it is com.mindtable.bitbuckethelper.domain.pullrequest.BuildsBecameGreen }) {
                    transitionFacts += NotificationTransitionFact.BuildsBecameGreen(currentRepository.id, currentRepository.displayName, currentRepository.webUrl,
                        observation.id, observation.upstreamNumber, observation.title, observation.webUrl, observation.headCommit,
                        BuildGreenTransitionId("bgt_" + ObservationAssembler.framedDigest(listOf(observation.id.value, observation.headCommit, completedAt.toString()))),
                        createdAt = completedAt)
                }
                actionAssembler.assemble(observation.id, activities[observation.id].orEmpty(), completedAt).forEach { actionObservation ->
                    val existing = actionItemStore.find(ActionItem.idFor(actionObservation.pullRequestId, actionObservation.sourceKind, actionObservation.upstreamSourceId))
                    val transition = existing?.domain()?.observe(actionObservation) ?: ActionItem.from(actionObservation)
                    actionItemStore.save(transition.actionItem.stored(repository.id))
                    if (transition.facts.any { it is ActionItemOpened || it is ActionItemVersionAdvanced || it is ActionItemReopened }) {
                        transitionFacts += NotificationTransitionFact.ActionableActivity(currentRepository.id, currentRepository.displayName, currentRepository.webUrl,
                            observation.id, observation.upstreamNumber, observation.title, observation.webUrl,
                            transition.actionItem.id, transition.actionItem.activityVersion, createdAt = completedAt)
                    }
                }
            }
            pullRequestStore.markMissingInactive(repository.id, activeIds, completedAt)
            val previous = synchronizationCheckpointStore.find(repository.id)
            val saved = if (failures.isEmpty()) successSnapshot(repository.id, completedAt, previous) else partialSnapshot(repository.id, completedAt, failures, successes.size, previous)
            synchronizationCheckpointStore.save(saved)
            if (previous?.snapshotAt == null) {
                transitionFacts.add(0, NotificationTransitionFact.InitialRepositoryDigest(currentRepository.id, currentRepository.displayName, currentRepository.webUrl,
                    actionItemStore.listActionable().count { it.repositoryId == repository.id }, createdAt = completedAt))
            }
            val inserted = intentPolicy.createIntents(transitionFacts).mapNotNull { intent ->
                val stored = intent.stored()
                when (notificationIntentStore.insertIfAbsent(stored)) {
                    is NotificationIntentInsertResult.Inserted -> stored.id
                    is NotificationIntentInsertResult.Existing -> null
                }
            }
            saved to inserted
        } ?: return RefreshRepositoryResult.RepositoryNotConfigured(command.repositoryId)
        val (snapshot, insertedIntentIds) = transactionResult
        if (insertedIntentIds.isNotEmpty()) dispatcher.dispatchCommitted(insertedIntentIds)
        return if (failures.isEmpty()) RefreshRepositoryResult.Succeeded(repository.id, completedAt, snapshot.projection(completedAt))
        else RefreshRepositoryResult.PartiallySucceeded(repository.id, completedAt, snapshot.problem.let { (it as SynchronizationProblem.Present).metadata }, snapshot.projection(completedAt))
    }
}

private fun malformedUpstream() = SynchronizationFailure(SynchronizationFailureCategory.MALFORMED_UPSTREAM, false, null)

private fun NewNotificationIntent.stored() = StoredNotificationIntent(
    NotificationIntentId("ni_" + ObservationAssembler.digest(request.deliveryKey.value)), request, createdAt,
    NotificationIntentState.PENDING, 0, createdAt, null,
)

private fun GatewayResult<*>.failureOrNull(): SynchronizationFailure? = when (this) {
    is GatewayResult.Success -> null
    GatewayResult.NotFound -> SynchronizationFailure(SynchronizationFailureCategory.UPSTREAM, false, null)
    is GatewayResult.Failure -> failure.sync()
}
private fun GatewayResult<*>.failureOrNotFound() = requireNotNull(failureOrNull())
private fun GatewayFailure.sync() = SynchronizationFailure(when(category) {
    GatewayFailureCategory.AUTHENTICATION -> SynchronizationFailureCategory.AUTHENTICATION
    GatewayFailureCategory.AUTHORIZATION -> SynchronizationFailureCategory.AUTHORIZATION
    GatewayFailureCategory.RATE_LIMITED -> SynchronizationFailureCategory.RATE_LIMITED
    GatewayFailureCategory.TIMEOUT -> SynchronizationFailureCategory.TIMEOUT
    GatewayFailureCategory.NETWORK -> SynchronizationFailureCategory.NETWORK
    GatewayFailureCategory.MALFORMED_RESPONSE, GatewayFailureCategory.UNSAFE_PAGINATION -> SynchronizationFailureCategory.MALFORMED_UPSTREAM
    GatewayFailureCategory.UPSTREAM -> SynchronizationFailureCategory.UPSTREAM
}, retryable, retryAt)

private fun successSnapshot(id: RepositoryId, at: java.time.Instant, previous: StoredSynchronizationSnapshot?) = StoredSynchronizationSnapshot(id, SynchronizationActivity.IDLE, at, SynchronizationAttemptOutcome.SUCCEEDED, at, at, SynchronizationProblem.None, 0, null, previous?.pullRequestCursor, previous?.activityCursor)
private fun partialSnapshot(id: RepositoryId, at: java.time.Instant, failures: List<SynchronizationFailure>, successes: Int, previous: StoredSynchronizationSnapshot?) = StoredSynchronizationSnapshot(id, SynchronizationActivity.IDLE, at, SynchronizationAttemptOutcome.PARTIAL_FAILURE, previous?.lastSuccessAt, at, SynchronizationProblem.Present(PartialFailureMetadata(failures.size + successes, successes, failures)), 0, null, previous?.pullRequestCursor, previous?.activityCursor)
private fun failureSnapshot(id: RepositoryId, at: java.time.Instant, failure: SynchronizationFailure, previous: StoredSynchronizationSnapshot?) = StoredSynchronizationSnapshot(id, SynchronizationActivity.IDLE, at, SynchronizationAttemptOutcome.FAILED, previous?.lastSuccessAt, previous?.snapshotAt, SynchronizationProblem.Present(PartialFailureMetadata(1, 0, listOf(failure))), (previous?.consecutiveFailureCount ?: 0) + 1, null, previous?.pullRequestCursor, previous?.activityCursor)
private fun StoredSynchronizationSnapshot.projection(now: java.time.Instant) = SynchronizationProjection(repositoryId, activity, lastAttemptAt, lastAttemptOutcome, lastSuccessAt,
    snapshotAt?.let { Freshness.Fresh(it, Duration.between(it, now)) } ?: Freshness.NeverSynchronized, problem)

private fun StoredPullRequestSnapshot.domain() = PullRequest(id, repositoryId, upstreamNumber, title, authorStableId, authorDisplayName, draft, headCommit, webUrl, createdAt, updatedAt, observedAt, active, inactiveAt,
    ReadinessAssessment((readiness as StoredReadiness.Available).checks.map { ReadinessCheck(ReadinessCheckName.valueOf(it.name), it.passed, it.safeReason) }),
    builds.map { BuildObservation(it.key, when(it.state) { BuildState.SUCCESSFUL -> BuildStatus.SUCCESSFUL; BuildState.FAILED -> BuildStatus.FAILED; BuildState.IN_PROGRESS -> BuildStatus.IN_PROGRESS; BuildState.UNKNOWN, BuildState.NO_BUILDS -> BuildStatus.UNKNOWN }, it.observedAt) }, buildsWereGreen)
private fun PullRequest.stored() = StoredPullRequestSnapshot(id, repositoryId, upstreamNumber, title, authorStableId, authorDisplayName, draft, headCommit, webUrl, createdAt, updatedAt, observedAt, active, inactiveAt,
    StoredReadiness.Available(readiness.passedCount, readiness.total, readiness.checks.map { StoredReadinessCheck(it.name.name, it.passed, it.safeReason) }),
    builds.map { StoredBuildObservation(it.key, when(it.status) { BuildStatus.SUCCESSFUL -> BuildState.SUCCESSFUL; BuildStatus.FAILED, BuildStatus.STOPPED -> BuildState.FAILED; BuildStatus.IN_PROGRESS -> BuildState.IN_PROGRESS; BuildStatus.UNKNOWN -> BuildState.UNKNOWN }, it.observedAt) }, buildsWereGreen)

private fun StoredActionItemSnapshot.domain() = ActionItem.restore(id, pullRequestId, ActionSourceKind.valueOf(sourceKind), upstreamSourceId, activityVersion,
    actorStableId, actorDisplayName, activityAt, observedAt, webUrl, when(state) {
        ActionItemState.OPEN, ActionItemState.ACKNOWLEDGED -> ActionObservationState.ACTIONABLE
        ActionItemState.CLOSED -> ActionObservationState.RESOLVED
    }, acknowledgedVersion, acknowledgedAt)
private fun ActionItem.stored(repositoryId: RepositoryId) = StoredActionItemSnapshot(id, pullRequestId, repositoryId, sourceKind.name, upstreamSourceId,
    authorStableId, authorDisplayName, activityAt, observedAt, activityVersion, when {
        sourceState != ActionObservationState.ACTIONABLE -> ActionItemState.CLOSED
        acknowledgedVersion == activityVersion -> ActionItemState.ACKNOWLEDGED
        else -> ActionItemState.OPEN
    }, acknowledgedVersion, acknowledgedAt, webUrl)

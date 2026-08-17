package com.mindtable.bitbuckethelper.application.service

import com.mindtable.bitbuckethelper.application.model.*
import com.mindtable.bitbuckethelper.application.port.inbound.GetDashboardSnapshot
import com.mindtable.bitbuckethelper.application.port.inbound.GetInbox
import com.mindtable.bitbuckethelper.application.port.inbound.GetPullRequest
import com.mindtable.bitbuckethelper.application.port.inbound.GetSynchronizationStatus
import com.mindtable.bitbuckethelper.application.port.inbound.ListPullRequests
import com.mindtable.bitbuckethelper.application.port.outbound.ApplicationTransaction
import com.mindtable.bitbuckethelper.application.port.outbound.ApplicationTransactionRunner
import com.mindtable.bitbuckethelper.domain.shared.ActionItemId
import com.mindtable.bitbuckethelper.domain.shared.DashboardRevision
import com.mindtable.bitbuckethelper.domain.shared.PullRequestId
import com.mindtable.bitbuckethelper.domain.shared.RepositoryId
import com.mindtable.bitbuckethelper.domain.shared.RepositoryRevision
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.Base64
import java.util.Locale

/**
 * Server-owned freshness and polling policy. A snapshot becomes stale at the
 * inclusive [staleAfter] boundary; elapsed age itself is deliberately not a
 * revision component, so the boundary causes exactly one revision transition.
 */
data class ProjectionFreshnessPolicy(
    val staleAfter: Duration = Duration.ofMinutes(15),
    val activePollingAfter: Duration = Duration.ofMillis(750),
) {
    init {
        require(!staleAfter.isNegative && !staleAfter.isZero) { "Freshness threshold must be positive" }
        require(!activePollingAfter.isNegative && !activePollingAfter.isZero) { "Polling delay must be positive" }
    }
}

class ReadQueryServices(
    private val transactions: ApplicationTransactionRunner,
    private val clock: Clock,
    private val freshnessPolicy: ProjectionFreshnessPolicy = ProjectionFreshnessPolicy(),
) {
    val getDashboardSnapshot: GetDashboardSnapshot = GetDashboardSnapshot(::dashboard)
    val listPullRequestsQuery: ListPullRequests = ListPullRequests(::listPullRequests)
    val getPullRequestQuery: GetPullRequest = GetPullRequest(::getPullRequest)
    val getInboxQuery: GetInbox = GetInbox(::getInbox)
    val getSynchronizationStatusQuery: GetSynchronizationStatus =
        GetSynchronizationStatus(::getSynchronizationStatus)

    suspend fun dashboard(query: GetDashboardSnapshotQuery): DashboardResult {
        val now = clock.instant()
        val projection = readProjection(now) ?: return DashboardResult.WorkspaceNotConfigured
        return if (query.afterRevision == projection.dashboard.revision) {
            DashboardResult.SnapshotUnchanged(projection.dashboard.revision, now, projection.dashboard.polling)
        } else {
            DashboardResult.SnapshotChanged(projection.dashboard)
        }
    }

    suspend fun listPullRequests(): ListPullRequestsResult {
        val projection = readProjection(clock.instant()) ?: return ListPullRequestsResult.WorkspaceNotConfigured
        return ListPullRequestsResult.Available(projection.dashboard.repositoryGroups)
    }

    suspend fun getPullRequest(query: GetPullRequestQuery): GetPullRequestResult {
        val projection = readProjection(clock.instant()) ?: return GetPullRequestResult.WorkspaceNotConfigured
        return projection.details[query.pullRequestId]?.let(GetPullRequestResult::Found)
            ?: GetPullRequestResult.PullRequestNotFound(query.pullRequestId)
    }

    suspend fun getInbox(): GetInboxResult {
        val projection = readProjection(clock.instant()) ?: return GetInboxResult.WorkspaceNotConfigured
        return GetInboxResult.Available(projection.dashboard.inbox)
    }

    suspend fun getSynchronizationStatus(): GetSynchronizationStatusResult {
        val projection = readProjection(clock.instant()) ?: return GetSynchronizationStatusResult.WorkspaceNotConfigured
        return GetSynchronizationStatusResult.Available(
            projection.dashboard.repositoryGroups.map(RepositoryGroupProjection::synchronization),
        )
    }

    private suspend fun readProjection(now: Instant): CompleteProjection? = transactions.inTransaction {
        val configuration = configurationStore.find() ?: return@inTransaction null
        val repositories = configuration.repositories
            .asSequence()
            .filter { it.removedAt == null }
            .sortedWith(repositoryOrder)
            .toList()
        val workspace = WorkspaceConfigurationProjection(
            workspaceId = configuration.workspaceId,
            bitbucketApiBaseUrl = configuration.bitbucketApiBaseUrl,
            workspaceSlug = configuration.workspaceSlug,
            workspaceDisplayName = configuration.workspaceDisplayName,
            workspaceWebUrl = configuration.workspaceWebUrl,
            retentionDays = configuration.retentionDays,
            repositories = repositories.map {
                ConfiguredRepositoryProjection(it.id, it.slug, it.displayName, it.webUrl)
            },
        )
        val details = linkedMapOf<PullRequestId, PullRequestDetailProjection>()
        val actionableItems = mutableListOf<ActionItemProjection>()
        val groups = repositories.map { repository ->
            projectRepository(repository, now, details, actionableItems)
        }
        val inbox = InboxProjection(
            actionableItems.sortedWith(actionOrder),
        )
        val polling = if (groups.any { it.synchronization.activity != SynchronizationActivity.IDLE }) {
            DashboardPolling.Active(freshnessPolicy.activePollingAfter.toMillis())
        } else {
            DashboardPolling.Idle
        }
        val revision = dashboardRevision(workspace, groups, inbox, polling)
        CompleteProjection(
            DashboardSnapshot(revision, now, workspace, groups, inbox, polling),
            java.util.Map.copyOf(details),
        )
    }

    private suspend fun ApplicationTransaction.projectRepository(
        repository: StoredConfiguredRepository,
        now: Instant,
        details: MutableMap<PullRequestId, PullRequestDetailProjection>,
        actionableItems: MutableList<ActionItemProjection>,
    ): RepositoryGroupProjection {
        val synchronization = synchronizationCheckpointStore.find(repository.id)
            .orEmpty(repository.id)
            .projection(now, freshnessPolicy)
        val pullRequests = pullRequestStore.listByRepository(repository.id, includeInactive = false)
            .asSequence()
            .filter { it.active && it.repositoryId == repository.id }
            .sortedWith(pullRequestOrder)
            .toList()
            .map { pullRequest ->
                val storedActions = actionItemStore.listByPullRequest(pullRequest.id)
                    .asSequence()
                    .filter {
                        it.pullRequestId == pullRequest.id &&
                            it.repositoryId == repository.id
                    }
                    .sortedWith(storedActionOrder)
                    .toList()
                val actions = storedActions.map { projectAction(it, repository, pullRequest) }
                actionableItems += storedActions.zip(actions)
                    .filter { (stored) -> stored.isCurrentlyActionable() }
                    .map { (_, projected) -> projected }
                val card = pullRequest.card(storedActions, actions)
                details[pullRequest.id] = PullRequestDetailProjection(
                    pullRequest = card,
                    headCommit = pullRequest.headCommit,
                    builds = pullRequest.builds.sortedBy(StoredBuildObservation::key).map {
                        BuildProjection(it.key, it.state)
                    },
                    freshness = synchronization.freshness,
                )
                card
            }.toList()
        val summary = ReadinessSummaryProjection(
            readyPullRequestCount = pullRequests.count {
                (it.readiness as? ReadinessProjection.Available)?.let { readiness ->
                    readiness.total > 0 && readiness.passed == readiness.total
                } == true
            },
            availablePullRequestCount = pullRequests.count { it.readiness is ReadinessProjection.Available },
            unavailablePullRequestCount = pullRequests.count { it.readiness is ReadinessProjection.Unavailable },
        )
        val revision = repositoryRevision(repository, synchronization, pullRequests, details)
        return RepositoryGroupProjection(
            repositoryId = repository.id,
            slug = repository.slug,
            displayName = repository.displayName,
            webUrl = repository.webUrl,
            revision = revision,
            synchronization = synchronization,
            readinessSummary = summary,
            pullRequests = pullRequests,
        )
    }
}

internal fun projectAction(
    action: StoredActionItemSnapshot,
    repository: StoredConfiguredRepository,
    pullRequest: StoredPullRequestSnapshot,
) = ActionItemProjection(
    id = action.id,
    pullRequestId = action.pullRequestId,
    repositoryId = action.repositoryId,
    repositoryDisplayName = repository.displayName,
    pullRequestNumber = pullRequest.upstreamNumber,
    pullRequestTitle = pullRequest.title,
    activityVersion = action.activityVersion,
    kind = action.sourceKind,
    actor = ActorProjection(action.actorStableId, action.actorDisplayName),
    activityAt = action.activityAt,
    state = action.state,
    acknowledgedAt = action.acknowledgedAt,
    webUrl = action.webUrl,
)

internal fun StoredActionItemSnapshot.isCurrentlyActionable(): Boolean =
    state == ActionItemState.OPEN && acknowledgedVersion != activityVersion

private fun StoredPullRequestSnapshot.card(
    storedActions: List<StoredActionItemSnapshot>,
    actions: List<ActionItemProjection>,
) = PullRequestCardProjection(
    id = id,
    repositoryId = repositoryId,
    upstreamNumber = upstreamNumber,
    title = title,
    author = ActorProjection(authorStableId, authorDisplayName),
    draft = draft,
    createdAt = createdAt,
    updatedAt = updatedAt,
    webUrl = webUrl,
    readiness = when (val stored = readiness) {
        is StoredReadiness.Available -> ReadinessProjection.Available(
            stored.passed,
            stored.total,
            stored.checks.sortedWith(readinessCheckOrder)
                .map { ReadinessCheckProjection(it.name, it.passed, it.safeReason) },
        )
        is StoredReadiness.Unavailable -> ReadinessProjection.Unavailable(stored.safeReason)
    },
    buildState = aggregateBuildState(builds),
    actionableItemCount = storedActions.count(StoredActionItemSnapshot::isCurrentlyActionable),
    acknowledgedItemCount = storedActions.count { it.state == ActionItemState.ACKNOWLEDGED },
    actionItems = actions,
)

private fun aggregateBuildState(builds: List<StoredBuildObservation>): BuildState = when {
    builds.isEmpty() || builds.all { it.state == BuildState.NO_BUILDS } -> BuildState.NO_BUILDS
    builds.any { it.state == BuildState.FAILED } -> BuildState.FAILED
    builds.any { it.state == BuildState.IN_PROGRESS } -> BuildState.IN_PROGRESS
    builds.any { it.state == BuildState.UNKNOWN || it.state == BuildState.NO_BUILDS } -> BuildState.UNKNOWN
    builds.all { it.state == BuildState.SUCCESSFUL } -> BuildState.SUCCESSFUL
    else -> BuildState.UNKNOWN
}

private fun StoredSynchronizationSnapshot?.orEmpty(repositoryId: RepositoryId) = this ?: StoredSynchronizationSnapshot(
    repositoryId = repositoryId,
    activity = SynchronizationActivity.IDLE,
    lastAttemptAt = null,
    lastAttemptOutcome = null,
    lastSuccessAt = null,
    snapshotAt = null,
    problem = SynchronizationProblem.None,
    consecutiveFailureCount = 0,
    backoffUntil = null,
    pullRequestCursor = null,
    activityCursor = null,
)

private fun StoredSynchronizationSnapshot.projection(
    now: Instant,
    policy: ProjectionFreshnessPolicy,
): SynchronizationProjection = SynchronizationProjection(
    repositoryId = repositoryId,
    activity = activity,
    lastAttemptAt = lastAttemptAt,
    lastAttemptOutcome = lastAttemptOutcome,
    lastSuccessAt = lastSuccessAt,
    freshness = freshness(now, policy),
    problem = problem.canonical(),
)

private fun SynchronizationProblem.canonical(): SynchronizationProblem = when (this) {
    SynchronizationProblem.None -> this
    is SynchronizationProblem.Present -> SynchronizationProblem.Present(
        metadata.copy(
            failures = metadata.failures.sortedWith(
                compareBy<SynchronizationFailure>(
                    { it.category.name },
                    { it.retryable },
                    { it.retryAt },
                ),
            ),
        ),
    )
}

private fun StoredSynchronizationSnapshot.freshness(now: Instant, policy: ProjectionFreshnessPolicy): Freshness {
    val capturedAt = snapshotAt ?: return Freshness.NeverSynchronized
    val age = Duration.between(capturedAt, now).coerceAtLeast(Duration.ZERO)
    return if (age >= policy.staleAfter) {
        Freshness.Stale(capturedAt, age, capturedAt.safePlus(policy.staleAfter))
    } else {
        Freshness.Fresh(capturedAt, age)
    }
}

private fun Instant.safePlus(duration: Duration): Instant =
    runCatching { plus(duration) }.getOrDefault(Instant.MAX)

private data class CompleteProjection(
    val dashboard: DashboardSnapshot,
    val details: Map<PullRequestId, PullRequestDetailProjection>,
)

private val repositoryOrder = compareBy<StoredConfiguredRepository>(
    { it.displayName.lowercase(Locale.ROOT) },
    { it.id.value },
)
private val pullRequestOrder = compareByDescending<StoredPullRequestSnapshot> { it.updatedAt }
    .thenBy { it.id.value }
private val storedActionOrder = compareByDescending<StoredActionItemSnapshot> { it.activityAt }
    .thenBy { it.id.value }
private val actionOrder = compareByDescending<ActionItemProjection> { it.activityAt }
    .thenBy { it.id.value }
private val readinessOrder = listOf(
    "APPROVALS",
    "UNRESOLVED_TASKS",
    "UNRESOLVED_COMMENTS",
    "BRANCH_FRESHNESS",
    "MERGE_CONFLICTS",
    "SUCCESSFUL_BUILDS",
    "REQUIRED_REVIEWER_STATE",
).withIndex().associate { it.value to it.index }
private val readinessCheckOrder = compareBy<StoredReadinessCheck>(
    { readinessOrder[it.name] ?: Int.MAX_VALUE },
    { it.name },
)

private fun repositoryRevision(
    repository: StoredConfiguredRepository,
    synchronization: SynchronizationProjection,
    pullRequests: List<PullRequestCardProjection>,
    details: Map<PullRequestId, PullRequestDetailProjection>,
): RepositoryRevision {
    val canonical = CanonicalComponents()
    canonical.type("repository", "repository-projection-v1")
    canonical.value("id", repository.id.value)
    canonical.value("slug", repository.slug)
    canonical.value("displayName", repository.displayName)
    canonical.value("webUrl", repository.webUrl.toASCIIString())
    canonical.synchronization(synchronization)
    canonical.list("pullRequests", pullRequests) { pullRequest ->
        canonical.pullRequest(pullRequest, requireNotNull(details[pullRequest.id]))
    }
    return RepositoryRevision(canonical.digest("rrev_"))
}

private fun dashboardRevision(
    workspace: WorkspaceConfigurationProjection,
    repositories: List<RepositoryGroupProjection>,
    inbox: InboxProjection,
    polling: DashboardPolling,
): DashboardRevision {
    val canonical = CanonicalComponents()
    canonical.type("dashboard", "dashboard-projection-v1")
    canonical.value("workspaceId", workspace.workspaceId.value)
    canonical.value("apiBaseUrl", workspace.bitbucketApiBaseUrl.toASCIIString())
    canonical.value("workspaceSlug", workspace.workspaceSlug)
    canonical.value("workspaceDisplayName", workspace.workspaceDisplayName)
    canonical.value("workspaceWebUrl", workspace.workspaceWebUrl.toASCIIString())
    canonical.value("retentionDays", workspace.retentionDays.toString())
    canonical.list("configuredRepositories", workspace.repositories) { repository ->
        canonical.type("configuredRepository", "configured-repository")
        canonical.value("id", repository.repositoryId.value)
        canonical.value("slug", repository.slug)
        canonical.value("displayName", repository.displayName)
        canonical.value("webUrl", repository.webUrl.toASCIIString())
    }
    canonical.list("repositoryRevisions", repositories) { repository ->
        canonical.value("repositoryId", repository.repositoryId.value)
        canonical.value("revision", repository.revision.value)
    }
    canonical.list("inbox", inbox.items) { canonical.action(it) }
    when (polling) {
        DashboardPolling.Idle -> canonical.type("polling", "idle")
        is DashboardPolling.Active -> {
            canonical.type("polling", "active")
            canonical.value("afterMilliseconds", polling.afterMilliseconds.toString())
        }
    }
    return DashboardRevision(canonical.digest("dr_"))
}

private class CanonicalComponents {
    private val content = StringBuilder()

    fun type(tag: String, type: String) {
        content.append('T')
        frame(tag)
        frame(type)
    }

    fun value(tag: String, value: String?) {
        content.append('S')
        frame(tag)
        if (value == null) {
            content.append('N')
        } else {
            content.append('V')
            frame(value)
        }
    }

    fun <T> list(tag: String, values: List<T>, append: (T) -> Unit) {
        content.append('L')
        frame(tag)
        frame(values.size.toString())
        values.forEach { value ->
            content.append('E')
            append(value)
        }
        content.append('Z')
    }

    fun synchronization(synchronization: SynchronizationProjection) {
        type("synchronization", "synchronization")
        value("repositoryId", synchronization.repositoryId.value)
        value("activity", synchronization.activity.name)
        value("lastAttemptAt", synchronization.lastAttemptAt?.toString())
        value("lastAttemptOutcome", synchronization.lastAttemptOutcome?.name)
        value("lastSuccessAt", synchronization.lastSuccessAt?.toString())
        when (val freshness = synchronization.freshness) {
            Freshness.NeverSynchronized -> type("freshness", "never")
            is Freshness.Fresh -> {
                type("freshness", "fresh")
                value("snapshotAt", freshness.snapshotAt.toString())
            }
            is Freshness.Stale -> {
                type("freshness", "stale")
                value("snapshotAt", freshness.snapshotAt.toString())
                value("staleSince", freshness.staleSince.toString())
            }
        }
        when (val problem = synchronization.problem) {
            SynchronizationProblem.None -> type("problem", "none")
            is SynchronizationProblem.Present -> {
                type("problem", "present")
                value("attemptedCount", problem.metadata.attemptedCount.toString())
                value("succeededCount", problem.metadata.succeededCount.toString())
                list("failures", problem.metadata.failures) { failure ->
                    type("failure", "synchronization-failure")
                    value("category", failure.category.name)
                    value("retryable", failure.retryable.toString())
                    value("retryAt", failure.retryAt?.toString())
                }
            }
        }
    }

    fun pullRequest(card: PullRequestCardProjection, detail: PullRequestDetailProjection) {
        type("pullRequest", "pull-request")
        value("id", card.id.value)
        value("repositoryId", card.repositoryId.value)
        value("upstreamNumber", card.upstreamNumber.toString())
        value("title", card.title)
        value("authorStableId", card.author.stableId)
        value("authorDisplayName", card.author.displayName)
        value("draft", card.draft.toString())
        value("createdAt", card.createdAt.toString())
        value("updatedAt", card.updatedAt.toString())
        value("webUrl", card.webUrl.toASCIIString())
        when (val readiness = card.readiness) {
            is ReadinessProjection.Available -> {
                type("readiness", "available")
                value("passed", readiness.passed.toString())
                value("total", readiness.total.toString())
                list("checks", readiness.checks) { check ->
                    type("check", "readiness-check")
                    value("name", check.name)
                    value("passed", check.passed.toString())
                    value("safeReason", check.safeReason)
                }
            }
            is ReadinessProjection.Unavailable -> {
                type("readiness", "unavailable")
                value("safeReason", readiness.safeReason)
            }
        }
        value("buildState", card.buildState.name)
        value("actionableItemCount", card.actionableItemCount.toString())
        value("acknowledgedItemCount", card.acknowledgedItemCount.toString())
        list("actionItems", card.actionItems) { action(it) }
        value("headCommit", detail.headCommit)
        list("builds", detail.builds) { build ->
            type("build", "build")
            value("key", build.key)
            value("state", build.state.name)
        }
    }

    fun action(action: ActionItemProjection) {
        type("action", "action-item")
        value("id", action.id.value)
        value("pullRequestId", action.pullRequestId.value)
        value("repositoryId", action.repositoryId.value)
        value("repositoryDisplayName", action.repositoryDisplayName)
        value("pullRequestNumber", action.pullRequestNumber.toString())
        value("pullRequestTitle", action.pullRequestTitle)
        value("activityVersion", action.activityVersion.value)
        value("kind", action.kind)
        value("actorStableId", action.actor.stableId)
        value("actorDisplayName", action.actor.displayName)
        value("activityAt", action.activityAt.toString())
        value("state", action.state.name)
        value("acknowledgedAt", action.acknowledgedAt?.toString())
        value("webUrl", action.webUrl.toASCIIString())
    }

    fun digest(prefix: String): String {
        val bytes = MessageDigest.getInstance("SHA-256")
            .digest(content.toString().toByteArray(StandardCharsets.UTF_8))
        return prefix + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    private fun frame(value: String) {
        val bytes = value.toByteArray(StandardCharsets.UTF_8)
        content.append(bytes.size).append(':').append(value)
    }
}

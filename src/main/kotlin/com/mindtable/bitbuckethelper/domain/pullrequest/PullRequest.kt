package com.mindtable.bitbuckethelper.domain.pullrequest

import com.mindtable.bitbuckethelper.domain.shared.PullRequestId
import com.mindtable.bitbuckethelper.domain.shared.RepositoryId
import java.net.URI
import java.time.Instant

data class BuildObservation(
    val key: String,
    val status: BuildStatus,
    val observedAt: Instant,
)

data class PullRequestObservation(
    val id: PullRequestId,
    val repositoryId: RepositoryId,
    val upstreamNumber: Long,
    val title: String,
    val authorStableId: String,
    val authorDisplayName: String,
    val draft: Boolean,
    val headCommit: String,
    val webUrl: URI,
    val createdAt: Instant,
    val updatedAt: Instant,
    val observedAt: Instant,
    val readiness: ReadinessAssessment,
    val builds: List<BuildObservation>,
)

enum class PullRequestTransitionDisposition {
    APPLIED,
    IGNORED_IDENTICAL,
    IGNORED_STALE,
    REJECTED_CONFLICTING_TIMESTAMP,
}

data class PullRequestTransition(
    val pullRequest: PullRequest,
    val facts: List<PullRequestEvent>,
    val disposition: PullRequestTransitionDisposition,
)

data class PullRequest(
    val id: PullRequestId,
    val repositoryId: RepositoryId,
    val upstreamNumber: Long,
    val title: String,
    val authorStableId: String,
    val authorDisplayName: String,
    val draft: Boolean,
    val headCommit: String,
    val webUrl: URI,
    val createdAt: Instant,
    val updatedAt: Instant,
    val observedAt: Instant,
    val active: Boolean,
    val inactiveAt: Instant?,
    val readiness: ReadinessAssessment,
    val builds: List<BuildObservation>,
    val buildsWereGreen: Boolean,
) {
    fun observe(observation: PullRequestObservation): PullRequestTransition {
        val canonicalObservation = observation.copy(builds = observation.builds.canonical())
        requireStableIdentity(canonicalObservation)

        if (canonicalObservation.observedAt < observedAt) return transition(PullRequestTransitionDisposition.IGNORED_STALE)
        if (canonicalObservation.observedAt == observedAt) {
            return if (active && matches(canonicalObservation)) {
                transition(PullRequestTransitionDisposition.IGNORED_IDENTICAL)
            } else {
                transition(PullRequestTransitionDisposition.REJECTED_CONFLICTING_TIMESTAMP)
            }
        }

        val nowGreen = canonicalObservation.builds.areAllSuccessful()
        val updated = copy(
            title = canonicalObservation.title,
            authorStableId = canonicalObservation.authorStableId,
            authorDisplayName = canonicalObservation.authorDisplayName,
            draft = canonicalObservation.draft,
            headCommit = canonicalObservation.headCommit,
            webUrl = canonicalObservation.webUrl,
            createdAt = canonicalObservation.createdAt,
            updatedAt = canonicalObservation.updatedAt,
            observedAt = canonicalObservation.observedAt,
            active = true,
            inactiveAt = null,
            readiness = canonicalObservation.readiness,
            builds = canonicalObservation.builds,
            buildsWereGreen = nowGreen,
        )
        val facts = buildList {
            if (readiness != canonicalObservation.readiness) add(ReadinessChanged(id, readiness, canonicalObservation.readiness))
            if (!buildsWereGreen && nowGreen) add(BuildsBecameGreen(id))
            if (!active) add(PullRequestReactivated(id))
        }
        return PullRequestTransition(updated, facts, PullRequestTransitionDisposition.APPLIED)
    }

    fun deactivate(authoritativeSuccessfulListAt: Instant): PullRequestTransition {
        if (!active) return transition(PullRequestTransitionDisposition.IGNORED_IDENTICAL)
        if (authoritativeSuccessfulListAt < observedAt) return transition(PullRequestTransitionDisposition.IGNORED_STALE)

        val updated = copy(
            observedAt = authoritativeSuccessfulListAt,
            active = false,
            inactiveAt = authoritativeSuccessfulListAt,
        )
        return PullRequestTransition(
            updated,
            listOf(PullRequestDeactivated(id)),
            PullRequestTransitionDisposition.APPLIED,
        )
    }

    private fun requireStableIdentity(observation: PullRequestObservation) {
        require(id == observation.id) { "Pull request id cannot change" }
        require(repositoryId == observation.repositoryId) { "Repository id cannot change" }
        require(upstreamNumber == observation.upstreamNumber) { "Upstream number cannot change" }
    }

    private fun matches(observation: PullRequestObservation): Boolean =
        title == observation.title &&
            authorStableId == observation.authorStableId &&
            authorDisplayName == observation.authorDisplayName &&
            draft == observation.draft &&
            headCommit == observation.headCommit &&
            webUrl == observation.webUrl &&
            createdAt == observation.createdAt &&
            updatedAt == observation.updatedAt &&
            readiness == observation.readiness &&
            builds == observation.builds

    private fun transition(disposition: PullRequestTransitionDisposition) =
        PullRequestTransition(this, emptyList(), disposition)

    companion object {
        fun from(observation: PullRequestObservation): PullRequestTransition {
            val canonicalObservation = observation.copy(builds = observation.builds.canonical())
            val pullRequest = PullRequest(
                id = canonicalObservation.id,
                repositoryId = canonicalObservation.repositoryId,
                upstreamNumber = canonicalObservation.upstreamNumber,
                title = canonicalObservation.title,
                authorStableId = canonicalObservation.authorStableId,
                authorDisplayName = canonicalObservation.authorDisplayName,
                draft = canonicalObservation.draft,
                headCommit = canonicalObservation.headCommit,
                webUrl = canonicalObservation.webUrl,
                createdAt = canonicalObservation.createdAt,
                updatedAt = canonicalObservation.updatedAt,
                observedAt = canonicalObservation.observedAt,
                active = true,
                inactiveAt = null,
                readiness = canonicalObservation.readiness,
                builds = canonicalObservation.builds,
                buildsWereGreen = canonicalObservation.builds.areAllSuccessful(),
            )
            return PullRequestTransition(pullRequest, emptyList(), PullRequestTransitionDisposition.APPLIED)
        }
    }
}

private fun List<BuildObservation>.areAllSuccessful(): Boolean =
    isNotEmpty() && all { it.status == BuildStatus.SUCCESSFUL }

private fun List<BuildObservation>.canonical(): List<BuildObservation> {
    require(map(BuildObservation::key).distinct().size == size) { "Build observation keys must be unique" }
    return sortedBy(BuildObservation::key)
}

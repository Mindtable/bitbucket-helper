package com.mindtable.bitbuckethelper.domain.pullrequest

import com.mindtable.bitbuckethelper.domain.shared.PullRequestId
import com.mindtable.bitbuckethelper.domain.shared.RepositoryId
import java.net.URI
import java.time.Instant
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PullRequestTest {
    @Test
    fun `first observation records current state without transition facts`() {
        val transition = PullRequest.from(observation())

        assertEquals(PullRequestTransitionDisposition.APPLIED, transition.disposition)
        assertTrue(transition.pullRequest.active)
        assertEquals("Improve refresh", transition.pullRequest.title)
        assertTrue(transition.pullRequest.buildsWereGreen)
        assertEquals(emptyList<PullRequestEvent>(), transition.facts)
    }

    @Test
    fun `identical observation at the same timestamp is an idempotent replay`() {
        val aggregate = PullRequest.from(observation()).pullRequest

        val transition = aggregate.observe(observation())

        assertEquals(PullRequestTransitionDisposition.IGNORED_IDENTICAL, transition.disposition)
        assertEquals(aggregate, transition.pullRequest)
        assertTrue(transition.facts.isEmpty())
    }

    @Test
    fun `uniquely keyed builds are canonical and insensitive to input order`() {
        val aggregate = PullRequest.from(
            observation(builds = listOf(build("build-b", BuildStatus.FAILED), build("build-a", BuildStatus.SUCCESSFUL))),
        ).pullRequest

        val transition = aggregate.observe(
            observation(builds = listOf(build("build-a", BuildStatus.SUCCESSFUL), build("build-b", BuildStatus.FAILED))),
        )

        assertEquals(PullRequestTransitionDisposition.IGNORED_IDENTICAL, transition.disposition)
        assertEquals(listOf("build-a", "build-b"), transition.pullRequest.builds.map(BuildObservation::key))
    }

    @Test
    fun `duplicate build keys are rejected before an observation can become state`() {
        assertThrows(IllegalArgumentException::class.java) {
            PullRequest.from(
                observation(builds = listOf(build("build-1", BuildStatus.SUCCESSFUL), build("build-1", BuildStatus.FAILED))),
            )
        }
    }

    @Test
    fun `metadata update changes state without readiness or green facts`() {
        val aggregate = PullRequest.from(observation()).pullRequest

        val transition = aggregate.observe(observation(observedAt = AT_2, title = "Improve refresh safely"))

        assertEquals(PullRequestTransitionDisposition.APPLIED, transition.disposition)
        assertEquals("Improve refresh safely", transition.pullRequest.title)
        assertTrue(transition.facts.isEmpty())
    }

    @Test
    fun `readiness update emits the previous and current readiness`() {
        val aggregate = PullRequest.from(observation(readiness = readiness(false))).pullRequest

        val transition = aggregate.observe(observation(observedAt = AT_2, readiness = readiness(true)))

        assertEquals(
            listOf(ReadinessChanged(PULL_REQUEST_ID, readiness(false), readiness(true))),
            transition.facts,
        )
    }

    @Test
    fun `all successful builds emit a fact only on a false to true edge`() {
        val aggregate = PullRequest.from(observation(builds = listOf(build("build-1", BuildStatus.FAILED)))).pullRequest
        val green = observation(observedAt = AT_2, builds = listOf(build("build-1", BuildStatus.SUCCESSFUL)))

        val becameGreen = aggregate.observe(green)
        val replay = becameGreen.pullRequest.observe(green)
        val stillGreen = replay.pullRequest.observe(
            green.copy(observedAt = AT_3, title = "Metadata after green"),
        )

        assertEquals(listOf(BuildsBecameGreen(PULL_REQUEST_ID)), becameGreen.facts)
        assertTrue(replay.facts.isEmpty())
        assertTrue(stillGreen.facts.isEmpty())
    }

    @Test
    fun `deactivation retains observation data and a newer observation reactivates once`() {
        val aggregate = PullRequest.from(observation(readiness = readiness(true))).pullRequest

        val deactivated = aggregate.deactivate(AT_2)
        val repeated = deactivated.pullRequest.deactivate(AT_2)
        val reactivated = repeated.pullRequest.observe(observation(observedAt = AT_3, readiness = readiness(true)))
        val replay = reactivated.pullRequest.observe(observation(observedAt = AT_3, readiness = readiness(true)))

        assertFalse(deactivated.pullRequest.active)
        assertEquals(AT_2, deactivated.pullRequest.inactiveAt)
        assertEquals("Improve refresh", deactivated.pullRequest.title)
        assertEquals(listOf(PullRequestDeactivated(PULL_REQUEST_ID)), deactivated.facts)
        assertTrue(repeated.facts.isEmpty())
        assertTrue(reactivated.pullRequest.active)
        assertEquals(listOf(PullRequestReactivated(PULL_REQUEST_ID)), reactivated.facts)
        assertTrue(replay.facts.isEmpty())
    }

    @Test
    fun `stale observation cannot overwrite state or emit facts`() {
        val aggregate = PullRequest.from(observation(observedAt = AT_2, title = "Current")).pullRequest

        val transition = aggregate.observe(observation(observedAt = AT_1, title = "Stale", readiness = readiness(true)))

        assertEquals(PullRequestTransitionDisposition.IGNORED_STALE, transition.disposition)
        assertEquals("Current", transition.pullRequest.title)
        assertTrue(transition.facts.isEmpty())
    }

    @Test
    fun `conflicting observation at the same timestamp is explicitly rejected`() {
        val aggregate = PullRequest.from(observation()).pullRequest

        val transition = aggregate.observe(observation(title = "Conflicting value"))

        assertEquals(PullRequestTransitionDisposition.REJECTED_CONFLICTING_TIMESTAMP, transition.disposition)
        assertEquals("Improve refresh", transition.pullRequest.title)
        assertTrue(transition.facts.isEmpty())
    }

    @Test
    fun `observation with a changed stable identity is rejected`() {
        val aggregate = PullRequest.from(observation()).pullRequest

        assertThrows(IllegalArgumentException::class.java) {
            aggregate.observe(observation(upstreamNumber = 43, observedAt = AT_2))
        }
    }

    private fun observation(
        observedAt: Instant = AT_1,
        upstreamNumber: Long = 42,
        title: String = "Improve refresh",
        readiness: ReadinessAssessment = readiness(false),
        builds: List<BuildObservation> = listOf(build("build-1", BuildStatus.SUCCESSFUL)),
    ) = PullRequestObservation(
        id = PULL_REQUEST_ID,
        repositoryId = REPOSITORY_ID,
        upstreamNumber = upstreamNumber,
        title = title,
        authorStableId = "{ada}",
        authorDisplayName = "Ada",
        draft = false,
        headCommit = "abc123",
        webUrl = URI("https://bitbucket.org/acme/alpha/pull-requests/$upstreamNumber"),
        createdAt = AT_0,
        updatedAt = observedAt,
        observedAt = observedAt,
        readiness = readiness,
        builds = builds,
    )

    private fun build(key: String, status: BuildStatus) = BuildObservation(key, status, AT_1)

    private fun readiness(passed: Boolean) = ReadinessAssessment(
        listOf(ReadinessCheck(ReadinessCheckName.APPROVALS, passed, if (passed) null else "No approval observed")),
    )

    private companion object {
        val PULL_REQUEST_ID = PullRequestId("pr_alpha-42")
        val REPOSITORY_ID = RepositoryId("repo_alpha")
        val AT_0 = Instant.parse("2026-08-15T10:00:00Z")
        val AT_1 = Instant.parse("2026-08-15T10:01:00Z")
        val AT_2 = Instant.parse("2026-08-15T10:02:00Z")
        val AT_3 = Instant.parse("2026-08-15T10:03:00Z")
    }
}

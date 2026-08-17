package com.mindtable.bitbuckethelper.application.policy

import com.mindtable.bitbuckethelper.application.model.NotificationTransitionFact
import java.time.Instant
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class DefaultNotificationIntentPolicyTest {
    @Test
    fun `notification transition facts provide the creation time required by every durable intent`() {
        // Catches a policy that fabricates createdAt rather than receiving the durable creation fact.
        val createdAtAccessor = NotificationTransitionFact::class.java.methods.singleOrNull {
            it.name == "getCreatedAt"
        }

        assertEquals(Instant::class.java, createdAtAccessor?.returnType)
    }

    @Test
    fun `first repository digest creates one safe deterministic intent`() {
        // Catches a policy that omits an initial digest or uses the wrong durable payload.
        val result = DefaultNotificationIntentPolicy().createIntents(
            listOf(initialDigest(actionableItemCount = 2)),
        )

        assertEquals(
            listOf(
                com.mindtable.bitbuckethelper.application.model.NewNotificationIntent(
                    request = com.mindtable.bitbuckethelper.application.model.NotificationRequest(
                        deliveryKey = com.mindtable.bitbuckethelper.application.model.NotificationDeliveryKey(
                            "initial-digest:repo_alpha",
                        ),
                        title = "Bitbucket Helper",
                        body = "Alpha: 2 items need attention",
                        openUrl = java.net.URI("https://bitbucket.org/acme/alpha"),
                        sound = com.mindtable.bitbuckethelper.application.model.NotificationSound.DEFAULT,
                    ),
                    createdAt = Instant.parse("2026-08-16T09:00:00Z"),
                ),
            ),
            result,
        )
    }

    @Test
    fun `new and advanced actionable activity use their exact activity versions`() {
        // Catches a policy that collapses distinct activity versions into one delivery key.
        val first = actionableActivity(activityVersion = "av_alpha-42-v1")
        val advanced = actionableActivity(activityVersion = "av_alpha-42-v2")

        val result = DefaultNotificationIntentPolicy().createIntents(listOf(advanced, first))

        assertEquals(
            listOf(
                "actionable:ai_alpha-42:av_alpha-42-v1",
                "actionable:ai_alpha-42:av_alpha-42-v2",
            ),
            result.map { it.request.deliveryKey.value },
        )
        assertEquals(
            listOf("Alpha: PR #42 needs attention", "Alpha: PR #42 needs attention"),
            result.map { it.request.body },
        )
        assertEquals(
            listOf(Instant.parse("2026-08-16T09:01:00Z"), Instant.parse("2026-08-16T09:01:00Z")),
            result.map { it.createdAt },
        )
    }

    @Test
    fun `all builds green edge creates one green intent`() {
        // Catches a policy that drops the all-builds-green transition or assigns its action key.
        val result = DefaultNotificationIntentPolicy().createIntents(listOf(buildsBecameGreen()))

        assertEquals(
            listOf(
                com.mindtable.bitbuckethelper.application.model.NewNotificationIntent(
                    request = com.mindtable.bitbuckethelper.application.model.NotificationRequest(
                        deliveryKey = com.mindtable.bitbuckethelper.application.model.NotificationDeliveryKey(
                            "builds-green:bgt_alpha-42-edge-1",
                        ),
                        title = "Bitbucket Helper",
                        body = "Alpha: PR #42 builds are green",
                        openUrl = java.net.URI("https://bitbucket.org/acme/alpha/pull-requests/42"),
                        sound = com.mindtable.bitbuckethelper.application.model.NotificationSound.DEFAULT,
                    ),
                    createdAt = Instant.parse("2026-08-16T09:02:00Z"),
                ),
            ),
            result,
        )
    }

    @Test
    fun `replaying the same transition facts returns byte-for-byte equal intents`() {
        // Catches a policy that draws time, jitter, or other nondeterministic state during mapping.
        val facts = listOf(initialDigest(2), actionableActivity(), buildsBecameGreen())

        assertEquals(
            DefaultNotificationIntentPolicy().createIntents(facts),
            DefaultNotificationIntentPolicy().createIntents(facts),
        )
    }

    @Test
    fun `notification payloads use fixed safe text default sound and only HTTPS URLs`() {
        // Catches a policy that leaks PR text, changes sound, or passes a non-HTTPS URL to the provider.
        val fact = actionableActivity(
            pullRequestTitle = "RAW-COMMENT-BODY-TITLE-MARKER",
            pullRequestWebUrl = "http://bitbucket.org/acme/alpha/pull-requests/42",
        )

        val result = DefaultNotificationIntentPolicy().createIntents(listOf(fact)).single().request

        assertEquals("Bitbucket Helper", result.title)
        assertEquals("Alpha: PR #42 needs attention", result.body)
        org.junit.jupiter.api.Assertions.assertNull(result.openUrl)
        assertEquals(com.mindtable.bitbuckethelper.application.model.NotificationSound.DEFAULT, result.sound)
    }

    @Test
    fun `multiple facts have delivery-key order independent of refresh emission order`() {
        // Catches a policy that preserves nondeterministic upstream iteration order.
        val result = DefaultNotificationIntentPolicy().createIntents(
            listOf(buildsBecameGreen(), initialDigest(repositoryId = "repo_beta"), actionableActivity()),
        )

        assertEquals(
            listOf(
                "actionable:ai_alpha-42:av_alpha-42-v1",
                "builds-green:bgt_alpha-42-edge-1",
                "initial-digest:repo_beta",
            ),
            result.map { it.request.deliveryKey.value },
        )
    }

    @Test
    fun `no refresh transition facts produce no notification intents`() {
        // Catches a policy that invents a user-visible notification for a no-op refresh.
        assertEquals(emptyList<com.mindtable.bitbuckethelper.application.model.NewNotificationIntent>(), DefaultNotificationIntentPolicy().createIntents(emptyList()))
    }

    @Test
    fun `raw activity markers never enter action or green intents`() {
        // Catches a policy that copies raw actor, comment, body, title, provider, or commit text into an intent.
        val marker = "RAW-ACTOR-COMMENT-BODY-TITLE-PROVIDER-MARKER"
        val result = DefaultNotificationIntentPolicy().createIntents(
            listOf(
                actionableActivity(pullRequestTitle = marker),
                buildsBecameGreen(pullRequestTitle = marker, headCommit = marker),
            ),
        )
        val persistedIntentText = result.joinToString("|") {
            "${it.request.deliveryKey.value}|${it.request.title}|${it.request.body}|${it.request.openUrl}|${it.request.sound}|${it.createdAt}"
        }

        assertEquals(false, persistedIntentText.contains(marker))
        assertEquals(
            listOf("Alpha: PR #42 needs attention", "Alpha: PR #42 builds are green"),
            result.map { it.request.body },
        )
    }

    private fun initialDigest(
        actionableItemCount: Int = 2,
        repositoryId: String = "repo_alpha",
    ): NotificationTransitionFact.InitialRepositoryDigest = NotificationTransitionFact.InitialRepositoryDigest(
        repositoryId = com.mindtable.bitbuckethelper.domain.shared.RepositoryId(repositoryId),
        repositoryDisplayName = "Alpha",
        repositoryWebUrl = java.net.URI("https://bitbucket.org/acme/alpha"),
        actionableItemCount = actionableItemCount,
        createdAt = Instant.parse("2026-08-16T09:00:00Z"),
    )

    private fun actionableActivity(
        activityVersion: String = "av_alpha-42-v1",
        pullRequestTitle: String = "Keep contracts boring",
        pullRequestWebUrl: String = "https://bitbucket.org/acme/alpha/pull-requests/42",
    ): NotificationTransitionFact.ActionableActivity = NotificationTransitionFact.ActionableActivity(
        repositoryId = com.mindtable.bitbuckethelper.domain.shared.RepositoryId("repo_alpha"),
        repositoryDisplayName = "Alpha",
        repositoryWebUrl = java.net.URI("https://bitbucket.org/acme/alpha"),
        pullRequestId = com.mindtable.bitbuckethelper.domain.shared.PullRequestId("pr_alpha-42"),
        pullRequestNumber = 42,
        pullRequestTitle = pullRequestTitle,
        pullRequestWebUrl = java.net.URI(pullRequestWebUrl),
        actionItemId = com.mindtable.bitbuckethelper.domain.shared.ActionItemId("ai_alpha-42"),
        activityVersion = com.mindtable.bitbuckethelper.domain.shared.ActivityVersion(activityVersion),
        createdAt = Instant.parse("2026-08-16T09:01:00Z"),
    )

    private fun buildsBecameGreen(
        pullRequestTitle: String = "Keep contracts boring",
        headCommit: String = "abc123",
    ): NotificationTransitionFact.BuildsBecameGreen = NotificationTransitionFact.BuildsBecameGreen(
        repositoryId = com.mindtable.bitbuckethelper.domain.shared.RepositoryId("repo_alpha"),
        repositoryDisplayName = "Alpha",
        repositoryWebUrl = java.net.URI("https://bitbucket.org/acme/alpha"),
        pullRequestId = com.mindtable.bitbuckethelper.domain.shared.PullRequestId("pr_alpha-42"),
        pullRequestNumber = 42,
        pullRequestTitle = pullRequestTitle,
        pullRequestWebUrl = java.net.URI("https://bitbucket.org/acme/alpha/pull-requests/42"),
        headCommit = headCommit,
        transitionId = com.mindtable.bitbuckethelper.domain.shared.BuildGreenTransitionId("bgt_alpha-42-edge-1"),
        createdAt = Instant.parse("2026-08-16T09:02:00Z"),
    )
}

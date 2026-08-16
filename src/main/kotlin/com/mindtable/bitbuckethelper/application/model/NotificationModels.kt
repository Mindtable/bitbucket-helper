package com.mindtable.bitbuckethelper.application.model

import com.mindtable.bitbuckethelper.domain.shared.ActionItemId
import com.mindtable.bitbuckethelper.domain.shared.ActivityVersion
import com.mindtable.bitbuckethelper.domain.shared.BuildGreenTransitionId
import com.mindtable.bitbuckethelper.domain.shared.NotificationAttemptId
import com.mindtable.bitbuckethelper.domain.shared.NotificationIntentId
import com.mindtable.bitbuckethelper.domain.shared.PullRequestId
import com.mindtable.bitbuckethelper.domain.shared.RepositoryId
import java.net.URI
import java.time.Instant

@JvmInline
value class NotificationDeliveryKey(val value: String) {
    init { require(value.isNotBlank()) { "Notification delivery key must not be blank" } }
}

enum class NotificationSound {
    DEFAULT,
    BASSO,
    BLOW,
    BOTTLE,
    FROG,
    FUNK,
    GLASS,
    HERO,
    MORSE,
    PING,
    POP,
    PURR,
    SOS,
    SUBMARINE,
    TINK,
}

data class NotificationRequest(
    val deliveryKey: NotificationDeliveryKey,
    val title: String,
    val body: String,
    val openUrl: URI?,
    val sound: NotificationSound,
)

data class NewNotificationIntent(
    val request: NotificationRequest,
    val createdAt: Instant,
)

enum class NotificationIntentState { PENDING, ACCEPTED, EXHAUSTED }

data class NotificationLease(
    val owner: String,
    val acquiredAt: Instant,
    val expiresAt: Instant,
)

data class StoredNotificationIntent(
    val id: NotificationIntentId,
    val request: NotificationRequest,
    val createdAt: Instant,
    val state: NotificationIntentState,
    val attemptCount: Int,
    val nextAttemptAt: Instant?,
    val lease: NotificationLease?,
)

enum class NotificationDeliveryFailureCategory {
    INVALID_ARGUMENTS,
    UNSUPPORTED_PLATFORM,
    DEPENDENCY_UNAVAILABLE,
    DELIVERY_TIMEOUT,
    DELIVERY_FAILED,
    INTERNAL_ERROR,
    PROCESS_NOT_STARTED,
    MALFORMED_RESPONSE,
    UNEXPECTED_EXIT,
    TERMINATED_BY_SIGNAL,
    AMBIGUOUS_PROCESS_FAILURE,
}

sealed interface NotificationDeliveryResult {
    data object Accepted : NotificationDeliveryResult
    data class Failed(
        val category: NotificationDeliveryFailureCategory,
        val ambiguous: Boolean,
    ) : NotificationDeliveryResult
}

data class StoredNotificationAttempt(
    val id: NotificationAttemptId,
    val intentId: NotificationIntentId,
    val attemptNumber: Int,
    val completedAt: Instant,
    val result: NotificationDeliveryResult,
)

sealed interface NotificationIntentInsertResult {
    data class Inserted(val intent: StoredNotificationIntent) : NotificationIntentInsertResult
    data class Existing(val intent: StoredNotificationIntent) : NotificationIntentInsertResult
}

data class NotificationAttemptCompletion(
    val attempt: StoredNotificationAttempt,
    val resultingState: NotificationIntentState,
    val nextAttemptAt: Instant?,
)

sealed interface NotificationTransitionFact {
    val repositoryId: RepositoryId
    val repositoryDisplayName: String
    val repositoryWebUrl: URI
    /** Core's durable observation or transition-commit time; policies must not fabricate it. */
    val createdAt: Instant

    data class InitialRepositoryDigest(
        override val repositoryId: RepositoryId,
        override val repositoryDisplayName: String,
        override val repositoryWebUrl: URI,
        val actionableItemCount: Int,
        override val createdAt: Instant,
    ) : NotificationTransitionFact

    data class ActionableActivity(
        override val repositoryId: RepositoryId,
        override val repositoryDisplayName: String,
        override val repositoryWebUrl: URI,
        val pullRequestId: PullRequestId,
        val pullRequestNumber: Long,
        val pullRequestTitle: String,
        val pullRequestWebUrl: URI,
        val actionItemId: ActionItemId,
        val activityVersion: ActivityVersion,
        override val createdAt: Instant,
    ) : NotificationTransitionFact

    data class BuildsBecameGreen(
        override val repositoryId: RepositoryId,
        override val repositoryDisplayName: String,
        override val repositoryWebUrl: URI,
        val pullRequestId: PullRequestId,
        val pullRequestNumber: Long,
        val pullRequestTitle: String,
        val pullRequestWebUrl: URI,
        val headCommit: String,
        val transitionId: BuildGreenTransitionId,
        override val createdAt: Instant,
    ) : NotificationTransitionFact
}

data class ReminderNotificationFact(
    val repositoryId: RepositoryId,
    val repositoryDisplayName: String,
    val repositoryWebUrl: URI,
    val actionableItemCount: Int,
    val utcHour: Instant,
)

data class NotificationDispatchSummary(
    val attemptedIntentIds: List<NotificationIntentId>,
    val acceptedCount: Int,
    val retryScheduledCount: Int,
    val exhaustedCount: Int,
)

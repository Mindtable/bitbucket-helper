package com.mindtable.bitbuckethelper.adapter.outbound.persistence

import com.mindtable.bitbuckethelper.application.model.*
import com.mindtable.bitbuckethelper.domain.shared.*
import java.net.URI
import java.time.Instant
import org.jooq.Record

internal object JooqRecordMappings {
    private const val SORTABLE_PREFIX = "@"
    private const val SORTABLE_SECOND_WIDTH = 17
    private const val NANO_WIDTH = 9

    fun instant(value: String?): Instant? = value?.let { encoded ->
        if (!encoded.startsWith(SORTABLE_PREFIX)) {
            Instant.parse(encoded)
        } else {
            require(encoded.length == SORTABLE_PREFIX.length + SORTABLE_SECOND_WIDTH + NANO_WIDTH) {
                "Invalid sortable Instant encoding"
            }
            val secondsOffset = encoded.substring(1, 1 + SORTABLE_SECOND_WIDTH).toLong()
            val nano = encoded.substring(1 + SORTABLE_SECOND_WIDTH).toLong()
            Instant.ofEpochSecond(Instant.MIN.epochSecond + secondsOffset, nano)
        }
    }

    fun text(value: Instant?): String? = value?.let { instant ->
        val secondsOffset = instant.epochSecond - Instant.MIN.epochSecond
        SORTABLE_PREFIX +
            secondsOffset.toString().padStart(SORTABLE_SECOND_WIDTH, '0') +
            instant.nano.toString().padStart(NANO_WIDTH, '0')
    }
    fun bool(value: Any?): Boolean = (value as Number).toInt() != 0
    fun Record.string(name: String): String = requireNotNull(get(name, String::class.java))
    fun Record.nullableString(name: String): String? = get(name, String::class.java)
    fun Record.int(name: String): Int = requireNotNull(get(name, Int::class.javaObjectType))
    fun Record.long(name: String): Long = requireNotNull(get(name, Long::class.javaObjectType))

    fun configuration(record: Record, repositories: List<StoredConfiguredRepository>) = StoredInstallationConfiguration(
        WorkspaceId(record.string("workspace_id")), URI(record.string("api_base_url")), record.string("workspace_slug"),
        record.string("workspace_display_name"), URI(record.string("workspace_web_url")), record.string("current_user_stable_id"),
        record.string("current_user_display_name"), instant(record.string("configured_at"))!!, record.int("retention_days"), repositories,
    )
    fun repository(record: Record) = StoredConfiguredRepository(
        RepositoryId(record.string("id")), WorkspaceId(record.string("workspace_id")), record.string("slug"), record.string("display_name"),
        URI(record.string("web_url")), instant(record.nullableString("removed_at")),
    )
    fun pullRequest(record: Record, checks: List<StoredReadinessCheck>, builds: List<StoredBuildObservation>): StoredPullRequestSnapshot {
        val readiness = if (record.string("readiness_kind") == "AVAILABLE") StoredReadiness.Available(record.int("readiness_passed"), record.int("readiness_total"), checks)
        else StoredReadiness.Unavailable(record.string("readiness_safe_reason"))
        return StoredPullRequestSnapshot(
            PullRequestId(record.string("id")), RepositoryId(record.string("repository_id")), record.long("upstream_number"), record.string("title"),
            record.string("author_stable_id"), record.string("author_display_name"), bool(record.get("draft")), record.string("head_commit"), URI(record.string("web_url")),
            instant(record.string("created_at"))!!, instant(record.string("updated_at"))!!, instant(record.string("observed_at"))!!, bool(record.get("active")),
            instant(record.nullableString("inactive_at")), readiness, builds, bool(record.get("builds_were_green")),
        )
    }
    fun readinessCheck(record: Record) = StoredReadinessCheck(record.string("name"), bool(record.get("passed")), record.nullableString("safe_reason"))
    fun build(record: Record) = StoredBuildObservation(record.string("build_key"), BuildState.valueOf(record.string("state")), instant(record.string("observed_at"))!!)
    fun actionItem(record: Record) = StoredActionItemSnapshot(
        ActionItemId(record.string("id")), PullRequestId(record.string("pull_request_id")), RepositoryId(record.string("repository_id")), record.string("source_kind"),
        record.string("upstream_source_id"), record.string("actor_stable_id"), record.string("actor_display_name"), instant(record.string("activity_at"))!!,
        instant(record.string("observed_at"))!!, ActivityVersion(record.string("activity_version")), ActionItemState.valueOf(record.string("state")),
        record.nullableString("acknowledged_version")?.let(::ActivityVersion), instant(record.nullableString("acknowledged_at")), URI(record.string("web_url")),
    )
    fun synchronization(record: Record, failures: List<SynchronizationFailure>): StoredSynchronizationSnapshot {
        val problem = if (record.string("problem_kind") == "NONE") SynchronizationProblem.None else SynchronizationProblem.Present(
            PartialFailureMetadata(record.int("attempted_count"), record.int("succeeded_count"), failures),
        )
        return StoredSynchronizationSnapshot(
            RepositoryId(record.string("repository_id")), SynchronizationActivity.valueOf(record.string("activity")), instant(record.nullableString("last_attempt_at")),
            record.nullableString("last_attempt_outcome")?.let(SynchronizationAttemptOutcome::valueOf), instant(record.nullableString("last_success_at")),
            instant(record.nullableString("snapshot_at")), problem, record.int("consecutive_failure_count"), instant(record.nullableString("backoff_until")),
            record.nullableString("pull_request_cursor"), record.nullableString("activity_cursor"),
        )
    }
    fun failure(record: Record) = SynchronizationFailure(
        SynchronizationFailureCategory.valueOf(record.string("category")), bool(record.get("retryable")), instant(record.nullableString("retry_at")),
    )
    fun intent(record: Record): StoredNotificationIntent {
        val owner = record.nullableString("lease_owner")
        val lease = owner?.let { NotificationLease(it, instant(record.string("lease_acquired_at"))!!, instant(record.string("lease_expires_at"))!!) }
        return StoredNotificationIntent(
            NotificationIntentId(record.string("id")), NotificationRequest(NotificationDeliveryKey(record.string("delivery_key")), record.string("title"), record.string("body"),
                record.nullableString("open_url")?.let(::URI), NotificationSound.valueOf(record.string("sound"))), instant(record.string("created_at"))!!,
            NotificationIntentState.valueOf(record.string("state")), record.int("attempt_count"), instant(record.nullableString("next_attempt_at")), lease,
        )
    }
    fun attempt(record: Record): StoredNotificationAttempt {
        val result = if (record.string("result_kind") == "ACCEPTED") NotificationDeliveryResult.Accepted else NotificationDeliveryResult.Failed(
            NotificationDeliveryFailureCategory.valueOf(record.string("failure_category")), bool(record.get("ambiguous")),
        )
        return StoredNotificationAttempt(NotificationAttemptId(record.string("id")), NotificationIntentId(record.string("intent_id")), record.int("attempt_number"), instant(record.string("completed_at"))!!, result)
    }
}

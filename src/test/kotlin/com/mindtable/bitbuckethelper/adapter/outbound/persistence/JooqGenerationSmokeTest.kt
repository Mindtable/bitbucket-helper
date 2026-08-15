package com.mindtable.bitbuckethelper.adapter.outbound.persistence

import com.mindtable.bitbuckethelper.adapter.outbound.persistence.generated.tables.references.BITBUCKET_CONNECTION_SNAPSHOT
import com.mindtable.bitbuckethelper.adapter.outbound.persistence.generated.tables.references.*
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class JooqGenerationSmokeTest {
    @Test
    fun `generated singleton table exposes every migration-owned column`() {
        assertEquals(
            listOf(
                "singleton_id",
                "state",
                "account_uuid",
                "display_name",
                "nickname",
                "last_attempt_at",
                "last_success_at",
                "failure_code",
                "failure_message",
            ),
            BITBUCKET_CONNECTION_SNAPSHOT.fields().map { it.name },
        )
    }

    @Test
    fun `generated schema exposes complete triage and lease tables`() {
        assertEquals(
            listOf("action_item", "build_observation", "configured_repository", "installation_configuration", "notification_attempt", "notification_intent", "pull_request", "readiness_check", "synchronization_checkpoint", "synchronization_failure"),
            listOf(ACTION_ITEM, BUILD_OBSERVATION, CONFIGURED_REPOSITORY, INSTALLATION_CONFIGURATION, NOTIFICATION_ATTEMPT, NOTIFICATION_INTENT, PULL_REQUEST, READINESS_CHECK, SYNCHRONIZATION_CHECKPOINT, SYNCHRONIZATION_FAILURE).map { it.name }.sorted(),
        )
        assertEquals(
            mapOf(
                "installation_configuration" to listOf("singleton_id","workspace_id","api_base_url","workspace_slug","workspace_display_name","workspace_web_url","current_user_stable_id","current_user_display_name","configured_at","retention_days"),
                "configured_repository" to listOf("id","workspace_id","slug","display_name","web_url","removed_at","position"),
                "pull_request" to listOf("id","repository_id","upstream_number","title","author_stable_id","author_display_name","draft","head_commit","web_url","created_at","updated_at","observed_at","active","inactive_at","readiness_kind","readiness_passed","readiness_total","readiness_safe_reason","builds_were_green"),
                "readiness_check" to listOf("pull_request_id","position","name","passed","safe_reason"),
                "build_observation" to listOf("pull_request_id","position","build_key","state","observed_at"),
                "action_item" to listOf("id","pull_request_id","repository_id","source_kind","upstream_source_id","actor_stable_id","actor_display_name","activity_at","observed_at","activity_version","state","acknowledged_version","acknowledged_at","web_url"),
                "synchronization_checkpoint" to listOf("repository_id","activity","last_attempt_at","last_attempt_outcome","last_success_at","snapshot_at","problem_kind","attempted_count","succeeded_count","consecutive_failure_count","backoff_until","pull_request_cursor","activity_cursor"),
                "synchronization_failure" to listOf("repository_id","position","category","retryable","retry_at"),
                "notification_intent" to listOf("id","delivery_key","title","body","open_url","sound","created_at","state","attempt_count","next_attempt_at","lease_owner","lease_acquired_at","lease_expires_at"),
                "notification_attempt" to listOf("id","intent_id","attempt_number","completed_at","result_kind","failure_category","ambiguous"),
            ),
            listOf(INSTALLATION_CONFIGURATION,CONFIGURED_REPOSITORY,PULL_REQUEST,READINESS_CHECK,BUILD_OBSERVATION,ACTION_ITEM,SYNCHRONIZATION_CHECKPOINT,SYNCHRONIZATION_FAILURE,NOTIFICATION_INTENT,NOTIFICATION_ATTEMPT).associate { it.name to it.fields().map { field -> field.name } },
        )
        assertTrue(NOTIFICATION_ATTEMPT.primaryKey.fields.map { it.name } == listOf("id"))
        assertTrue(READINESS_CHECK.primaryKey.fields.map { it.name } == listOf("pull_request_id", "position"))
        assertTrue(BUILD_OBSERVATION.primaryKey.fields.map { it.name } == listOf("pull_request_id", "position"))
        assertTrue(SYNCHRONIZATION_FAILURE.primaryKey.fields.map { it.name } == listOf("repository_id", "position"))
    }
}

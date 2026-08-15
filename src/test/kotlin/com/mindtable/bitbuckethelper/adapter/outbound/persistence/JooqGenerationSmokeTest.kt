package com.mindtable.bitbuckethelper.adapter.outbound.persistence

import com.mindtable.bitbuckethelper.adapter.outbound.persistence.generated.tables.references.BITBUCKET_CONNECTION_SNAPSHOT
import com.mindtable.bitbuckethelper.adapter.outbound.persistence.generated.tables.references.*
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.jooq.Record
import org.jooq.Table

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
        val tables = listOf<Table<out Record>>(ACTION_ITEM,BUILD_OBSERVATION,CONFIGURED_REPOSITORY,INSTALLATION_CONFIGURATION,NOTIFICATION_ATTEMPT,NOTIFICATION_INTENT,PULL_REQUEST,READINESS_CHECK,SYNCHRONIZATION_CHECKPOINT,SYNCHRONIZATION_FAILURE)
        val expectedFields = mapOf(
            "installation_configuration" to "singleton_id:I:Integer:? workspace_id:C:String:! api_base_url:C:String:! workspace_slug:C:String:! workspace_display_name:C:String:! workspace_web_url:C:String:! current_user_stable_id:C:String:! current_user_display_name:C:String:! configured_at:C:String:! retention_days:I:Integer:!",
            "configured_repository" to "id:C:String:? workspace_id:C:String:! slug:C:String:! display_name:C:String:! web_url:C:String:! removed_at:C:String:? position:I:Integer:!",
            "pull_request" to "id:C:String:? repository_id:C:String:! upstream_number:I:Integer:! title:C:String:! author_stable_id:C:String:! author_display_name:C:String:! draft:I:Integer:! head_commit:C:String:! web_url:C:String:! created_at:C:String:! updated_at:C:String:! observed_at:C:String:! active:I:Integer:! inactive_at:C:String:? readiness_kind:C:String:! readiness_passed:I:Integer:? readiness_total:I:Integer:? readiness_safe_reason:C:String:? builds_were_green:I:Integer:!",
            "readiness_check" to "pull_request_id:C:String:! position:I:Integer:! name:C:String:! passed:I:Integer:! safe_reason:C:String:?",
            "build_observation" to "pull_request_id:C:String:! position:I:Integer:! build_key:C:String:! state:C:String:! observed_at:C:String:!",
            "action_item" to "id:C:String:? pull_request_id:C:String:! repository_id:C:String:! source_kind:C:String:! upstream_source_id:C:String:! actor_stable_id:C:String:! actor_display_name:C:String:! activity_at:C:String:! observed_at:C:String:! activity_version:C:String:! state:C:String:! acknowledged_version:C:String:? acknowledged_at:C:String:? web_url:C:String:!",
            "synchronization_checkpoint" to "repository_id:C:String:? activity:C:String:! last_attempt_at:C:String:? last_attempt_outcome:C:String:? last_success_at:C:String:? snapshot_at:C:String:? problem_kind:C:String:! attempted_count:I:Integer:? succeeded_count:I:Integer:? consecutive_failure_count:I:Integer:! backoff_until:C:String:? pull_request_cursor:C:String:? activity_cursor:C:String:?",
            "synchronization_failure" to "repository_id:C:String:! position:I:Integer:! category:C:String:! retryable:I:Integer:! retry_at:C:String:?",
            "notification_intent" to "id:C:String:? delivery_key:C:String:! title:C:String:! body:C:String:! open_url:C:String:? sound:C:String:! created_at:C:String:! state:C:String:! attempt_count:I:Integer:! next_attempt_at:C:String:? lease_owner:C:String:? lease_acquired_at:C:String:? lease_expires_at:C:String:?",
            "notification_attempt" to "id:C:String:? intent_id:C:String:! attempt_number:I:Integer:! completed_at:C:String:! result_kind:C:String:! failure_category:C:String:? ambiguous:I:Integer:?",
        ).mapValues { (_, encoded) -> encoded.split(" ") }
        assertEquals(expectedFields, tables.associate { it.name to it.fields().map(::fieldMetadata) })

        val expectedPrimaryKeys = mapOf("installation_configuration" to "singleton_id","configured_repository" to "id","pull_request" to "id","readiness_check" to "pull_request_id,position","build_observation" to "pull_request_id,position","action_item" to "id","synchronization_checkpoint" to "repository_id","synchronization_failure" to "repository_id,position","notification_intent" to "id","notification_attempt" to "id")
        assertEquals(expectedPrimaryKeys, tables.associate { it.name to requireNotNull(it.primaryKey).fields.joinToString(",") { field -> field.name } })
        assertEquals(mapOf("installation_configuration" to setOf("workspace_id"),"notification_attempt" to setOf("intent_id,attempt_number")), tables.mapNotNull { table -> table.uniqueKeys.map { key -> key.fields.joinToString(",") { it.name } }.toSet().takeIf { it.isNotEmpty() }?.let { table.name to it } }.toMap())
        assertEquals(mapOf("configured_repository" to setOf("workspace_id->installation_configuration.workspace_id:CASCADE:NO_ACTION"),"readiness_check" to setOf("pull_request_id->pull_request.id:CASCADE:NO_ACTION"),"build_observation" to setOf("pull_request_id->pull_request.id:CASCADE:NO_ACTION"),"synchronization_failure" to setOf("repository_id->synchronization_checkpoint.repository_id:CASCADE:NO_ACTION"),"notification_attempt" to setOf("intent_id->notification_intent.id:CASCADE:NO_ACTION")), tables.mapNotNull { table -> table.references.map { reference -> reference.fields.joinToString(",") { it.name } + "->" + reference.key.table.name + "." + reference.key.fields.joinToString(",") { it.name } + ":" + reference.deleteRule + ":" + reference.updateRule }.toSet().takeIf { it.isNotEmpty() }?.let { table.name to it } }.toMap())
        assertEquals(expectedIndexes, tables.mapNotNull { table -> table.indexes.map { index -> index.name + ":" + index.fields.joinToString(",") { it.name } + ":" + index.unique }.toSet().takeIf { it.isNotEmpty() }?.let { table.name to it } }.toMap())
    }

    private fun fieldMetadata(field: org.jooq.Field<*>): String = "${field.name}:${if (field.dataType.typeName.equals("integer",true)) "I" else "C"}:${field.dataType.type.simpleName}:${if (field.dataType.nullable()) "?" else "!"}"

    private val expectedIndexes = mapOf(
        "configured_repository" to setOf("configured_repository_workspace_position_idx:workspace_id,position,id:false"),
        "pull_request" to setOf("pull_request_inactive_at_id_idx:inactive_at,id:false","pull_request_repository_active_id_idx:repository_id,active,id:false"),
        "action_item" to setOf("action_item_actionable_idx:state,repository_id,pull_request_id,activity_at,id:false","action_item_pull_request_id_idx:pull_request_id,id:false"),
        "notification_intent" to setOf("notification_intent_claim_idx:state,lease_expires_at,id:false","notification_intent_delivery_key_idx:delivery_key:false","notification_intent_due_claim_idx:state,next_attempt_at,lease_expires_at,created_at,id:false","notification_intent_due_idx:state,next_attempt_at,created_at,id:false"),
        "notification_attempt" to setOf("notification_attempt_intent_number_idx:intent_id,attempt_number,id:false"),
    )
}

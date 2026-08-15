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
            "installation_configuration" to "singleton_id:INTEGER:Integer:? workspace_id:CLOB:String:! api_base_url:CLOB:String:! workspace_slug:CLOB:String:! workspace_display_name:CLOB:String:! workspace_web_url:CLOB:String:! current_user_stable_id:CLOB:String:! current_user_display_name:CLOB:String:! configured_at:CLOB:String:! retention_days:INTEGER:Integer:!",
            "configured_repository" to "id:CLOB:String:? workspace_id:CLOB:String:! slug:CLOB:String:! display_name:CLOB:String:! web_url:CLOB:String:! removed_at:CLOB:String:? position:INTEGER:Integer:!",
            "pull_request" to "id:CLOB:String:? repository_id:CLOB:String:! upstream_number:INTEGER:Integer:! title:CLOB:String:! author_stable_id:CLOB:String:! author_display_name:CLOB:String:! draft:INTEGER:Integer:! head_commit:CLOB:String:! web_url:CLOB:String:! created_at:CLOB:String:! updated_at:CLOB:String:! observed_at:CLOB:String:! active:INTEGER:Integer:! inactive_at:CLOB:String:? readiness_kind:CLOB:String:! readiness_passed:INTEGER:Integer:? readiness_total:INTEGER:Integer:? readiness_safe_reason:CLOB:String:? builds_were_green:INTEGER:Integer:!",
            "readiness_check" to "pull_request_id:CLOB:String:! position:INTEGER:Integer:! name:CLOB:String:! passed:INTEGER:Integer:! safe_reason:CLOB:String:?",
            "build_observation" to "pull_request_id:CLOB:String:! position:INTEGER:Integer:! build_key:CLOB:String:! state:CLOB:String:! observed_at:CLOB:String:!",
            "action_item" to "id:CLOB:String:? pull_request_id:CLOB:String:! repository_id:CLOB:String:! source_kind:CLOB:String:! upstream_source_id:CLOB:String:! actor_stable_id:CLOB:String:! actor_display_name:CLOB:String:! activity_at:CLOB:String:! observed_at:CLOB:String:! activity_version:CLOB:String:! state:CLOB:String:! acknowledged_version:CLOB:String:? acknowledged_at:CLOB:String:? web_url:CLOB:String:!",
            "synchronization_checkpoint" to "repository_id:CLOB:String:? activity:CLOB:String:! last_attempt_at:CLOB:String:? last_attempt_outcome:CLOB:String:? last_success_at:CLOB:String:? snapshot_at:CLOB:String:? problem_kind:CLOB:String:! attempted_count:INTEGER:Integer:? succeeded_count:INTEGER:Integer:? consecutive_failure_count:INTEGER:Integer:! backoff_until:CLOB:String:? pull_request_cursor:CLOB:String:? activity_cursor:CLOB:String:?",
            "synchronization_failure" to "repository_id:CLOB:String:! position:INTEGER:Integer:! category:CLOB:String:! retryable:INTEGER:Integer:! retry_at:CLOB:String:?",
            "notification_intent" to "id:CLOB:String:? delivery_key:CLOB:String:! title:CLOB:String:! body:CLOB:String:! open_url:CLOB:String:? sound:CLOB:String:! created_at:CLOB:String:! state:CLOB:String:! attempt_count:INTEGER:Integer:! next_attempt_at:CLOB:String:? lease_owner:CLOB:String:? lease_acquired_at:CLOB:String:? lease_expires_at:CLOB:String:?",
            "notification_attempt" to "id:CLOB:String:? intent_id:CLOB:String:! attempt_number:INTEGER:Integer:! completed_at:CLOB:String:! result_kind:CLOB:String:! failure_category:CLOB:String:? ambiguous:INTEGER:Integer:?",
        ).mapValues { (_, encoded) -> encoded.split(" ") }
        assertEquals(expectedFields, tables.associate { it.name to it.fields().map(::fieldMetadata) })

        val expectedPrimaryKeys = mapOf("installation_configuration" to "singleton_id","configured_repository" to "id","pull_request" to "id","readiness_check" to "pull_request_id,position","build_observation" to "pull_request_id,position","action_item" to "id","synchronization_checkpoint" to "repository_id","synchronization_failure" to "repository_id,position","notification_intent" to "id","notification_attempt" to "id")
        assertEquals(expectedPrimaryKeys, tables.associate { it.name to requireNotNull(it.primaryKey).fields.joinToString(",") { field -> field.name } })
        assertEquals(mapOf("installation_configuration" to setOf("workspace_id"),"notification_attempt" to setOf("intent_id,attempt_number")), tables.mapNotNull { table -> table.uniqueKeys.map { key -> key.fields.joinToString(",") { it.name } }.toSet().takeIf { it.isNotEmpty() }?.let { table.name to it } }.toMap())
        assertEquals(mapOf("configured_repository" to setOf("workspace_id->installation_configuration.workspace_id:CASCADE:NO_ACTION"),"readiness_check" to setOf("pull_request_id->pull_request.id:CASCADE:NO_ACTION"),"build_observation" to setOf("pull_request_id->pull_request.id:CASCADE:NO_ACTION"),"synchronization_failure" to setOf("repository_id->synchronization_checkpoint.repository_id:CASCADE:NO_ACTION"),"notification_attempt" to setOf("intent_id->notification_intent.id:CASCADE:NO_ACTION")), tables.mapNotNull { table -> table.references.map { reference -> reference.fields.joinToString(",") { it.name } + "->" + reference.key.table.name + "." + reference.key.fields.joinToString(",") { it.name } + ":" + reference.deleteRule + ":" + reference.updateRule }.toSet().takeIf { it.isNotEmpty() }?.let { table.name to it } }.toMap())
        assertEquals(expectedIndexes, tables.mapNotNull { table -> table.indexes.map { index -> index.name + ":" + index.fields.joinToString(",") { it.name } + ":" + index.unique }.toSet().takeIf { it.isNotEmpty() }?.let { table.name to it } }.toMap())
    }

    private fun fieldMetadata(field: org.jooq.Field<*>): String = "${field.name}:${field.dataType.typeName.uppercase()}:${field.dataType.type.simpleName}:${if (field.dataType.nullable()) "?" else "!"}"

    private val expectedIndexes = mapOf(
        "configured_repository" to setOf("configured_repository_workspace_position_idx:workspace_id,position,id:false"),
        "pull_request" to setOf("pull_request_inactive_at_id_idx:inactive_at,id:false","pull_request_repository_active_id_idx:repository_id,active,id:false"),
        "action_item" to setOf("action_item_actionable_idx:state,repository_id,pull_request_id,activity_at,id:false","action_item_pull_request_id_idx:pull_request_id,id:false"),
        "notification_intent" to setOf("notification_intent_claim_idx:state,lease_expires_at,id:false","notification_intent_delivery_key_idx:delivery_key:false","notification_intent_due_claim_idx:state,next_attempt_at,lease_expires_at,created_at,id:false","notification_intent_due_idx:state,next_attempt_at,created_at,id:false"),
        "notification_attempt" to setOf("notification_attempt_intent_number_idx:intent_id,attempt_number,id:false"),
    )
}

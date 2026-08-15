package com.mindtable.bitbuckethelper.adapter.outbound.persistence

import com.mindtable.bitbuckethelper.adapter.outbound.persistence.generated.tables.references.BITBUCKET_CONNECTION_SNAPSHOT
import com.mindtable.bitbuckethelper.adapter.outbound.persistence.generated.tables.references.*
import org.junit.jupiter.api.Assertions.assertEquals
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
            listOf("lease_owner", "lease_acquired_at", "lease_expires_at"),
            NOTIFICATION_INTENT.fields().map { it.name }.filter { it.startsWith("lease_") },
        )
    }
}

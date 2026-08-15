package com.mindtable.bitbuckethelper.adapter.outbound.persistence

import com.mindtable.bitbuckethelper.adapter.outbound.persistence.generated.tables.references.BITBUCKET_CONNECTION_SNAPSHOT
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
}

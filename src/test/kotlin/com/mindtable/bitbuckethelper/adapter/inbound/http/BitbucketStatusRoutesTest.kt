package com.mindtable.bitbuckethelper.adapter.inbound.http

import com.mindtable.bitbuckethelper.application.model.BitbucketAccount
import com.mindtable.bitbuckethelper.application.model.BitbucketConnectionSnapshot
import com.mindtable.bitbuckethelper.application.model.ConnectionFailure
import com.mindtable.bitbuckethelper.application.model.ConnectionFailureCode
import com.mindtable.bitbuckethelper.application.model.ConnectionState
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import java.time.Instant
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test

class BitbucketStatusRoutesTest {
    private val json = Json

    @Test
    fun `pending is a successful versioned query`() = testApplication {
        application { installBitbucketStatusApi { null } }

        val response = client.get("/api/v1/bitbucket/status")

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals(
            buildJsonObject {
                put("schemaVersion", 1)
                put("state", "pending")
                put("lastAttemptAt", JsonNull)
                put("lastSuccessAt", JsonNull)
                put("account", JsonNull)
                put("failure", JsonNull)
            },
            json.parseToJsonElement(response.bodyAsText()),
        )
    }

    @Test
    fun `healthy snapshot is returned as a successful versioned query`() = testApplication {
        val snapshot = BitbucketConnectionSnapshot(
            state = ConnectionState.HEALTHY,
            account = BitbucketAccount(
                uuid = "{healthy-account-uuid}",
                displayName = "Ada Lovelace",
                nickname = "ada",
            ),
            lastAttemptAt = Instant.parse("2026-08-15T10:15:30Z"),
            lastSuccessAt = Instant.parse("2026-08-15T10:15:30Z"),
            failure = null,
        )
        application { installBitbucketStatusApi { snapshot } }

        val response = client.get("/api/v1/bitbucket/status")

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals(
            json.parseToJsonElement(
                """
                {
                  "schemaVersion": 1,
                  "state": "healthy",
                  "lastAttemptAt": "2026-08-15T10:15:30Z",
                  "lastSuccessAt": "2026-08-15T10:15:30Z",
                  "account": {
                    "uuid": "{healthy-account-uuid}",
                    "displayName": "Ada Lovelace",
                    "nickname": "ada"
                  },
                  "failure": null
                }
                """.trimIndent(),
            ),
            json.parseToJsonElement(response.bodyAsText()),
        )
    }

    @Test
    fun `failed snapshot preserves the last successful account`() = testApplication {
        val snapshot = BitbucketConnectionSnapshot(
            state = ConnectionState.FAILED,
            account = BitbucketAccount(
                uuid = "{last-successful-account-uuid}",
                displayName = "Grace Hopper",
                nickname = null,
            ),
            lastAttemptAt = Instant.parse("2026-08-15T10:20:00Z"),
            lastSuccessAt = Instant.parse("2026-08-15T10:00:00Z"),
            failure = ConnectionFailure(
                code = ConnectionFailureCode.RATE_LIMITED,
                message = "Bitbucket rate limit exceeded",
            ),
        )
        application { installBitbucketStatusApi { snapshot } }

        val response = client.get("/api/v1/bitbucket/status")

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals(
            json.parseToJsonElement(
                """
                {
                  "schemaVersion": 1,
                  "state": "failed",
                  "lastAttemptAt": "2026-08-15T10:20:00Z",
                  "lastSuccessAt": "2026-08-15T10:00:00Z",
                  "account": {
                    "uuid": "{last-successful-account-uuid}",
                    "displayName": "Grace Hopper",
                    "nickname": null
                  },
                  "failure": {
                    "code": "rate_limited",
                    "message": "Bitbucket rate limit exceeded"
                  }
                }
                """.trimIndent(),
            ),
            json.parseToJsonElement(response.bodyAsText()),
        )
    }

    @Test
    fun `unexpected query failure is a sanitized 500`() = testApplication {
        application {
            installBitbucketStatusApi {
                error("sentinel-api-token and database internals")
            }
        }

        val response = client.get("/api/v1/bitbucket/status")
        val responseBody = response.bodyAsText()

        assertEquals(HttpStatusCode.InternalServerError, response.status)
        assertEquals(
            """{"schemaVersion":1,"error":"internal_server_error"}""",
            responseBody,
        )
        assertFalse(responseBody.contains("sentinel-api-token"))
        assertFalse(responseBody.contains("database internals"))
    }
}

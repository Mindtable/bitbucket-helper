package com.mindtable.bitbuckethelper.adapter.inbound.http

import com.mindtable.bitbuckethelper.application.model.ActivePollingAdvice
import com.mindtable.bitbuckethelper.application.model.GetRefreshRunResult
import com.mindtable.bitbuckethelper.application.model.PartialFailureMetadata
import com.mindtable.bitbuckethelper.application.model.RefreshRegistrationDisposition
import com.mindtable.bitbuckethelper.application.model.RefreshRunRepositoryEntry
import com.mindtable.bitbuckethelper.application.model.RefreshRunSnapshot
import com.mindtable.bitbuckethelper.application.model.RefreshTarget
import com.mindtable.bitbuckethelper.application.model.StartRefreshRunCommand
import com.mindtable.bitbuckethelper.application.model.StartRefreshRunResult
import com.mindtable.bitbuckethelper.application.model.SynchronizationFailure
import com.mindtable.bitbuckethelper.application.model.SynchronizationFailureCategory
import com.mindtable.bitbuckethelper.domain.shared.RefreshRunId
import com.mindtable.bitbuckethelper.domain.shared.RepositoryId
import com.mindtable.bitbuckethelper.generated.api.v1.model.GetRefreshRunResponse
import com.mindtable.bitbuckethelper.generated.api.v1.model.RefreshRunInProgressResult
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import java.time.Instant
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class RefreshRunRoutesTest {
    private val strictJson = Json {
        encodeDefaults = true
        explicitNulls = true
        ignoreUnknownKeys = false
        classDiscriminator = "type"
    }

    @Test
    fun `refresh registration maps every result nested discriminator and preserves order`() = testApplication {
        val registered = StartRefreshRunResult.RefreshRunRegistered(
            refreshRun = refreshRun(),
            dispositions = listOf(
                RefreshRegistrationDisposition.RepositoryNotConfigured(REPOSITORY_D),
                RefreshRegistrationDisposition.DeferredByBackoff(
                    REPOSITORY_C,
                    Instant.parse("2026-08-17T10:15:00Z"),
                ),
                RefreshRegistrationDisposition.JoinedExisting(REPOSITORY_B),
                RefreshRegistrationDisposition.Started(REPOSITORY_A),
            ),
        )
        val fake = FakeRefreshRunDependencies(
            startResults = ArrayDeque(
                listOf(
                    StartRefreshRunResult.WorkspaceNotConfigured,
                    StartRefreshRunResult.NoRepositoriesConfigured,
                    registered,
                    registered,
                ),
            ),
        )
        application { installRefreshRunApi(fake) }

        client.postRefresh("""{"apiVersion":"1","target":{"type":"allConfiguredRepositories"}}""")
            .assertEnvelope("workspaceNotConfigured")
        client.postRefresh("""{"apiVersion":"1","target":{"type":"allConfiguredRepositories"}}""")
            .assertEnvelope("noRepositoriesConfigured")
        val registeredResult = client.postRefresh(
            """{"apiVersion":"1","target":{"type":"allConfiguredRepositories"}}""",
        ).assertEnvelope("refreshRunRegistered")

        val run = registeredResult.objectValue("refreshRun")
        assertEquals("rr_run-01", run.string("refreshRunId"))
        assertEquals(
            listOf("queued", "running", "succeeded", "partialFailure", "failed", "deferredByBackoff"),
            run.array("repositories").map { it.jsonObject.string("type") },
        )
        assertEquals(
            listOf("repo_d", "repo_c", "repo_b", "repo_a"),
            registeredResult.array("dispositions").map { it.jsonObject.string("repositoryId") },
        )
        assertEquals(
            listOf("repositoryNotConfigured", "deferredByBackoff", "joinedExisting", "started"),
            registeredResult.array("dispositions").map { it.jsonObject.string("type") },
        )
        assertEquals(
            JsonNull,
            run.array("repositories")[4].jsonObject.objectValue("failure")["retryAt"],
        )

        client.postRefresh(
            """{"apiVersion":"1","target":{"type":"repositories","repositoryIds":["repo_b","repo_a"]}}""",
        ).assertEnvelope("refreshRunRegistered")

        assertEquals(4, fake.startCommands.size)
        assertTrue(fake.startCommands.take(3).all { it == StartRefreshRunCommand(RefreshTarget.AllConfiguredRepositories) })
        assertEquals(
            listOf(REPOSITORY_B, REPOSITORY_A),
            (fake.startCommands[3].target as RefreshTarget.Repositories).repositoryIds,
        )
    }

    @Test
    fun `refresh inspection maps every result and active polling strict round trips`() = testApplication {
        val fake = FakeRefreshRunDependencies(
            getResults = ArrayDeque(
                listOf(
                    GetRefreshRunResult.RefreshRunInProgress(refreshRun(), ActivePollingAdvice(1750)),
                    GetRefreshRunResult.RefreshRunCompleted(refreshRun()),
                    GetRefreshRunResult.RefreshRunUnavailable(RefreshRunId("rr_unknown-02")),
                ),
            ),
        )
        application { installRefreshRunApi(fake) }

        val inProgressResponse = client.get("/api/v1/refresh-runs/rr_run-01")
        val inProgressText = inProgressResponse.bodyAsText()
        val inProgress = inProgressText.result("refreshRunInProgress")
        assertEquals("active", inProgress.objectValue("polling").string("type"))
        assertEquals(1750L, inProgress.objectValue("polling").long("afterMilliseconds"))

        val decoded = strictJson.decodeFromString<GetRefreshRunResponse>(inProgressText)
        val decodedResult = decoded.result as RefreshRunInProgressResult
        assertEquals(com.mindtable.bitbuckethelper.generated.api.v1.model.ActivePollingAdvice.Type.active, decodedResult.polling.type)
        assertEquals(1750L, decodedResult.polling.afterMilliseconds)
        assertEquals(
            strictJson.parseToJsonElement(inProgressText),
            strictJson.parseToJsonElement(strictJson.encodeToString(decoded)),
        )

        client.get("/api/v1/refresh-runs/rr_run-01").assertEnvelope("refreshRunCompleted")
        val unavailable = client.get("/api/v1/refresh-runs/rr_unknown-02")
            .assertEnvelope("refreshRunUnavailable")
        assertEquals("rr_unknown-02", unavailable.string("refreshRunId"))
        assertEquals(
            listOf("rr_run-01", "rr_run-01", "rr_unknown-02"),
            fake.getIds.map { it.value },
        )
    }

    @Test
    fun `refresh request errors are typed safe and invoke no use case`() = testApplication {
        val fake = FakeRefreshRunDependencies()
        application { installRefreshRunApi(fake) }

        val empty = client.postRefresh(
            """{"apiVersion":"1","target":{"type":"repositories","repositoryIds":[]}}""",
        )
        val malformedRepository = client.postRefresh(
            """{"apiVersion":"1","target":{"type":"repositories","repositoryIds":["wrong"]}}""",
        )
        val malformedRun = client.get("/api/v1/refresh-runs/wrong")
        val malformedJson = client.postRefresh("""{"apiVersion":"1","target":sentinel""")
        val unsupportedVersion = client.postRefresh(
            """{"apiVersion":"2","target":{"type":"allConfiguredRepositories"}}""",
        )

        empty.assertRequestError("target.repositoryIds")
        malformedRepository.assertRequestError("target.repositoryIds")
        malformedRun.assertRequestError("refreshRunId")
        malformedJson.assertRequestError()
        unsupportedVersion.assertRequestError()
        assertTrue(fake.startCommands.isEmpty())
        assertTrue(fake.getIds.isEmpty())
    }

    private fun io.ktor.server.application.Application.installRefreshRunApi(fake: FakeRefreshRunDependencies) {
        installApiV1(TransportKind.UNIX) {
            installRefreshRunRoutes(
                RefreshRunApiV1Dependencies(
                    startRefreshRun = fake.startRefreshRun,
                    getRefreshRun = fake.getRefreshRun,
                ),
            )
        }
    }

    private suspend fun io.ktor.client.HttpClient.postRefresh(body: String): HttpResponse =
        post("/api/v1/refresh-runs") {
            contentType(ContentType.Application.Json)
            setBody(body)
        }

    private suspend fun HttpResponse.assertEnvelope(type: String): JsonObject {
        assertEquals(HttpStatusCode.OK, status)
        assertEquals("no-store", headers[HttpHeaders.CacheControl])
        assertTrue(contentType()?.match(ContentType.Application.Json) == true)
        return bodyAsText().result(type)
    }

    private suspend fun HttpResponse.assertRequestError(field: String? = null) {
        assertEquals(HttpStatusCode.BadRequest, status)
        val root = bodyAsText().json()
        assertEquals("INVALID_REQUEST", root.objectValue("error").string("code"))
        if (field != null) {
            assertTrue(root.objectValue("error").getValue("violations").toString().contains("\"field\":\"$field\""))
        }
    }

    private fun String.result(type: String): JsonObject {
        val root = json()
        assertEquals("1", root.string("apiVersion"))
        assertTrue(root.string("requestId").matches(Regex("^req_[A-Za-z0-9_-]+$")))
        val result = root.objectValue("result")
        assertEquals(type, result.string("type"))
        return result
    }

    private fun String.json(): JsonObject = Json.parseToJsonElement(this).jsonObject
    private fun JsonObject.objectValue(name: String): JsonObject = getValue(name).jsonObject
    private fun JsonObject.array(name: String) = getValue(name).jsonArray
    private fun JsonObject.string(name: String): String = getValue(name).jsonPrimitive.content
    private fun JsonObject.long(name: String): Long = getValue(name).jsonPrimitive.content.toLong()

    private class FakeRefreshRunDependencies(
        private val startResults: ArrayDeque<StartRefreshRunResult> = ArrayDeque(),
        private val getResults: ArrayDeque<GetRefreshRunResult> = ArrayDeque(),
    ) {
        val startCommands = mutableListOf<StartRefreshRunCommand>()
        val getIds = mutableListOf<RefreshRunId>()
        val startRefreshRun = com.mindtable.bitbuckethelper.application.port.inbound.StartRefreshRun {
            startCommands += it
            startResults.removeFirst()
        }
        val getRefreshRun = com.mindtable.bitbuckethelper.application.port.inbound.GetRefreshRun {
            getIds += it
            getResults.removeFirst()
        }
    }

    companion object {
        private val REPOSITORY_A = RepositoryId("repo_a")
        private val REPOSITORY_B = RepositoryId("repo_b")
        private val REPOSITORY_C = RepositoryId("repo_c")
        private val REPOSITORY_D = RepositoryId("repo_d")

        private fun refreshRun() = RefreshRunSnapshot(
            id = RefreshRunId("rr_run-01"),
            createdAt = Instant.parse("2026-08-17T10:00:00Z"),
            expiresAt = Instant.parse("2026-08-17T11:00:00Z"),
            repositories = listOf(
                RefreshRunRepositoryEntry.Queued(REPOSITORY_A),
                RefreshRunRepositoryEntry.Running(REPOSITORY_B),
                RefreshRunRepositoryEntry.Succeeded(REPOSITORY_C, Instant.parse("2026-08-17T10:02:00Z")),
                RefreshRunRepositoryEntry.PartiallySucceeded(
                    REPOSITORY_D,
                    Instant.parse("2026-08-17T10:03:00Z"),
                    PartialFailureMetadata(
                        attemptedCount = 2,
                        succeededCount = 1,
                        failures = listOf(
                            SynchronizationFailure(
                                SynchronizationFailureCategory.RATE_LIMITED,
                                retryable = true,
                                retryAt = Instant.parse("2026-08-17T10:15:00Z"),
                            ),
                        ),
                    ),
                ),
                RefreshRunRepositoryEntry.Failed(
                    REPOSITORY_A,
                    Instant.parse("2026-08-17T10:04:00Z"),
                    SynchronizationFailure(
                        SynchronizationFailureCategory.AUTHENTICATION,
                        retryable = false,
                        retryAt = null,
                    ),
                ),
                RefreshRunRepositoryEntry.DeferredByBackoff(
                    REPOSITORY_B,
                    Instant.parse("2026-08-17T10:20:00Z"),
                ),
            ),
        )
    }
}

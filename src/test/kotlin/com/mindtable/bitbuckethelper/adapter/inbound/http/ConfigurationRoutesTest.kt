package com.mindtable.bitbuckethelper.adapter.inbound.http

import com.mindtable.bitbuckethelper.application.model.AddRepositoryCommand
import com.mindtable.bitbuckethelper.application.model.AddRepositoryResult
import com.mindtable.bitbuckethelper.application.model.ConfigureWorkspaceCommand
import com.mindtable.bitbuckethelper.application.model.ConfigureWorkspaceResult
import com.mindtable.bitbuckethelper.application.model.ConfiguredRepositoryProjection
import com.mindtable.bitbuckethelper.application.model.GatewayFailure
import com.mindtable.bitbuckethelper.application.model.GatewayFailureCategory
import com.mindtable.bitbuckethelper.application.model.GetWorkspaceConfigurationResult
import com.mindtable.bitbuckethelper.application.model.RemoveRepositoryCommand
import com.mindtable.bitbuckethelper.application.model.RemoveRepositoryResult
import com.mindtable.bitbuckethelper.application.model.WorkspaceConfigurationProjection
import com.mindtable.bitbuckethelper.domain.shared.RepositoryId
import com.mindtable.bitbuckethelper.domain.shared.WorkspaceId
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import java.net.URI
import java.time.Instant
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ConfigurationRoutesTest {
    @Test
    fun `workspace configuration read and configure map every typed business result`() = testApplication {
        val gatewayFailures = gatewayFailures()
        val fake = FakeConfigurationDependencies(
            getResults = ArrayDeque(
                listOf(
                    GetWorkspaceConfigurationResult.Configured(configuration()),
                    GetWorkspaceConfigurationResult.WorkspaceNotConfigured,
                ),
            ),
            configureResults = ArrayDeque<ConfigureWorkspaceResult>().apply {
                add(ConfigureWorkspaceResult.WorkspaceConfigured(configuration()))
                add(ConfigureWorkspaceResult.WorkspaceAlreadyConfigured(configuration()))
                add(ConfigureWorkspaceResult.WorkspaceIdentityMismatch(configuration()))
                add(ConfigureWorkspaceResult.WorkspaceNotFound)
                gatewayFailures.forEach { add(ConfigureWorkspaceResult.WorkspaceResolutionUnavailable(it)) }
            },
        )
        application { installConfigurationApi(fake) }

        val configured = client.get(WORKSPACE_PATH).assertEnvelope("workspaceConfigured")
            .objectValue("configuration")
        assertEquals(listOf("repo_zeta", "repo_alpha"), configured.array("repositories").map {
            it.jsonObject.string("repositoryId")
        })
        assertEquals("bitbucket-helper workspace configure", client.get(WORKSPACE_PATH)
            .assertEnvelope("workspaceNotConfigured").string("setupCommand"))

        val expectedTypes = listOf(
            "workspaceConfigured",
            "workspaceAlreadyConfigured",
            "workspaceIdentityMismatch",
            "workspaceNotFound",
        )
        expectedTypes.forEach { expectedType ->
            val result = client.putWorkspace(CONFIGURE_BODY).assertEnvelope(expectedType)
            if (expectedType == "workspaceConfigured" || expectedType == "workspaceAlreadyConfigured") {
                assertEquals("ws_acme", result.objectValue("configuration").string("workspaceId"))
            }
            if (expectedType == "workspaceIdentityMismatch") {
                assertEquals("ws_acme", result.objectValue("current").string("workspaceId"))
            }
        }

        val expectedCategories = listOf(
            "authentication",
            "authorization",
            "rateLimited",
            "timeout",
            "network",
            "upstream",
            "malformedResponse",
            "unsafePagination",
        )
        expectedCategories.forEachIndexed { index, expectedCategory ->
            val failure = client.putWorkspace(CONFIGURE_BODY)
                .assertEnvelope("workspaceResolutionUnavailable")
                .objectValue("failure")
            assertEquals(expectedCategory, failure.string("category"))
            assertEquals(
                if (index == 0) "2026-08-17T10:30:00Z" else null,
                failure["retryAt"]?.jsonPrimitive?.contentOrNull,
            )
            if (index != 0) assertEquals(JsonNull, failure["retryAt"])
        }

        assertEquals(2, fake.getCalls)
        assertEquals(
            List(12) { ConfigureWorkspaceCommand(URI("https://api.bitbucket.org/2.0"), "acme-engineering") },
            fake.configureCommands,
        )
    }

    @Test
    fun `repository add maps every typed business result and gateway category`() = testApplication {
        val fake = FakeConfigurationDependencies(
            addResults = ArrayDeque<AddRepositoryResult>().apply {
                add(AddRepositoryResult.RepositoryAdded(repository("repo_added", "added")))
                add(AddRepositoryResult.RepositoryAlreadyConfigured(repository("repo_existing", "existing")))
                add(AddRepositoryResult.RepositoryNotFound)
                add(AddRepositoryResult.WorkspaceNotConfigured)
                gatewayFailures().forEach { add(AddRepositoryResult.RepositoryResolutionUnavailable(it)) }
            },
        )
        application { installConfigurationApi(fake) }

        assertEquals(
            "repo_added",
            client.postRepository(ADD_BODY).assertEnvelope("repositoryAdded")
                .objectValue("repository").string("repositoryId"),
        )
        assertEquals(
            "repo_existing",
            client.postRepository(ADD_BODY).assertEnvelope("repositoryAlreadyConfigured")
                .objectValue("repository").string("repositoryId"),
        )
        client.postRepository(ADD_BODY).assertEnvelope("repositoryNotFound")
        client.postRepository(ADD_BODY).assertEnvelope("workspaceNotConfigured")

        val expectedCategories = listOf(
            "authentication",
            "authorization",
            "rateLimited",
            "timeout",
            "network",
            "upstream",
            "malformedResponse",
            "unsafePagination",
        )
        expectedCategories.forEach { category ->
            assertEquals(
                category,
                client.postRepository(ADD_BODY).assertEnvelope("repositoryResolutionUnavailable")
                    .objectValue("failure").string("category"),
            )
        }

        assertEquals(List(12) { AddRepositoryCommand("release-tools") }, fake.addCommands)
    }

    @Test
    fun `repository remove maps configured and unknown repositories as typed 200`() = testApplication {
        val fake = FakeConfigurationDependencies(
            removeResults = ArrayDeque(
                listOf(
                    RemoveRepositoryResult.RepositoryRemoved(RepositoryId("repo_target-01")),
                    RemoveRepositoryResult.RepositoryNotConfigured(RepositoryId("repo_unknown-02")),
                ),
            ),
        )
        application { installConfigurationApi(fake) }

        assertEquals(
            "repo_target-01",
            client.delete("$REPOSITORIES_PATH/repo_target-01")
                .assertEnvelope("repositoryRemoved").string("repositoryId"),
        )
        assertEquals(
            "repo_unknown-02",
            client.delete("$REPOSITORIES_PATH/repo_unknown-02")
                .assertEnvelope("repositoryNotConfigured").string("repositoryId"),
        )
        assertEquals(
            listOf(RemoveRepositoryCommand(RepositoryId("repo_target-01")), RemoveRepositoryCommand(RepositoryId("repo_unknown-02"))),
            fake.removeCommands,
        )
    }

    @Test
    fun `configuration request errors reject invalid URLs empty targets malformed IDs and bodies`() =
        testApplication {
            val fake = FakeConfigurationDependencies()
            application { installConfigurationApi(fake) }

            val invalidUrls = listOf(
                "relative/path",
                "ftp://api.bitbucket.org/2.0",
                "http://api.bitbucket.org/2.0",
                "https://user:password@api.bitbucket.org/2.0",
                "https://api.bitbucket.org/",
                "https://api.bitbucket.org/2.0/extra",
                "https://api.bitbucket.org/x/../2.0",
                "https://api.bitbucket.org/2.0?token=value",
                "https://api.bitbucket.org/2.0#fragment",
            )
            invalidUrls.forEach { url ->
                client.putWorkspace(
                    """{"apiVersion":"1","bitbucketApiBaseUrl":"$url","workspaceSlug":"acme"}""",
                ).assertRequestError("bitbucketApiBaseUrl")
            }
            client.putWorkspace(
                """{"apiVersion":"1","bitbucketApiBaseUrl":"https://api.bitbucket.org/2.0","workspaceSlug":""}""",
            ).assertRequestError("workspaceSlug")
            client.postRepository("""{"apiVersion":"1","repositorySlug":""}""")
                .assertRequestError("repositorySlug")
            client.delete("$REPOSITORIES_PATH/wrong").assertRequestError("repositoryId")
            client.postRepository("""{"apiVersion":"1","repositorySlug":sentinel""")
                .assertRequestError()
            client.postRepository("""{"apiVersion":"2","repositorySlug":"release-tools"}""")
                .assertRequestError()

            val noMediaType = client.post(REPOSITORIES_PATH) {
                setBody(ADD_BODY)
            }
            assertEquals(HttpStatusCode.UnsupportedMediaType, noMediaType.status)
            assertTrue(fake.configureCommands.isEmpty())
            assertTrue(fake.addCommands.isEmpty())
            assertTrue(fake.removeCommands.isEmpty())
        }

    @Test
    fun `syntactically malformed Bitbucket base URL is a safe request error`() = testApplication {
        val fake = FakeConfigurationDependencies()
        application { installConfigurationApi(fake) }

        val response = client.putWorkspace(
            """{"apiVersion":"1","bitbucketApiBaseUrl":"https://api.bitbucket.org/%sentinel-malformed-url","workspaceSlug":"acme"}""",
        )

        response.assertRequestError("bitbucketApiBaseUrl")
        assertFalse(response.bodyAsText().contains("sentinel-malformed-url"))
        assertTrue(fake.configureCommands.isEmpty())
    }

    private fun io.ktor.server.application.Application.installConfigurationApi(fake: FakeConfigurationDependencies) {
        installApiV1(TransportKind.UNIX) {
            installConfigurationRoutes(
                ConfigurationApiV1Dependencies(
                    getWorkspaceConfiguration = fake.getWorkspaceConfiguration,
                    configureWorkspace = fake.configureWorkspace,
                    addRepository = fake.addRepository,
                    removeRepository = fake.removeRepository,
                ),
            )
        }
    }

    private suspend fun io.ktor.client.HttpClient.putWorkspace(body: String): HttpResponse = put(WORKSPACE_PATH) {
        contentType(ContentType.Application.Json)
        setBody(body)
    }

    private suspend fun io.ktor.client.HttpClient.postRepository(body: String): HttpResponse = post(REPOSITORIES_PATH) {
        contentType(ContentType.Application.Json)
        setBody(body)
    }

    private suspend fun HttpResponse.assertEnvelope(type: String): JsonObject {
        assertEquals(HttpStatusCode.OK, status)
        assertEquals("no-store", headers[HttpHeaders.CacheControl])
        assertTrue(contentType()?.match(ContentType.Application.Json) == true)
        val root = bodyAsText().json()
        assertEquals("1", root.string("apiVersion"))
        assertTrue(root.string("requestId").matches(Regex("^req_[A-Za-z0-9_-]+$")))
        val result = root.objectValue("result")
        assertEquals(type, result.string("type"))
        return result
    }

    private suspend fun HttpResponse.assertRequestError(field: String? = null) {
        assertEquals(HttpStatusCode.BadRequest, status)
        val root = bodyAsText().json()
        assertEquals("INVALID_REQUEST", root.objectValue("error").string("code"))
        if (field != null) {
            assertTrue(root.objectValue("error").getValue("violations").toString().contains("\"field\":\"$field\""))
        }
    }

    private fun String.json(): JsonObject = Json.parseToJsonElement(this).jsonObject
    private fun JsonObject.objectValue(name: String): JsonObject = getValue(name).jsonObject
    private fun JsonObject.array(name: String) = getValue(name).jsonArray
    private fun JsonObject.string(name: String): String = getValue(name).jsonPrimitive.content

    private class FakeConfigurationDependencies(
        private val getResults: ArrayDeque<GetWorkspaceConfigurationResult> = ArrayDeque(),
        private val configureResults: ArrayDeque<ConfigureWorkspaceResult> = ArrayDeque(),
        private val addResults: ArrayDeque<AddRepositoryResult> = ArrayDeque(),
        private val removeResults: ArrayDeque<RemoveRepositoryResult> = ArrayDeque(),
    ) {
        var getCalls = 0
        val configureCommands = mutableListOf<ConfigureWorkspaceCommand>()
        val addCommands = mutableListOf<AddRepositoryCommand>()
        val removeCommands = mutableListOf<RemoveRepositoryCommand>()

        val getWorkspaceConfiguration = com.mindtable.bitbuckethelper.application.port.inbound.GetWorkspaceConfiguration {
            getCalls += 1
            getResults.removeFirst()
        }
        val configureWorkspace = com.mindtable.bitbuckethelper.application.port.inbound.ConfigureWorkspace {
            configureCommands += it
            configureResults.removeFirst()
        }
        val addRepository = com.mindtable.bitbuckethelper.application.port.inbound.AddRepository {
            addCommands += it
            addResults.removeFirst()
        }
        val removeRepository = com.mindtable.bitbuckethelper.application.port.inbound.RemoveRepository {
            removeCommands += it
            removeResults.removeFirst()
        }
    }

    companion object {
        private const val WORKSPACE_PATH = "/api/v1/configuration/workspace"
        private const val REPOSITORIES_PATH = "/api/v1/configuration/workspace/repositories"
        private const val CONFIGURE_BODY =
            """{"apiVersion":"1","bitbucketApiBaseUrl":"https://api.bitbucket.org/2.0","workspaceSlug":"acme-engineering"}"""
        private const val ADD_BODY = """{"apiVersion":"1","repositorySlug":"release-tools"}"""

        private fun gatewayFailures() = GatewayFailureCategory.entries.mapIndexed { index, category ->
            GatewayFailure(
                category = category,
                retryable = index == 0,
                retryAt = if (index == 0) Instant.parse("2026-08-17T10:30:00Z") else null,
            )
        }

        private fun repository(id: String, slug: String) = ConfiguredRepositoryProjection(
            repositoryId = RepositoryId(id),
            slug = slug,
            displayName = slug.replaceFirstChar(Char::uppercase),
            webUrl = URI("https://bitbucket.example/acme/$slug"),
        )

        private fun configuration() = WorkspaceConfigurationProjection(
            workspaceId = WorkspaceId("ws_acme"),
            bitbucketApiBaseUrl = URI("https://api.bitbucket.org/2.0"),
            workspaceSlug = "acme-engineering",
            workspaceDisplayName = "Acme Engineering",
            workspaceWebUrl = URI("https://bitbucket.example/acme-engineering"),
            retentionDays = 30,
            repositories = listOf(repository("repo_zeta", "zeta"), repository("repo_alpha", "alpha")),
        )
    }
}

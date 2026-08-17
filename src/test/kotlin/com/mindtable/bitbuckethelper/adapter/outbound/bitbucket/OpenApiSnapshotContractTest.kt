package com.mindtable.bitbuckethelper.adapter.outbound.bitbucket

import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import kotlin.io.path.readBytes
import kotlin.io.path.readText
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.Test

class OpenApiSnapshotContractTest {
    private val json = Json

    private val expectedOperations = linkedMapOf(
        "/user" to "getCurrentUser",
        "/workspaces/{workspace}" to "getWorkspace",
        "/repositories/{workspace}/{repo_slug}" to "getRepository",
        "/repositories/{workspace}/{repo_slug}/pullrequests" to "listAuthoredOpenPullRequests",
        "/repositories/{workspace}/{repo_slug}/pullrequests/{pull_request_id}" to "getPullRequest",
        "/repositories/{workspace}/{repo_slug}/effective-default-reviewers" to "listEffectiveDefaultReviewers",
        "/repositories/{workspace}/{repo_slug}/pullrequests/{pull_request_id}/statuses" to "listPullRequestStatuses",
        "/repositories/{workspace}/{repo_slug}/pullrequests/{pull_request_id}/tasks" to "listPullRequestTasks",
        "/repositories/{workspace}/{repo_slug}/pullrequests/{pull_request_id}/activity" to "listPullRequestActivity",
        "/repositories/{workspace}/{repo_slug}/pullrequests/{pull_request_id}/comments/{comment_id}" to "getPullRequestComment",
    )

    private val expectedFlattenedDefinitionProperties = mapOf(
        "commit" to setOf(
            "type", "hash", "date", "author", "committer", "message", "summary", "parents",
            "repository", "participants",
        ),
        "pullrequest_comment" to setOf(
            "type", "id", "created_on", "updated_on", "content", "user", "deleted", "parent",
            "inline", "links", "pullrequest", "resolution", "pending",
        ),
        "team" to setOf("type", "links", "created_on", "display_name", "uuid"),
        "user" to setOf(
            "type", "links", "created_on", "display_name", "uuid", "account_id", "account_status",
            "has_2fa_enabled", "nickname", "is_staff",
        ),
    )

    @Test
    fun `committed snapshot checksum and reduced operation are reproducible`() {
        val canonicalFile = Path.of("specs/bitbucket-cloud/openapi.json")
        val metadata = Path.of("specs/bitbucket-cloud/README.md").readText()
        val expectedSha = Regex("SHA-256: `([0-9a-f]{64})`")
            .find(metadata)?.groupValues?.get(1)
            ?: fail("README must contain the snapshot SHA-256")
        assertEquals(expectedSha, sha256(canonicalFile.readBytes()))

        val canonical = json.parseToJsonElement(canonicalFile.readText()).jsonObject
        assertEquals("2.0", canonical["swagger"]!!.jsonPrimitive.content)
        assertNotNull(canonical["paths"]!!.jsonObject["/user"]!!.jsonObject["get"])
        assertEquals(
            true,
            canonical["definitions"]!!.jsonObject["object"]!!.jsonObject["additionalProperties"]!!
                .jsonPrimitive.boolean,
        )

        val prepared = json.parseToJsonElement(
            Path.of("build/openapi/bitbucket-current-user.json").readText(),
        ).jsonObject
        assertEquals("2.0", prepared["swagger"]!!.jsonPrimitive.content)
        val preparedPaths = prepared["paths"]!!.jsonObject
        assertEquals(expectedOperations.keys.toList(), preparedPaths.keys.toList())
        preparedPaths.forEach { (path, pathItem) ->
            assertEquals(setOf("get", "parameters"), pathItem.jsonObject.keys)
            assertEquals(
                expectedOperations.getValue(path),
                pathItem.jsonObject["get"]!!.jsonObject["operationId"]!!.jsonPrimitive.content,
            )
            assertEquals(
                canonical["paths"]!!.jsonObject[path]!!.jsonObject["parameters"] ?: json.parseToJsonElement("[]"),
                pathItem.jsonObject["parameters"] ?: json.parseToJsonElement("[]"),
            )
        }
        assertEquals(
            expectedOperations.keys.toList(),
            preparedPaths.keys.toList(),
            "The selected-operation table must determine the generated path order",
        )
        val listPullRequests = preparedPaths.getValue(
            "/repositories/{workspace}/{repo_slug}/pullrequests",
        ).jsonObject.getValue("get").jsonObject
        val queryParameters = listPullRequests.getValue("parameters").jsonArray
        assertEquals(
            listOf("state", "q"),
            queryParameters.map { it.jsonObject.getValue("name").jsonPrimitive.content },
        )
        assertEquals("query", queryParameters[1].jsonObject.getValue("in").jsonPrimitive.content)
        assertEquals(false, queryParameters[1].jsonObject.getValue("required").jsonPrimitive.boolean)
        assertEquals("string", queryParameters[1].jsonObject.getValue("type").jsonPrimitive.content)
        assertFalse(
            prepared["definitions"]!!.jsonObject["object"]!!.jsonObject
                .containsKey("additionalProperties"),
        )
        assertEquals(
            setOf("Users", "Workspaces", "Repositories", "Pull requests"),
            prepared["tags"]!!.jsonArray.map { it.jsonObject["name"]!!.jsonPrimitive.content }.toSet(),
        )
    }

    @Test
    fun `reduced inheritance compatibility preserves selected model fields without chained allOf`() {
        val definitions = json.parseToJsonElement(
            Path.of("build/openapi/bitbucket-current-user.json").readText(),
        ).jsonObject["definitions"]!!.jsonObject

        expectedFlattenedDefinitionProperties.forEach { (name, expectedProperties) ->
            val definition = definitions.getValue(name).jsonObject
            assertFalse(definition.containsKey("allOf"), "$name must be a generator-compatible object")
            assertEquals(expectedProperties, definition["properties"]!!.jsonObject.keys)
        }
    }

    @Test
    fun `generated client contains only APIs selected by the reduced contract`() {
        val apiDirectory = Path.of(
            "build/generated/sources/bitbucket/src/main/kotlin/",
            "com/mindtable/bitbuckethelper/adapter/outbound/bitbucket/generated/api",
        )
        val generatedApis = Files.list(apiDirectory).use { paths ->
            paths
                .filter { it.fileName.toString().endsWith("Api.kt") }
                .map { it.fileName.toString() }
                .sorted()
                .toList()
        }

        assertEquals(
            listOf("PullRequestsApi.kt", "RepositoriesApi.kt", "UsersApi.kt", "WorkspacesApi.kt"),
            generatedApis,
        )

        val pullRequestsApi = apiDirectory.resolve("PullRequestsApi.kt").readText()
        assertTrue(
            pullRequestsApi.contains(
                "listAuthoredOpenPullRequests(repoSlug: kotlin.String, workspace: kotlin.String, " +
                    "state: kotlin.String?, q: kotlin.String?)",
            ),
        )
        val stateRequest = "localVariableQuery[\"state\"] = listOf(\"\$state\")"
        val qRequest = "localVariableQuery[\"q\"] = listOf(\"\$q\")"
        assertTrue(pullRequestsApi.contains(stateRequest))
        assertTrue(pullRequestsApi.contains(qRequest))
        assertTrue(pullRequestsApi.indexOf(stateRequest) < pullRequestsApi.indexOf(qRequest))
    }

    @Test
    fun `production gateway invokes every selected generated pull request operation`() {
        val gatewaySource = Path.of(
            "src/main/kotlin/com/mindtable/bitbuckethelper/adapter/outbound/bitbucket/GeneratedBitbucketGateway.kt",
        ).readText()
        val expectedInvocations = listOf(
            "pullRequests.listAuthoredOpenPullRequests(",
            "pullRequests.getPullRequest(",
            "pullRequests.listEffectiveDefaultReviewers(",
            "pullRequests.listPullRequestStatuses(",
            "pullRequests.listPullRequestTasks(",
            "pullRequests.listPullRequestActivity(",
            "pullRequests.getPullRequestComment(",
        )

        expectedInvocations.forEach { invocation ->
            assertEquals(
                1,
                gatewaySource.windowed(invocation.length).count { it == invocation },
                "Production gateway must invoke $invocation exactly once",
            )
        }

        var currentFunction = ""
        val rawClientCallOwners = buildList {
            gatewaySource.lineSequence().forEach { line ->
                Regex("private suspend fun ([A-Za-z0-9]+)").find(line)?.let { match ->
                    currentFunction = match.groupValues[1]
                }
                if ("paginationClient.get(" in line) add(currentFunction)
            }
        }
        assertEquals(
            listOf("fetchOpaqueObject", "fetchOpaquePullRequestPage"),
            rawClientCallOwners,
            "The raw client may only follow already validated opaque next URLs",
        )
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
}

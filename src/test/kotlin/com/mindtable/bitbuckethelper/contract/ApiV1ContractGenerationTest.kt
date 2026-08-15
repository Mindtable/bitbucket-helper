package com.mindtable.bitbuckethelper.contract

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.readText
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.yaml.snakeyaml.Yaml

class ApiV1ContractGenerationTest {
    private val projectRoot: Path = Path.of(System.getProperty("user.dir"))

    @Test
    fun `canonical contract generates the complete product operation surface`() {
        val contract = projectRoot.resolve("openapi/api-v1.yaml")
        assertTrue(contract.isRegularFile(), "openapi/api-v1.yaml must be the canonical product contract")

        val contractSource = contract.readText()
        val operationIds = Regex("(?m)^\\s{6}operationId: ([A-Za-z0-9]+)$")
            .findAll(contractSource)
            .map { it.groupValues[1] }
            .toSet()

        assertEquals(
            setOf(
                "getDashboard",
                "listPullRequests",
                "getPullRequest",
                "getInbox",
                "getLiveActivityContent",
                "acknowledgeActionItem",
                "startRefreshRun",
                "getRefreshRun",
                "getSynchronization",
                "getWorkspaceConfiguration",
                "configureWorkspace",
                "addRepository",
                "removeRepository",
                "getHealth",
                "getBrowserSession",
            ),
            operationIds,
        )
        assertEquals(
            21,
            Regex.escape("${'$'}ref: '#/components/headers/CacheControlNoStore'").toRegex()
                .findAll(contractSource)
                .count(),
            "all 15 normal responses and all 6 request-error responses must declare no-store",
        )
    }

    @Test
    fun `committed generated surfaces contain every operation and model output`() {
        val kotlinModels = projectRoot.resolve("src/generated/api-v1/kotlin/src/main/kotlin")
        val typeScriptClient = projectRoot.resolve("web/src/generated/api-v1")

        assertTrue(kotlinModels.isDirectory(), "committed generated Kotlin models are required")
        assertTrue(typeScriptClient.isDirectory(), "committed generated TypeScript client is required")
        assertTrue(regularFiles(kotlinModels).any { it.fileName.toString() == "DashboardResponse.kt" })
        assertTrue(regularFiles(kotlinModels).none { it.fileName.toString().endsWith("Api.kt") })

        val operationIds = regularFiles(typeScriptClient.resolve("src/apis"))
            .flatMap { file ->
                Regex("(?m)^    async ([A-Za-z0-9]+)\\(")
                    .findAll(file.readText())
                    .map { it.groupValues[1] }
                    .filterNot { it.endsWith("Raw") || it.endsWith("RequestOpts") }
                    .toList()
            }
            .toSet()
        assertEquals(
            setOf(
                "getDashboard",
                "listPullRequests",
                "getPullRequest",
                "getInbox",
                "getLiveActivityContent",
                "acknowledgeActionItem",
                "startRefreshRun",
                "getRefreshRun",
                "getSynchronization",
                "getWorkspaceConfiguration",
                "configureWorkspace",
                "addRepository",
                "removeRepository",
                "getHealth",
                "getBrowserSession",
            ),
            operationIds,
        )
    }

    @Test
    fun `typescript discriminated unions fail clearly for unknown result types`() {
        val modelDirectory = projectRoot.resolve("web/src/generated/api-v1/src/models")
        val discriminatedUnions = regularFiles(modelDirectory)
            .filter { it.readText().contains("export type ") }

        assertEquals(20, discriminatedUnions.size, "every declared oneOf union must be generated")
        discriminatedUnions.forEach { model ->
            val generatedSource = model.readText()
            assertTrue(
                "throw new Error(`Unknown " in generatedSource,
                "${model.fileName} must reject unknown discriminator values",
            )
            assertTrue(
                "default:\n            return json;" !in generatedSource &&
                    "default:\n            return value;" !in generatedSource,
                "${model.fileName} must not silently accept an unknown discriminator",
            )
        }
    }

    @Test
    fun `typescript timestamp models preserve exact RFC 3339 strings across generated converters`() {
        val modelDirectory = projectRoot.resolve("web/src/generated/api-v1/src/models")
        val generatedSources = regularFiles(modelDirectory).associateWith(Path::readText)
        val timestampProperties = contractRoot
            .mapValue("components")
            .mapValue("schemas")
            .flatMap { (schemaName, schemaValue) ->
                val properties = schemaValue.asMap()["properties"]?.asMap().orEmpty()
                properties
                    .filterValues(::referencesUtcInstant)
                    .keys
                    .map { propertyName -> schemaName to propertyName }
            }

        assertTrue(timestampProperties.isNotEmpty(), "the canonical contract must expose timestamp properties")
        timestampProperties.forEach { (schemaName, propertyName) ->
            val model = modelDirectory.resolve("$schemaName.ts")
            val source = generatedSources.getValue(model)
            assertTrue(
                Regex("(?m)^\\s*$propertyName\\??: string(?: \\| null)?;").containsMatchIn(source),
                "$schemaName.$propertyName must expose its UtcInstant as a string",
            )
            assertTrue("'$propertyName': json['$propertyName']" in source)
            assertTrue("'$propertyName': value['$propertyName']" in source)
        }

        generatedSources.forEach { (model, source) ->
            assertFalse(
                Regex("(?m)^\\s*[A-Za-z0-9_]+\\??: Date;").containsMatchIn(source),
                "${model.fileName} must expose date-time values as wire strings",
            )
            assertFalse("new Date(" in source, "${model.fileName} must not parse date-time wire strings")
            assertFalse(".toISOString()" in source, "${model.fileName} must not rewrite date-time wire strings")
        }

    }

    @Test
    fun `kotlin class serial names are limited to discriminated variants`() {
        val modelDirectory = projectRoot.resolve(
            "src/generated/api-v1/kotlin/src/main/kotlin/com/mindtable/bitbuckethelper/generated/api/v1/model",
        )
        val classSerialName = Regex("@SerialName\\(value = \\\"([^\\\"]+)\\\"\\)\\s+(?:data )?class")
        val variantSerialNames = contractRoot
            .mapValue("components")
            .mapValue("schemas")
            .mapNotNull { (schemaName, schemaValue) ->
                val schema = schemaValue.asMap()
                val implementedUnions = schema["x-kotlin-implements"] as? List<*> ?: return@mapNotNull null
                if (implementedUnions.isEmpty()) return@mapNotNull null
                val serialName = schema
                    .mapValue("properties")
                    .mapValue("type")
                    .listValue("enum")
                    .single()
                    .toString()
                schemaName to serialName
            }
            .toMap()

        regularFiles(modelDirectory).forEach { model ->
            val modelName = model.fileName.toString().removeSuffix(".kt")
            val actualSerialName = classSerialName.find(model.readText())?.groupValues?.get(1)
            val expectedSerialName = variantSerialNames[modelName]
            if (expectedSerialName == null) {
                assertEquals(null, actualSerialName, "$modelName is not a discriminated-union variant")
            } else {
                assertEquals(expectedSerialName, actualSerialName, "$modelName must retain its discriminator serial name")
            }
        }
    }

    @Test
    fun `collection schemas encode every canonical ordering key and tie breaker`() {
        val repositoryOrder = listOf(
            orderKey("displayName", "ascending", "localeIndependentCaseFold"),
            orderKey("repositoryId", "ascending", "opaqueIdentifier"),
        )
        val pullRequestOrder = listOf(
            orderKey("updatedAt", "descending", "rfc3339Instant"),
            orderKey("pullRequestId", "ascending", "opaqueIdentifier"),
        )
        val actionItemOrder = listOf(
            orderKey("activityAt", "descending", "rfc3339Instant"),
            orderKey("actionItemId", "ascending", "opaqueIdentifier"),
        )

        assertEquals(repositoryOrder, collectionOrder("WorkspaceConfiguration", "repositories"))
        assertEquals(repositoryOrder, collectionOrder("DashboardSnapshot", "repositoryGroups"))
        assertEquals(repositoryOrder, collectionOrder("PullRequestsAvailableResult", "repositoryGroups"))
        assertEquals(pullRequestOrder, collectionOrder("RepositoryGroup", "pullRequests"))
        assertEquals(actionItemOrder, collectionOrder("PullRequestCard", "actionItems"))
        assertEquals(actionItemOrder, collectionOrder("Inbox", "items"))
    }

    @Test
    fun `transport profiles distinguish loopback reads mutations and browser session`() {
        val security = contractRoot.mapValue("x-bitbucket-helper-transport-security")
        assertEquals(
            mapOf(
                "production" to "disabled",
                "emitAccessControlAllowOrigin" to false,
            ),
            security.mapValue("cors"),
        )

        val profiles = security.mapValue("profiles")
        assertEquals(
            mapOf(
                "hostValidation" to "exactConfiguredLoopbackAuthority",
                "originValidation" to "exactAllowedOriginIfPresent",
                "jsonContentType" to "notApplicable",
                "csrf" to "notRequired",
            ),
            profiles.mapValue("loopbackRead").mapValue("loopback"),
        )
        assertEquals(
            mapOf(
                "hostValidation" to "exactConfiguredLoopbackAuthority",
                "originValidation" to "exactAllowedOriginRequired",
                "jsonContentType" to mapOf(
                    "requiredWhenBodyPresent" to true,
                    "allowed" to listOf("application/json"),
                ),
                "csrf" to mapOf(
                    "requirement" to "exactInMemoryToken",
                    "header" to "X-CSRF-Token",
                ),
            ),
            profiles.mapValue("loopbackMutation").mapValue("loopback"),
        )
        assertEquals(
            profiles.mapValue("loopbackRead").mapValue("loopback"),
            profiles.mapValue("loopbackOnlyRead").mapValue("loopback"),
        )
        assertEquals(
            mapOf(
                "available" to true,
                "hostValidation" to "notRequired",
                "originValidation" to "notRequired",
                "csrf" to "notRequired",
            ),
            profiles.mapValue("loopbackRead").mapValue("unixSocket"),
        )
        assertEquals(
            mapOf(
                "available" to true,
                "hostValidation" to "notRequired",
                "originValidation" to "notRequired",
                "csrf" to "notRequired",
                "jsonContentType" to mapOf(
                    "requiredWhenBodyPresent" to true,
                    "allowed" to listOf("application/json"),
                ),
            ),
            profiles.mapValue("loopbackMutation").mapValue("unixSocket"),
        )
        assertEquals(
            mapOf(
                "available" to false,
                "reason" to "browserSessionIsLoopbackOnly",
            ),
            profiles.mapValue("loopbackOnlyRead").mapValue("unixSocket"),
        )

        val expectedProfiles = mapOf(
            "getDashboard" to "loopbackRead",
            "listPullRequests" to "loopbackRead",
            "getPullRequest" to "loopbackRead",
            "getInbox" to "loopbackRead",
            "getLiveActivityContent" to "loopbackRead",
            "acknowledgeActionItem" to "loopbackMutation",
            "startRefreshRun" to "loopbackMutation",
            "getRefreshRun" to "loopbackRead",
            "getSynchronization" to "loopbackRead",
            "getWorkspaceConfiguration" to "loopbackRead",
            "configureWorkspace" to "loopbackMutation",
            "addRepository" to "loopbackMutation",
            "removeRepository" to "loopbackMutation",
            "getHealth" to "loopbackRead",
            "getBrowserSession" to "loopbackOnlyRead",
        )
        assertEquals(expectedProfiles, operationTransportProfiles())

        operationsById().forEach { (operationId, operation) ->
            val parameterReferences = (operation["parameters"] as? List<*>)
                .orEmpty()
                .mapNotNull { parameter -> parameter.asMap()["${'$'}ref"]?.toString() }
            if (expectedProfiles.getValue(operationId) == "loopbackMutation") {
                assertTrue(
                    "#/components/parameters/CsrfTokenHeader" in parameterReferences,
                    "$operationId must use the generated CSRF parameter",
                )
            } else {
                assertFalse(
                    "#/components/parameters/CsrfTokenHeader" in parameterReferences,
                    "$operationId must not expose CSRF on a read",
                )
            }
        }

        val componentParameters = contractRoot.mapValue("components").mapValue("parameters")
        val generatedHeaderNames = componentParameters.values
            .map { it.asMap().getValue("name").toString() }
        assertFalse(generatedHeaderNames.any { it.equals("Host", true) || it.equals("Origin", true) })

        regularFiles(projectRoot.resolve("web/src/generated/api-v1/src/apis")).forEach { api ->
            val generatedSource = api.readText()
            assertFalse(
                Regex("(?im)^\\s*(host|origin)\\??:\\s*string").containsMatchIn(generatedSource),
                "${api.fileName} must not expose browser-managed Host or Origin request parameters",
            )
        }
    }

    private val contractRoot: Map<String, Any?> by lazy {
        Yaml().load(projectRoot.resolve("openapi/api-v1.yaml").readText())
    }

    private fun orderKey(field: String, direction: String, comparison: String): Map<String, String> =
        mapOf("field" to field, "direction" to direction, "comparison" to comparison)

    private fun collectionOrder(schemaName: String, propertyName: String): List<Map<String, String>> {
        val property = contractRoot
            .mapValue("components")
            .mapValue("schemas")
            .mapValue(schemaName)
            .mapValue("properties")
            .mapValue(propertyName)
        return property.listValue("x-canonical-order").map { item ->
            item.asMap().mapValues { (_, value) -> value.toString() }
        }
    }

    private fun operationsById(): Map<String, Map<String, Any?>> = contractRoot
        .mapValue("paths")
        .values
        .flatMap { path ->
            path.asMap().values.mapNotNull { operationValue ->
                val operation = operationValue.asMap()
                val operationId = operation["operationId"]?.toString() ?: return@mapNotNull null
                operationId to operation
            }
        }
        .toMap()

    private fun operationTransportProfiles(): Map<String, String> = operationsById()
        .mapValues { (_, operation) ->
            operation.getValue("x-bitbucket-helper-transport-profile").toString()
        }

    private fun referencesUtcInstant(value: Any?): Boolean = when (value) {
        is Map<*, *> -> value["${'$'}ref"] == "#/components/schemas/UtcInstant" ||
            value.values.any(::referencesUtcInstant)
        is List<*> -> value.any(::referencesUtcInstant)
        else -> false
    }

    @Suppress("UNCHECKED_CAST")
    private fun Any?.asMap(): Map<String, Any?> = this as Map<String, Any?>

    private fun Map<String, Any?>.mapValue(key: String): Map<String, Any?> = getValue(key).asMap()

    @Suppress("UNCHECKED_CAST")
    private fun Map<String, Any?>.listValue(key: String): List<Any?> = getValue(key) as List<Any?>

    private fun regularFiles(root: Path): List<Path> =
        Files.walk(root).use { paths -> paths.filter(Files::isRegularFile).toList() }
}

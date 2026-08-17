package com.mindtable.bitbuckethelper.contract

import com.mindtable.bitbuckethelper.generated.api.v1.model.AcknowledgeActionItemResponse
import com.mindtable.bitbuckethelper.generated.api.v1.model.DashboardResponse
import com.mindtable.bitbuckethelper.generated.api.v1.model.GetRefreshRunResponse
import com.mindtable.bitbuckethelper.generated.api.v1.model.GetWorkspaceConfigurationResponse
import com.mindtable.bitbuckethelper.generated.api.v1.model.HealthResponse
import com.mindtable.bitbuckethelper.generated.api.v1.model.LiveActivityContentResponse
import com.mindtable.bitbuckethelper.generated.api.v1.model.RequestErrorEnvelope
import com.mindtable.bitbuckethelper.generated.api.v1.model.StartRefreshRunResponse
import com.mindtable.bitbuckethelper.generated.api.v1.model.SynchronizationResponse
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.isDirectory
import kotlin.io.path.readText
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.SerializationException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ApiV1FixtureTest {
    private val fixtureRoot: Path = Path.of(System.getProperty("user.dir"), "openapi", "fixtures", "v1")
    private val strictJson = Json {
        ignoreUnknownKeys = false
        explicitNulls = true
    }

    @Test
    fun `shared valid fixtures are present and contain JSON documents`() {
        val validRoot = fixtureRoot.resolve("valid")
        assertTrue(validRoot.isDirectory(), "shared valid v1 fixtures are required")
        val fixtures = jsonFiles(validRoot)
        assertTrue(fixtures.isNotEmpty(), "at least one valid fixture is required")
        fixtures.forEach { fixture ->
            val document = fixture.readText()
            assertUtcInstants(Json.parseToJsonElement(document))
            val encoded = decodeAndEncode(fixture.fileName.toString(), document)
            assertEquals(Json.parseToJsonElement(document), Json.parseToJsonElement(encoded), fixture.toString())
        }
    }

    @Test
    fun `shared invalid fixtures cover strict wire rejection`() {
        val invalidRoot = fixtureRoot.resolve("invalid")
        assertTrue(invalidRoot.isDirectory(), "shared invalid v1 fixtures are required")
        val names = jsonFiles(invalidRoot).map { it.fileName.toString() }.toSet()

        assertTrue("missing-discriminator.json" in names)
        assertTrue("unknown-discriminator.json" in names)
        assertTrue("non-utc-instant.json" in names)
        assertTrue("ambiguous-null.json" in names)
        assertTrue("extra-field.json" in names)

        listOf(
            "missing-discriminator.json",
            "unknown-discriminator.json",
            "ambiguous-null.json",
            "extra-field.json",
        ).forEach { name ->
            assertThrows(SerializationException::class.java, {
                strictJson.decodeFromString(DashboardResponse.serializer(), invalidRoot.resolve(name).readText())
            }, name)
        }
        assertThrows(IllegalArgumentException::class.java, {
            assertUtcInstants(Json.parseToJsonElement(invalidRoot.resolve("non-utc-instant.json").readText()))
        }, "non-UTC instants must be rejected")
    }

    private fun decodeAndEncode(name: String, document: String): String = when (name) {
        "dashboard-snapshot-unchanged.json" -> strictJson.encodeToString(
            DashboardResponse.serializer(),
            strictJson.decodeFromString(DashboardResponse.serializer(), document),
        )
        "live-content-available.json" -> strictJson.encodeToString(
            LiveActivityContentResponse.serializer(),
            strictJson.decodeFromString(LiveActivityContentResponse.serializer(), document),
        )
        "acknowledgment-already-applied.json" -> strictJson.encodeToString(
            AcknowledgeActionItemResponse.serializer(),
            strictJson.decodeFromString(AcknowledgeActionItemResponse.serializer(), document),
        )
        "refresh-run-registered.json" -> strictJson.encodeToString(
            StartRefreshRunResponse.serializer(),
            strictJson.decodeFromString(StartRefreshRunResponse.serializer(), document),
        )
        "refresh-run-in-progress.json" -> strictJson.encodeToString(
            GetRefreshRunResponse.serializer(),
            strictJson.decodeFromString(GetRefreshRunResponse.serializer(), document),
        )
        "synchronization-available.json" -> strictJson.encodeToString(
            SynchronizationResponse.serializer(),
            strictJson.decodeFromString(SynchronizationResponse.serializer(), document),
        )
        "workspace-configuration-available.json" -> strictJson.encodeToString(
            GetWorkspaceConfigurationResponse.serializer(),
            strictJson.decodeFromString(GetWorkspaceConfigurationResponse.serializer(), document),
        )
        "health-degraded.json" -> strictJson.encodeToString(
            HealthResponse.serializer(),
            strictJson.decodeFromString(HealthResponse.serializer(), document),
        )
        "request-error.json" -> strictJson.encodeToString(
            RequestErrorEnvelope.serializer(),
            strictJson.decodeFromString(RequestErrorEnvelope.serializer(), document),
        )
        else -> error("Fixture has no generated DTO decoder: $name")
    }

    private fun assertUtcInstants(element: JsonElement, fieldName: String? = null) {
        when (element) {
            is JsonObject -> element.forEach { (name, value) -> assertUtcInstants(value, name) }
            is JsonArray -> element.forEach { value -> assertUtcInstants(value, fieldName) }
            is JsonPrimitive -> if (fieldName?.matches(instantFieldName) == true && element.isString) {
                require(utcInstant.matches(element.content)) {
                    "$fieldName must be an RFC 3339 UTC instant: ${element.content}"
                }
            }
        }
    }

    private fun jsonFiles(root: Path): List<Path> =
        Files.walk(root).use { paths ->
            paths.filter { Files.isRegularFile(it) && it.extension == "json" }.sorted().toList()
        }

    private companion object {
        val instantFieldName = Regex(".*(?:At|Time|Since)$")
        val utcInstant = Regex("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(?:\\.\\d+)?Z")
    }
}

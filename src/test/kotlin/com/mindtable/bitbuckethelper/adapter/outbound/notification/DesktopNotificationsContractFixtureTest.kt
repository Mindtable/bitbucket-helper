package com.mindtable.bitbuckethelper.adapter.outbound.notification

import java.nio.file.Path
import java.security.MessageDigest
import kotlin.io.path.readBytes
import kotlin.io.path.readText
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DesktopNotificationsContractFixtureTest {
    private val fixturePath = Path.of("contracts/desktop-notifications-send-cases.json")
    private val providerMetadataPath = Path.of("contracts/desktop-notifications-provider.txt")
    private val strictJson = Json { ignoreUnknownKeys = false }

    @Test
    fun `provider metadata pins the approved executable revision and release`() {
        assertEquals(
            """
            repository=../desktop-notifications
            revision=fe12b2e
            fixture_sha256=91e5cfd97445eba9c0f0f596958584f76043513e521b080b5ce7d415ada19270
            executable=desktop-notifications
            provider_release=0.3.0
            """.trimIndent() + "\n",
            providerMetadataPath.readText(),
        )
    }

    @Test
    fun `fixture checksum is verified before strict decoding and every provider outcome is covered`() {
        val fixtureBytes = fixturePath.readBytes()
        assertEquals(EXPECTED_FIXTURE_SHA256, sha256(fixtureBytes))

        val fixture = strictJson.decodeFromString(SendCasesFixture.serializer(), fixtureBytes.toString(Charsets.UTF_8))
        val casesByName = fixture.cases.associateBy(SendCaseFixture::name)
        assertEquals(EXPECTED_CASES.keys, casesByName.keys)
        EXPECTED_CASES.forEach { (name, expected) ->
            val actual = casesByName.getValue(name)
            assertEquals(expected.scenario, actual.scenario, name)
            assertEquals(expected.expectedExit, actual.expectedExit, name)
            assertEquals(expected.expectedStdout, actual.expectedStdout, name)
            assertEquals(expected.expectedAttempts, actual.expectedAttempts, name)
        }
        assertEquals(
            setOf("invalid_arguments", "unsupported_platform", "dependency_unavailable", "delivery_timeout", "delivery_failed", "internal_error"),
            fixture.cases.mapNotNull { errorCode(it.expectedStdout) }.toSet(),
        )
    }

    @Test
    fun `fixture accepts the complete lowercase sound contract including ping`() {
        val fixtureBytes = fixturePath.readBytes()
        assertEquals(EXPECTED_FIXTURE_SHA256, sha256(fixtureBytes))
        val fixture = strictJson.decodeFromString(SendCasesFixture.serializer(), fixtureBytes.toString(Charsets.UTF_8))

        val fixtureSounds = fixture.cases.flatMap { case ->
            case.arguments.zipWithNext()
                .filter { (argument, _) -> argument == "--sound" }
                .map { (_, value) -> value }
        }.toSet()
        assertEquals(setOf("default", "ping"), fixtureSounds)
        assertTrue(fixtureSounds.all { it in LOWERCASE_SOUND_VALUES })
        assertEquals(
            setOf(
                "none",
                "default",
                "basso",
                "blow",
                "bottle",
                "frog",
                "funk",
                "glass",
                "hero",
                "morse",
                "ping",
                "pop",
                "purr",
                "sosumi",
                "submarine",
                "tink",
            ),
            LOWERCASE_SOUND_VALUES,
        )
        assertTrue(LOWERCASE_SOUND_VALUES.all { it == it.lowercase() })
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }

    private fun errorCode(stdout: String): String? =
        strictJson.parseToJsonElement(stdout).jsonObject["error"]
            ?.jsonObject?.get("code")?.jsonPrimitive?.content

    private data class ExpectedCase(
        val scenario: String,
        val expectedExit: Int,
        val expectedStdout: String,
        val expectedAttempts: Int,
    )

    private companion object {
        const val EXPECTED_FIXTURE_SHA256 =
            "91e5cfd97445eba9c0f0f596958584f76043513e521b080b5ce7d415ada19270"

        val LOWERCASE_SOUND_VALUES = setOf(
            "none",
            "default",
            "basso",
            "blow",
            "bottle",
            "frog",
            "funk",
            "glass",
            "hero",
            "morse",
            "ping",
            "pop",
            "purr",
            "sosumi",
            "submarine",
            "tink",
        )

        val EXPECTED_CASES = mapOf(
            "accepted_minimal" to ExpectedCase(
                scenario = "accepted",
                expectedExit = 0,
                expectedStdout = "{\"status\":\"accepted\"}\n",
                expectedAttempts = 1,
            ),
            "accepted_full_loopback" to ExpectedCase(
                scenario = "accepted",
                expectedExit = 0,
                expectedStdout = "{\"status\":\"accepted\"}\n",
                expectedAttempts = 1,
            ),
            "accepted_named_sound" to ExpectedCase(
                scenario = "accepted",
                expectedExit = 0,
                expectedStdout = "{\"status\":\"accepted\"}\n",
                expectedAttempts = 1,
            ),
            "invalid_arguments_precede_platform" to ExpectedCase(
                scenario = "accepted",
                expectedExit = 1,
                expectedStdout = "{\"status\":\"failed\",\"error\":{\"code\":\"invalid_arguments\",\"message\":\"The send arguments are invalid.\"}}\n",
                expectedAttempts = 0,
            ),
            "unsupported_platform" to ExpectedCase(
                scenario = "accepted",
                expectedExit = 1,
                expectedStdout = "{\"status\":\"failed\",\"error\":{\"code\":\"unsupported_platform\",\"message\":\"Desktop notifications are supported only on macOS.\"}}\n",
                expectedAttempts = 0,
            ),
            "dependency_unavailable" to ExpectedCase(
                scenario = "dependency_unavailable",
                expectedExit = 1,
                expectedStdout = "{\"status\":\"failed\",\"error\":{\"code\":\"dependency_unavailable\",\"message\":\"The notification adapter is unavailable.\"}}\n",
                expectedAttempts = 0,
            ),
            "delivery_timeout" to ExpectedCase(
                scenario = "delivery_timeout",
                expectedExit = 1,
                expectedStdout = "{\"status\":\"failed\",\"error\":{\"code\":\"delivery_timeout\",\"message\":\"The notification adapter did not finish within 10 seconds.\"}}\n",
                expectedAttempts = 1,
            ),
            "delivery_failed" to ExpectedCase(
                scenario = "delivery_failed",
                expectedExit = 1,
                expectedStdout = "{\"status\":\"failed\",\"error\":{\"code\":\"delivery_failed\",\"message\":\"The notification adapter reported failure.\"}}\n",
                expectedAttempts = 1,
            ),
            "internal_error" to ExpectedCase(
                scenario = "internal_error",
                expectedExit = 1,
                expectedStdout = "{\"status\":\"failed\",\"error\":{\"code\":\"internal_error\",\"message\":\"Desktop notification delivery failed unexpectedly.\"}}\n",
                expectedAttempts = 1,
            ),
        )
    }
}

@Serializable
private data class SendCasesFixture(val cases: List<SendCaseFixture>)

@Serializable
private data class SendCaseFixture(
    val name: String,
    val arguments: List<String>,
    val platform: String,
    val scenario: String,
    val expected_exit: Int,
    val expected_stdout: String,
    val expected_attempts: Int,
) {
    val expectedExit: Int get() = expected_exit
    val expectedStdout: String get() = expected_stdout
    val expectedAttempts: Int get() = expected_attempts
}

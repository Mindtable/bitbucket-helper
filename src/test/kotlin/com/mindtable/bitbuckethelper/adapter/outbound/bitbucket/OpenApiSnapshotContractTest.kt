package com.mindtable.bitbuckethelper.adapter.outbound.bitbucket

import java.nio.file.Path
import java.security.MessageDigest
import kotlin.io.path.readBytes
import kotlin.io.path.readText
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.Test

class OpenApiSnapshotContractTest {
    private val json = Json

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
        assertEquals(setOf("/user"), prepared["paths"]!!.jsonObject.keys)
        assertFalse(
            prepared["definitions"]!!.jsonObject["object"]!!.jsonObject
                .containsKey("additionalProperties"),
        )
        val operation = prepared["paths"]!!.jsonObject["/user"]!!.jsonObject["get"]!!.jsonObject
        assertEquals("getCurrentUser", operation["operationId"]!!.jsonPrimitive.content)
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
}

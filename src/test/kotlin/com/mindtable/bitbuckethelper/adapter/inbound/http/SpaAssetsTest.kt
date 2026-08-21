package com.mindtable.bitbuckethelper.adapter.inbound.http

import io.ktor.http.ContentType
import io.ktor.http.withCharset
import java.nio.charset.StandardCharsets.UTF_8
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SpaAssetsTest {
    @Test
    fun `entry point must exist and be non-empty`() {
        val missing = SpaAssets(SpaResourceReader { null })
        val empty = SpaAssets(SpaResourceReader { ByteArray(0) })

        assertEquals("Packaged SPA assets are unavailable", assertThrows(IllegalStateException::class.java) {
            missing.requireEntryPoint()
        }.message)
        assertThrows(IllegalStateException::class.java) { empty.requireEntryPoint() }
    }

    @Test
    fun `lookup allows only the entry point and nested asset paths`() {
        val reads = mutableListOf<String>()
        val assets = SpaAssets(SpaResourceReader { resource ->
            reads += resource
            resource.encodeToByteArray()
        })

        assertArrayEquals("spa/index.html".encodeToByteArray(), assets.find("/")?.bytes)
        assertArrayEquals("spa/index.html".encodeToByteArray(), assets.find("index.html")?.bytes)
        assertArrayEquals(
            "spa/assets/chunks/app-123.js".encodeToByteArray(),
            assets.find("assets/chunks/app-123.js")?.bytes,
        )
        assertEquals(
            listOf("spa/index.html", "spa/index.html", "spa/assets/chunks/app-123.js"),
            reads,
        )
        assertTrue(reads.all { it.startsWith("spa/") })
    }

    @Test
    fun `lookup rejects unsafe and unsupported paths before reading`() {
        val reads = mutableListOf<String>()
        val assets = SpaAssets(SpaResourceReader { resource ->
            reads += resource
            resource.encodeToByteArray()
        })

        listOf(
            "../application.conf",
            "assets/../../application.conf",
            "assets\\app.js",
            "assets//app.js",
            "assets/./app.js",
            "assets/../app.js",
            "assets/app.js/",
            "assets/app.txt",
            "other/app.js",
            "/assets/app.js",
            "assets/app\u0000.js",
        ).forEach { path -> assertNull(assets.find(path), path) }

        assertTrue(reads.isEmpty())
    }

    @Test
    fun `lookup classifies supported content types`() {
        val assets = SpaAssets(SpaResourceReader { resource -> resource.encodeToByteArray() })

        assertEquals(ContentType.Text.Html.withCharset(UTF_8), assets.find("index.html")?.contentType)
        assertEquals(ContentType.Text.CSS.withCharset(UTF_8), assets.find("assets/app.css")?.contentType)
        assertEquals(
            ContentType.parse("text/javascript; charset=UTF-8"),
            assets.find("assets/app.js")?.contentType,
        )
        assertEquals(ContentType.parse("image/svg+xml"), assets.find("assets/logo.svg")?.contentType)
        assertEquals(ContentType.Image.PNG, assets.find("assets/logo.png")?.contentType)
        assertEquals(ContentType.parse("image/x-icon"), assets.find("assets/favicon.ico")?.contentType)
        assertEquals(ContentType.parse("font/woff2"), assets.find("assets/app.woff2")?.contentType)
        assertEquals(ContentType.Application.Json, assets.find("assets/manifest.json")?.contentType)
    }
}

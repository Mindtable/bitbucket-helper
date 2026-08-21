package com.mindtable.bitbuckethelper.adapter.inbound.http

import io.ktor.http.ContentType
import io.ktor.http.withCharset
import java.nio.charset.StandardCharsets.UTF_8

internal fun interface SpaResourceReader {
    fun read(resourceName: String): ByteArray?
}

internal data class SpaAsset(
    val bytes: ByteArray,
    val contentType: ContentType,
)

internal class SpaAssets(
    private val reader: SpaResourceReader,
) {
    fun requireEntryPoint() {
        val entry = reader.read(ENTRY_POINT_RESOURCE)
        check(entry != null && entry.isNotEmpty()) {
            "Packaged SPA assets are unavailable"
        }
    }

    fun find(relativePath: String): SpaAsset? {
        val normalized = normalizeSpaRelativePath(relativePath) ?: return null
        val contentType = spaContentType(normalized) ?: return null
        val bytes = reader.read("$SPA_RESOURCE_PREFIX$normalized") ?: return null
        return SpaAsset(bytes, contentType)
    }

    companion object {
        fun classpath(): SpaAssets = SpaAssets(SpaResourceReader { name ->
            SpaAssets::class.java.classLoader.getResourceAsStream(name)?.use { it.readBytes() }
        })
    }
}

private const val SPA_RESOURCE_PREFIX = "spa/"
private const val ENTRY_POINT = "index.html"
private const val ENTRY_POINT_RESOURCE = "$SPA_RESOURCE_PREFIX$ENTRY_POINT"
private val ALLOWED_ASSET_SEGMENT = Regex("[A-Za-z0-9._-]+")

private fun normalizeSpaRelativePath(relativePath: String): String? = when {
    relativePath == "/" || relativePath == ENTRY_POINT -> ENTRY_POINT
    !relativePath.startsWith("assets/") -> null
    else -> relativePath
        .removePrefix("assets/")
        .split('/')
        .takeIf { segments ->
            segments.isNotEmpty() && segments.all { segment ->
                segment != "." && segment != ".." && ALLOWED_ASSET_SEGMENT.matches(segment)
            }
        }
        ?.joinToString(separator = "/", prefix = "assets/")
}

private fun spaContentType(path: String): ContentType? = when {
    path == ENTRY_POINT -> ContentType.Text.Html.withCharset(UTF_8)
    path.endsWith(".css") -> ContentType.Text.CSS.withCharset(UTF_8)
    path.endsWith(".js") -> ContentType.parse("text/javascript; charset=UTF-8")
    path.endsWith(".svg") -> ContentType.parse("image/svg+xml")
    path.endsWith(".png") -> ContentType.Image.PNG
    path.endsWith(".ico") -> ContentType.parse("image/x-icon")
    path.endsWith(".woff2") -> ContentType.parse("font/woff2")
    path.endsWith(".json") -> ContentType.Application.Json
    else -> null
}

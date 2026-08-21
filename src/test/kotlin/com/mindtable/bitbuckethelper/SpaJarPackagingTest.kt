package com.mindtable.bitbuckethelper

import java.util.zip.ZipFile
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SpaJarPackagingTest {
    @Test
    fun `fat jar contains only the verified production SPA`() {
        ZipFile(locateSingleFatJar().toFile()).use { jar ->
            val index = jar.getInputStream(requireNotNull(jar.getEntry("spa/index.html")))
                .bufferedReader()
                .use { it.readText() }
            val references = Regex("(?:src|href)=\"/assets/([^\"]+)\"")
                .findAll(index)
                .map { "spa/assets/${it.groupValues[1]}" }
                .toList()
            assertTrue(references.isNotEmpty())
            references.forEach { assertNotNull(jar.getEntry(it), "missing $it") }
            val names = jar.entries().asSequence().map { it.name }.toList()
            assertTrue(names.none { it.startsWith("spa/") && it.endsWith(".map") })
            assertTrue(names.none { it.startsWith("spa/") && it.endsWith(".ts") })
        }
    }
}

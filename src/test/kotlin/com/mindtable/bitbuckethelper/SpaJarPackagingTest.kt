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
            assertTrue(references.any { it.endsWith(".js") })
            assertTrue(references.any { it.endsWith(".css") })
            references.forEach { assertNotNull(jar.getEntry(it), "missing $it") }
            val names = jar.entries().asSequence().map { it.name }.toList()
            val spaFiles = names.filter { it.startsWith("spa/") }
            assertTrue(
                spaFiles.none { name ->
                    val fileName = name.substringAfterLast('/')
                    fileName.endsWith(".map") ||
                        fileName.endsWith(".ts") ||
                        fileName.endsWith(".tsx") ||
                        fileName.endsWith(".vue") ||
                        fileName.startsWith(".env") ||
                        fileName == "package.json" ||
                        fileName == "package-lock.json"
                },
            )
            spaFiles.filter { it.endsWith(".js") }.forEach { name ->
                val source = jar.getInputStream(requireNotNull(jar.getEntry(name)))
                    .bufferedReader()
                    .use { it.readText() }
                assertTrue("fixtureJourney" !in source)
                assertTrue("Could we cap the retry window" !in source)
            }
        }
    }
}

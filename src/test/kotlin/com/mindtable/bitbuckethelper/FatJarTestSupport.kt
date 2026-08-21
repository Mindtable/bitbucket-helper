package com.mindtable.bitbuckethelper

import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals

fun locateSingleFatJar(): Path {
    val directory = Path.of(System.getProperty("user.dir"), "build", "libs")
    val jars = if (Files.isDirectory(directory)) {
        Files.list(directory).use { paths ->
            paths.filter { path ->
                Files.isRegularFile(path) && path.fileName.toString().endsWith("-all.jar")
            }.toList()
        }
    } else {
        emptyList()
    }
    assertEquals(1, jars.size, "Expected exactly one *-all.jar under build/libs")
    return jars.single()
}

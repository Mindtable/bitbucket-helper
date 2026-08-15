package com.mindtable.bitbuckethelper.bootstrap

import com.github.ajalt.clikt.testing.test
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class RootCommandTest {
    @Test
    fun `help advertises service run without starting the service`() {
        var starts = 0

        val result = rootCommand { starts += 1 }.test("--help")

        assertEquals(0, result.statusCode)
        assertTrue(result.output.contains("service"))
        assertEquals(0, starts)
    }

    @Test
    fun `service run invokes the injected bootstrap exactly once`() {
        var starts = 0

        val result = rootCommand { starts += 1 }.test("service run")

        assertEquals(0, result.statusCode)
        assertEquals(1, starts)
    }

    @Test
    fun `version is stable`() {
        val result = rootCommand { }.test("--version")

        assertEquals(0, result.statusCode)
        assertTrue(result.output.contains("0.1.0"))
    }
}

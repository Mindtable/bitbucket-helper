package com.mindtable.bitbuckethelper.observability

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SafeExceptionDiagnosticTest {
    @Test
    fun `diagnostic keeps type and frame locations but never exception message`() {
        val failure = IllegalStateException(
            "credential-token https://private.invalid SQL SELECT secret \n ESC\u001b C1\u0085",
        )

        val diagnostic = SafeExceptionDiagnostic.from(failure)

        assertTrue(diagnostic.exceptionTypes.contains("java.lang.IllegalStateException"))
        assertTrue(diagnostic.stackTrace.contains("SafeExceptionDiagnosticTest"))
        assertFalse(diagnostic.stackTrace.contains("credential-token"))
        assertFalse(diagnostic.stackTrace.contains("private.invalid"))
        assertFalse(diagnostic.stackTrace.contains("SELECT secret"))
    }

    @Test
    fun `diagnostic is bounded and marks truncation`() {
        val failure = IllegalStateException("message sentinel")
        repeat(100) { index ->
            failure.addSuppressed(IllegalArgumentException("suppressed-$index"))
        }

        val diagnostic = SafeExceptionDiagnostic.from(failure)

        assertTrue(diagnostic.truncated)
        assertTrue(diagnostic.stackTrace.toByteArray(Charsets.UTF_8).size <= 32 * 1024)
        assertFalse(diagnostic.stackTrace.contains("message sentinel"))
    }
}

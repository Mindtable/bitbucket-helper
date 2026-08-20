package com.mindtable.bitbuckethelper.observability

import com.mindtable.bitbuckethelper.application.model.HealthComponent
import com.mindtable.bitbuckethelper.application.model.SynchronizationFailureCategory
import com.mindtable.bitbuckethelper.application.port.outbound.OperationalEvent
import com.mindtable.bitbuckethelper.domain.shared.NotificationIntentId
import com.mindtable.bitbuckethelper.domain.shared.RefreshRunId
import com.mindtable.bitbuckethelper.domain.shared.RepositoryId
import java.time.Instant
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

    @Test
    fun `diagnostic detects cyclic causes and caps cause depth`() {
        val first = IllegalStateException("cycle-message-sentinel")
        val second = IllegalArgumentException("cycle-message-sentinel")
        first.stackTrace = emptyArray()
        second.stackTrace = emptyArray()
        first.initCause(second)
        second.initCause(first)

        val diagnostic = SafeExceptionDiagnostic.from(first)

        assertTrue(diagnostic.truncated)
        assertTrue(diagnostic.stackTrace.contains("<cycle>"))
        assertFalse(diagnostic.stackTrace.contains("cycle-message-sentinel"))

        val causeDiagnostic = SafeExceptionDiagnostic.from(
            rootFromCauseChain("cause-message-sentinel", 10),
        )
        assertTrue(causeDiagnostic.truncated)
        assertFalse(causeDiagnostic.stackTrace.contains("cause-message-sentinel"))
    }

    @Test
    fun `diagnostic caps suppressed entries and stack frames`() {
        val failure = IllegalStateException("suppressed-message-sentinel")
        repeat(100) { index ->
            val suppressed = IllegalArgumentException("suppressed-message-sentinel-$index")
            suppressed.stackTrace = emptyArray()
            failure.addSuppressed(suppressed)
        }

        val diagnostic = SafeExceptionDiagnostic.from(failure)

        assertTrue(diagnostic.truncated)
        assertTrue(diagnostic.stackTrace.contains("suppressed <truncated>"))
        assertFalse(diagnostic.stackTrace.contains("suppressed-message-sentinel"))

        val frameFailure = IllegalStateException("frame-message-sentinel")
        frameFailure.stackTrace = Array(100) { frame ->
            StackTraceElement("SensitiveFrame", "method$frame", "/private/path/Sensitive.kt", frame)
        }
        val frameDiagnostic = SafeExceptionDiagnostic.from(frameFailure)
        assertTrue(frameDiagnostic.truncated)
        assertTrue(frameDiagnostic.stackTrace.contains("<frames-truncated>"))
        assertFalse(frameDiagnostic.stackTrace.contains("frame-message-sentinel"))
    }

    @Test
    fun `aggregate diagnostic rendering stays within 32 kibibytes`() {
        val diagnostic = SafeExceptionDiagnostic.from(worstCaseFailureGraph(3))
        val rendered = buildString {
            append(diagnostic.exceptionTypes.joinToString("|"))
            append('|')
            append(diagnostic.stackTrace)
            append('|')
            append(diagnostic.truncated)
        }
        val encodedFields = buildString {
            append('[')
            append(diagnostic.exceptionTypes.joinToString(",") { "\"$it\"" })
            append("]\"${escapeTerminalValue(diagnostic.stackTrace)}\"")
            append(diagnostic.truncated)
        }

        assertTrue(diagnostic.truncated)
        assertTrue(rendered.toByteArray(Charsets.UTF_8).size <= 32 * 1024)
        assertTrue(
            encodedFields.toByteArray(Charsets.UTF_8).size <= 32 * 1024,
            "encoded diagnostic bytes=${encodedFields.toByteArray(Charsets.UTF_8).size}",
        )
        assertFalse(rendered.contains("worst-case-message-sentinel"))
    }

    @Test
    fun `failure-bearing operational events redact original messages`() {
        val failure = IllegalStateException("operational-message-sentinel")
        val events = listOf(
            OperationalEvent.HealthProbeFailed(HealthComponent.PERSISTENCE, failure),
            OperationalEvent.RefreshRepositoryFinished(
                refreshRunId = RefreshRunId("rr_test"),
                repositoryId = RepositoryId("repo_test"),
                outcome = com.mindtable.bitbuckethelper.application.port.outbound.RefreshRepositoryOutcome.UNEXPECTED,
                failureCategory = SynchronizationFailureCategory.NETWORK,
                retryable = true,
                retryAt = Instant.EPOCH,
                durationMilliseconds = 3,
                unexpectedFailure = failure,
            ),
            OperationalEvent.NotificationCleanupFailed(NotificationIntentId("ni_test"), failure),
        )

        events.forEach { event ->
            assertFalse(event.toString().contains("operational-message-sentinel"))
        }
    }

    private fun rootFromCauseChain(message: String, length: Int): Throwable {
        val root = IllegalStateException(message)
        root.stackTrace = emptyArray()
        var current: Throwable = root
        repeat(length) {
            val cause = IllegalArgumentException(message)
            cause.stackTrace = emptyArray()
            current.initCause(cause)
            current = cause
        }
        return root
    }

    private fun worstCaseFailureGraph(level: Int): Throwable {
        val failure = IllegalArgumentException("worst-case-message-sentinel")
        failure.stackTrace = emptyArray()
        if (level > 0) {
            repeat(8) { failure.addSuppressed(worstCaseFailureGraph(level - 1)) }
        }
        return failure
    }
}

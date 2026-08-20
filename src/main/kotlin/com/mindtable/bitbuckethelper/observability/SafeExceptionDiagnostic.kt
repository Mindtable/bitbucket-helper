package com.mindtable.bitbuckethelper.observability

import java.nio.charset.StandardCharsets
import java.util.Collections
import java.util.IdentityHashMap

/**
 * A bounded, message-free representation of a failure suitable for logs.
 *
 * Throwable messages are deliberately never read.  In addition to avoiding
 * accidental credential or payload disclosure, doing the conversion here
 * means the original Throwable does not cross the logging API boundary.
 */
data class SafeExceptionDiagnostic(
    val exceptionTypes: List<String>,
    val stackTrace: String,
    val truncated: Boolean,
) {
    companion object {
        private const val MAX_CAUSE_DEPTH = 8
        private const val MAX_SUPPRESSED_PER_LEVEL = 8
        private const val MAX_FRAMES_PER_EXCEPTION = 64
        private const val MAX_STACK_TRACE_BYTES = 32 * 1024

        fun from(failure: Throwable): SafeExceptionDiagnostic {
            val visited = Collections.newSetFromMap(IdentityHashMap<Throwable, Boolean>())
            val exceptionTypes = mutableListOf<String>()
            val output = StringBuilder()
            var truncated = false

            fun append(text: String) {
                var used = output.toString().toByteArray(StandardCharsets.UTF_8).size
                if (used >= MAX_STACK_TRACE_BYTES) {
                    truncated = true
                    return
                }
                for (character in text) {
                    val encodedSize = character.toString().toByteArray(StandardCharsets.UTF_8).size
                    if (used + encodedSize > MAX_STACK_TRACE_BYTES) {
                        truncated = true
                        return
                    }
                    output.append(character)
                    used += encodedSize
                }
            }

            fun appendLine(text: String) {
                append(text)
                append("\n")
            }

            fun visit(
                current: Throwable?,
                depth: Int,
                relation: String,
            ) {
                if (current == null) return
                if (output.toString().toByteArray(StandardCharsets.UTF_8).size >= MAX_STACK_TRACE_BYTES) {
                    truncated = true
                    return
                }
                if (depth >= MAX_CAUSE_DEPTH) {
                    truncated = true
                    appendLine("$relation <truncated>")
                    return
                }
                if (!visited.add(current)) {
                    truncated = true
                    appendLine("$relation <cycle>")
                    return
                }

                val type = current.javaClass.name
                exceptionTypes += type
                appendLine("$relation $type")

                val frames = current.stackTrace
                val frameCount = minOf(frames.size, MAX_FRAMES_PER_EXCEPTION)
                for (index in 0 until frameCount) {
                    val frame = frames[index]
                    appendLine("  at ${frame.className}.${frame.methodName}(${safeFileName(frame.fileName)}:${frame.lineNumber})")
                }
                if (frames.size > MAX_FRAMES_PER_EXCEPTION) {
                    truncated = true
                    appendLine("  <frames-truncated>")
                }

                val suppressed = current.suppressed
                val suppressedCount = minOf(suppressed.size, MAX_SUPPRESSED_PER_LEVEL)
                for (index in 0 until suppressedCount) {
                    visit(suppressed[index], depth + 1, "suppressed[$index]")
                }
                if (suppressed.size > MAX_SUPPRESSED_PER_LEVEL) {
                    truncated = true
                    appendLine("suppressed <truncated>")
                }

                val cause = current.cause
                if (cause != null) visit(cause, depth + 1, "cause")
            }

            visit(failure, 0, "root")
            return SafeExceptionDiagnostic(
                exceptionTypes = exceptionTypes.toList(),
                stackTrace = output.toString(),
                truncated = truncated,
            )
        }

        private fun safeFileName(fileName: String?): String = fileName
            ?.substringAfterLast('/')
            ?.substringAfterLast('\\')
            ?: "?"
    }
}

/** Escapes values before they are rendered by the human-readable appender. */
internal fun escapeTerminalValue(value: String): String = buildString(value.length) {
    value.forEach { character ->
        when {
            character == '\n' -> append("\\n")
            character == '\r' -> append("\\r")
            character == '\u001B' -> append("\\u001B")
            character.code in 0..0x1F || character.code in 0x80..0x9F || character.code == 0x7F -> {
                append("\\u%04X".format(character.code))
            }
            else -> append(character)
        }
    }
}

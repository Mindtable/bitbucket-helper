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
        private const val MAX_DIAGNOSTIC_BYTES = 32 * 1024
        private const val RESERVED_METADATA_BYTES = 2048

        fun from(failure: Throwable): SafeExceptionDiagnostic {
            val visited = Collections.newSetFromMap(IdentityHashMap<Throwable, Boolean>())
            val exceptionTypes = mutableListOf<String>()
            val output = StringBuilder()
            val budget = DiagnosticBudget(MAX_DIAGNOSTIC_BYTES - RESERVED_METADATA_BYTES)

            fun append(text: String) = budget.appendEscaped(text, output)

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
                if (budget.exhausted) {
                    return
                }
                if (depth >= MAX_CAUSE_DEPTH) {
                    budget.markTruncated()
                    appendLine("$relation <truncated>")
                    return
                }
                if (!visited.add(current)) {
                    budget.markTruncated()
                    appendLine("$relation <cycle>")
                    return
                }

                val type = current.javaClass.name
                if (budget.consumeWhole(type, separatorBytes = 4)) {
                    exceptionTypes += type
                }
                appendLine("$relation $type")

                val frames = current.stackTrace
                val frameCount = minOf(frames.size, MAX_FRAMES_PER_EXCEPTION)
                for (index in 0 until frameCount) {
                    val frame = frames[index]
                    appendLine("  at ${frame.className}.${frame.methodName}(${safeFileName(frame.fileName)}:${frame.lineNumber})")
                }
                if (frames.size > MAX_FRAMES_PER_EXCEPTION) {
                    budget.markTruncated()
                    appendLine("  <frames-truncated>")
                }

                val suppressed = current.suppressed
                val suppressedCount = minOf(suppressed.size, MAX_SUPPRESSED_PER_LEVEL)
                for (index in 0 until suppressedCount) {
                    visit(suppressed[index], depth + 1, "suppressed[$index]")
                }
                if (suppressed.size > MAX_SUPPRESSED_PER_LEVEL) {
                    budget.markTruncated()
                    appendLine("suppressed <truncated>")
                }

                val cause = current.cause
                if (cause != null) visit(cause, depth + 1, "cause")
            }

            visit(failure, 0, "root")
            return SafeExceptionDiagnostic(
                exceptionTypes = exceptionTypes.toList(),
                stackTrace = output.toString(),
                truncated = budget.truncated,
            )
        }

        private class DiagnosticBudget(private val limit: Int) {
            var truncated: Boolean = false
                private set
            var usedBytes: Int = 0
                private set
            val exhausted: Boolean get() = usedBytes >= limit

            fun markTruncated() {
                truncated = true
            }

            fun consumeWhole(value: String, separatorBytes: Int): Boolean {
                val bytes = escapedByteSize(value) + separatorBytes
                if (usedBytes + bytes > limit) {
                    truncated = true
                    return false
                }
                usedBytes += bytes
                return true
            }

            fun appendEscaped(value: String, destination: StringBuilder) {
                var index = 0
                while (index < value.length) {
                    val codePoint = value.codePointAt(index)
                    val character = String(Character.toChars(codePoint))
                    val bytes = maxOf(
                        escapeTerminalValue(character)
                            .toByteArray(StandardCharsets.UTF_8)
                            .size,
                        escapeJsonCodePoint(codePoint)
                            .toByteArray(StandardCharsets.UTF_8)
                            .size,
                    )
                    if (usedBytes + bytes > limit) {
                        truncated = true
                        return
                    }
                    destination.append(character)
                    usedBytes += bytes
                    index += Character.charCount(codePoint)
                }
            }

            private fun escapedByteSize(value: String): Int {
                var index = 0
                var total = 0
                while (index < value.length) {
                    val codePoint = value.codePointAt(index)
                    val character = String(Character.toChars(codePoint))
                    total += maxOf(
                        escapeTerminalValue(character)
                            .toByteArray(StandardCharsets.UTF_8)
                            .size,
                        escapeJsonCodePoint(codePoint)
                            .toByteArray(StandardCharsets.UTF_8)
                            .size,
                    )
                    index += Character.charCount(codePoint)
                }
                return total
            }

            private fun escapeJsonCodePoint(codePoint: Int): String = when (codePoint) {
                0x22 -> "\\\""
                0x5C -> "\\\\"
                0x08 -> "\\b"
                0x0C -> "\\f"
                0x0A -> "\\n"
                0x0D -> "\\r"
                0x09 -> "\\t"
                in 0x00..0x1F -> "\\u%04X".format(codePoint)
                in 0x10000..0x10FFFF -> {
                    val pair = Character.toChars(codePoint)
                    "\\u%04X\\u%04X".format(pair[0].code, pair[1].code)
                }
                else -> String(Character.toChars(codePoint))
            }
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

package com.mindtable.bitbuckethelper.cli

import java.io.OutputStream

enum class OutputMode {
    HUMAN,
    JSON,
}

/**
 * A completed command result that has not yet touched a process stream.
 *
 * Command handlers classify their generated API discriminators before creating
 * an [Api] outcome, so this boundary never recomputes business decisions.
 */
sealed interface CliOutcome {
    val exit: CliExit

    class Api<out T> internal constructor(
        val response: LocalApiResponse<T>,
        override val exit: CliExit,
        private val humanRenderer: (TerminalCapability) -> String,
    ) : CliOutcome {
        fun renderHuman(terminal: TerminalCapability): String = humanRenderer(terminal)
    }

    data object ServiceUnavailable : CliOutcome {
        override val exit: CliExit = CliExit.SERVICE_OR_PROTOCOL_FAILURE
    }

    class UnexpectedFailure internal constructor(
        val diagnostic: String,
    ) : CliOutcome {
        override val exit: CliExit = CliExit.UNEXPECTED_FAILURE
    }

    companion object {
        fun <T> api(
            response: LocalApiResponse<T>,
            exit: CliExit = CliExit.SUCCESS,
            humanRenderer: (TerminalCapability) -> String,
        ): Api<T> = Api(
            response = response,
            exit = if (response.error == null) exit else CliExit.SERVICE_OR_PROTOCOL_FAILURE,
            humanRenderer = humanRenderer,
        )

        fun serviceUnavailable(): ServiceUnavailable = ServiceUnavailable

        fun unexpectedFailure(diagnostic: String): UnexpectedFailure = UnexpectedFailure(diagnostic)
    }
}

/** Terminal styling that intentionally becomes plain text for pipes and files. */
class TerminalCapability(
    private val isTerminal: Boolean,
) {
    fun bold(text: String): String = if (isTerminal) "\u001B[1m$text\u001B[0m" else text
}

/** Makes an untrusted API field visible without allowing it to control terminal rendering. */
internal fun String.humanEscaped(): String = buildString(length) {
    this@humanEscaped.forEach { character ->
        when (character) {
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            in '\u0000'..'\u001F', in '\u007F'..'\u009F' -> {
                append("\\u")
                append(character.code.toString(16).uppercase().padStart(4, '0'))
            }
            else -> append(character)
        }
    }
}

/** The only process-stream rendering boundary for product command outcomes. */
class CliOutput(
    private val standardOut: OutputStream = System.out,
    private val standardErr: OutputStream = System.err,
    private val terminal: TerminalCapability = TerminalCapability(System.console() != null),
) {
    fun render(mode: OutputMode, outcome: CliOutcome): CliExit {
        when (outcome) {
            is CliOutcome.Api<*> -> when (mode) {
                OutputMode.JSON -> writeDocument(outcome.response.body)
                OutputMode.HUMAN -> writeLine(standardOut, outcome.renderHuman(terminal))
            }

            CliOutcome.ServiceUnavailable -> when (mode) {
                OutputMode.JSON -> writeDocument(SERVICE_UNAVAILABLE_DOCUMENT)
                OutputMode.HUMAN -> writeLine(standardOut, SERVICE_UNAVAILABLE_MESSAGE)
            }

            is CliOutcome.UnexpectedFailure -> writeLine(standardErr, outcome.diagnostic)
        }
        return outcome.exit
    }

    private fun writeDocument(document: ByteArray) {
        standardOut.write(document)
        standardOut.write(LINE_FEED)
        standardOut.flush()
    }

    private fun writeDocument(document: String) = writeDocument(document.encodeToByteArray())

    private fun writeLine(stream: OutputStream, line: String) {
        stream.write(line.encodeToByteArray())
        stream.write(LINE_FEED)
        stream.flush()
    }

    private companion object {
        val LINE_FEED: ByteArray = byteArrayOf('\n'.code.toByte())
        const val SERVICE_UNAVAILABLE_MESSAGE =
            "Bitbucket Helper service is unavailable. Run 'bitbucket-helper service status' and then 'bitbucket-helper service start'."
        const val SERVICE_UNAVAILABLE_DOCUMENT =
            "{\"cliVersion\":\"1\",\"error\":{\"code\":\"SERVICE_UNAVAILABLE\",\"message\":\"Bitbucket Helper service is unavailable. Run 'bitbucket-helper service status' and then 'bitbucket-helper service start'.\"}}"
    }
}

internal fun <Response> CliOutput.renderApiError(
    mode: OutputMode,
    response: LocalApiResponse<Response>,
): CliExit? = response.error?.let {
    render(
        mode,
        CliOutcome.api(response, CliExit.SERVICE_OR_PROTOCOL_FAILURE) {
            "The local service rejected the request."
        },
    )
}

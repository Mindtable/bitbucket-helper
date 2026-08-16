package com.mindtable.bitbuckethelper.cli

import com.mindtable.bitbuckethelper.generated.api.v1.model.RequestErrorEnvelope
import io.ktor.http.HttpStatusCode
import java.io.ByteArrayOutputStream
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class CliOutputTest {
    @Test
    fun `JSON API result preserves the original bytes and appends one line feed`() {
        val rawDocument =
            "{\"result\":{\"repositoryGroups\":[],\"type\":\"available\"},\"requestId\":\"req_42\",\"apiVersion\":\"1\"}".encodeToByteArray()
        val streams = capturedStreams(isTerminal = false)

        val exit = streams.output.render(
            OutputMode.JSON,
            CliOutcome.api(apiResponse(rawDocument), CliExit.SUCCESS) { "ignored in JSON" },
        )

        assertEquals(CliExit.SUCCESS, exit)
        assertArrayEquals(rawDocument + '\n'.code.toByte(), streams.standardOut.toByteArray())
        assertEquals("", streams.standardErr.toString(Charsets.UTF_8))
    }

    @Test
    fun `JSON typed request error preserves its original field order and keeps stderr empty`() {
        val rawDocument =
            "{\"error\":{\"message\":\"The request is invalid.\",\"code\":\"INVALID_REQUEST\",\"violations\":[]},\"requestId\":\"req_43\",\"apiVersion\":\"1\"}".encodeToByteArray()
        val streams = capturedStreams(isTerminal = false)

        val exit = streams.output.render(
            OutputMode.JSON,
            CliOutcome.api(
                apiResponse(
                    body = rawDocument,
                    status = HttpStatusCode.BadRequest,
                    error = Json.decodeFromString(RequestErrorEnvelope.serializer(), rawDocument.decodeToString()),
                ),
                CliExit.BUSINESS_NOT_ACHIEVED,
            ) { "ignored in JSON" },
        )

        assertEquals(CliExit.BUSINESS_NOT_ACHIEVED, exit)
        assertArrayEquals(rawDocument + '\n'.code.toByte(), streams.standardOut.toByteArray())
        assertEquals("", streams.standardErr.toString(Charsets.UTF_8))
    }

    @Test
    fun `human output goes to stdout and disables ANSI styling for a non-terminal`() {
        val streams = capturedStreams(isTerminal = false)

        val exit = streams.output.render(
            OutputMode.HUMAN,
            CliOutcome.api(apiResponse("{}".encodeToByteArray()), CliExit.SUCCESS) { ansi ->
                "Status: ${ansi.bold("ready")}"
            },
        )

        assertEquals(CliExit.SUCCESS, exit)
        assertEquals("Status: ready\n", streams.standardOut.toString(Charsets.UTF_8))
        assertEquals("", streams.standardErr.toString(Charsets.UTF_8))
    }

    @Test
    fun `human output applies ANSI styling only for a terminal`() {
        val streams = capturedStreams(isTerminal = true)

        streams.output.render(
            OutputMode.HUMAN,
            CliOutcome.api(apiResponse("{}".encodeToByteArray()), CliExit.SUCCESS) { ansi ->
                "Status: ${ansi.bold("ready")}"
            },
        )

        assertEquals("Status: \u001B[1mready\u001B[0m\n", streams.standardOut.toString(Charsets.UTF_8))
        assertEquals("", streams.standardErr.toString(Charsets.UTF_8))
    }

    @Test
    fun `service unavailable JSON output is the fixed local envelope`() {
        val streams = capturedStreams(isTerminal = false)

        val exit = streams.output.render(OutputMode.JSON, CliOutcome.serviceUnavailable())

        assertEquals(CliExit.SERVICE_UNAVAILABLE, exit)
        assertEquals(
            "{\"cliVersion\":\"1\",\"error\":{\"code\":\"SERVICE_UNAVAILABLE\",\"message\":\"Bitbucket Helper service is unavailable. Run 'bitbucket-helper service status' and then 'bitbucket-helper service start'.\"}}\n",
            streams.standardOut.toString(Charsets.UTF_8),
        )
        assertEquals("", streams.standardErr.toString(Charsets.UTF_8))
    }

    @Test
    fun `exit codes are stable`() {
        assertEquals(0, CliExit.SUCCESS.code)
        assertEquals(1, CliExit.UNEXPECTED_FAILURE.code)
        assertEquals(2, CliExit.USAGE_ERROR.code)
        assertEquals(3, CliExit.BUSINESS_NOT_ACHIEVED.code)
        assertEquals(4, CliExit.SERVICE_UNAVAILABLE.code)
    }

    @Test
    fun `unexpected local failures reserve stderr for diagnostics`() {
        val streams = capturedStreams(isTerminal = false)

        val exit = streams.output.render(OutputMode.HUMAN, CliOutcome.unexpectedFailure("Unexpected local failure."))

        assertEquals(CliExit.UNEXPECTED_FAILURE, exit)
        assertEquals("", streams.standardOut.toString(Charsets.UTF_8))
        assertEquals("Unexpected local failure.\n", streams.standardErr.toString(Charsets.UTF_8))
    }

    private fun apiResponse(
        body: ByteArray,
        status: HttpStatusCode = HttpStatusCode.OK,
        error: RequestErrorEnvelope? = null,
    ): LocalApiResponse<Unit> = LocalApiResponse(status, body, Unit, error)

    private fun capturedStreams(isTerminal: Boolean): CapturedStreams {
        val standardOut = ByteArrayOutputStream()
        val standardErr = ByteArrayOutputStream()
        return CapturedStreams(
            standardOut,
            standardErr,
            CliOutput(standardOut, standardErr, TerminalCapability(isTerminal)),
        )
    }

    private data class CapturedStreams(
        val standardOut: ByteArrayOutputStream,
        val standardErr: ByteArrayOutputStream,
        val output: CliOutput,
    )
}

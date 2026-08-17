package com.mindtable.bitbuckethelper.cli

import com.mindtable.bitbuckethelper.generated.api.v1.model.AcknowledgeActionItemResponse
import io.ktor.http.HttpStatusCode
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AcknowledgeCommandTest {
    @Test
    fun `acknowledge maps successful server results and identifies the requested opaque version`() = runBlocking {
        listOf(
            acknowledgedDocument to "Acknowledged action item ai_501 at activity version av_target_7.\n",
            alreadyAcknowledgedDocument to "Action item ai_501 is already acknowledged at activity version av_target_7.\n",
        ).forEach { (document, expectedHuman) ->
            val client = FakeLocalApiClient(response = response(document))
            val streams = capturedStreams()

            val exit = AcknowledgeCommand(client, streams.output).acknowledge("ai_501", "av_target_7", OutputMode.HUMAN)

            assertEquals(CliExit.SUCCESS, exit)
            assertEquals(listOf(PutRequest(ACKNOWLEDGMENT_PATH, requestBody)), client.putRequests)
            assertEquals(expectedHuman, streams.stdout())
            assertEquals("", streams.stderr())
        }
    }

    @Test
    fun `acknowledge maps unsuccessful server discriminators without changing the targeted version`() = runBlocking {
        listOf(
            staleDocument to "Activity version av_target_7 is stale for action item ai_501.\n",
            rejectedDocument to "Action item ai_501 cannot be acknowledged at activity version av_target_7.\n",
            missingDocument to "Action item ai_501 was not found at activity version av_target_7.\n",
        ).forEach { (document, expectedHuman) ->
            val client = FakeLocalApiClient(response = response(document))
            val streams = capturedStreams()

            val exit = AcknowledgeCommand(client, streams.output).acknowledge("ai_501", "av_target_7", OutputMode.HUMAN)

            assertEquals(CliExit.BUSINESS_NOT_ACHIEVED, exit)
            assertEquals(listOf(PutRequest(ACKNOWLEDGMENT_PATH, requestBody)), client.putRequests)
            assertEquals(expectedHuman, streams.stdout())
            assertEquals("", streams.stderr())
        }
    }

    @Test
    fun `acknowledge sends the supplied opaque version in exactly one versioned request`() = runBlocking {
        val client = FakeLocalApiClient(
            response = response(acknowledgedDocument.replace("av_target_7", "av_opaque-7")),
        )
        val streams = capturedStreams()

        val exit = AcknowledgeCommand(client, streams.output).acknowledge("ai_501", "av_opaque-7", OutputMode.HUMAN)

        assertEquals(CliExit.SUCCESS, exit)
        assertEquals(
            listOf(
                PutRequest(
                    "/api/v1/action-items/ai_501/acknowledgment",
                    "{\"apiVersion\":\"1\",\"activityVersion\":\"av_opaque-7\"}",
                ),
            ),
            client.putRequests,
        )
        assertTrue(client.getPaths.isEmpty())
        assertTrue(client.postPaths.isEmpty())
        assertTrue(client.deletePaths.isEmpty())
        assertEquals("Acknowledged action item ai_501 at activity version av_opaque-7.\n", streams.stdout())
        assertEquals("", streams.stderr())
    }

    @Test
    fun `acknowledge rejects either mismatched echoed identity on every result discriminator`() = runBlocking {
        val matchingDocuments = listOf(
            acknowledgedDocument,
            alreadyAcknowledgedDocument,
            staleDocument,
            rejectedDocument,
            missingDocument,
        )

        matchingDocuments.flatMap { document ->
            listOf(
                document.replaceFirst("\"actionItemId\":\"ai_501\"", "\"actionItemId\":\"ai_other\""),
                document.replaceFirst("\"requestedVersion\":\"av_target_7\"", "\"requestedVersion\":\"av_other\""),
            )
        }.forEach { mismatchedDocument ->
            val client = FakeLocalApiClient(response = response(mismatchedDocument))
            val streams = capturedStreams()

            val exit = AcknowledgeCommand(client, streams.output).acknowledge(
                "ai_501",
                "av_target_7",
                OutputMode.HUMAN,
            )

            assertEquals(CliExit.SERVICE_OR_PROTOCOL_FAILURE, exit, mismatchedDocument)
            assertEquals(SERVICE_UNAVAILABLE_MESSAGE, streams.stdout(), mismatchedDocument)
            assertEquals("", streams.stderr(), mismatchedDocument)
        }
    }

    @Test
    fun `acknowledge rejects malformed action identifiers and versions before calling the service`() = runBlocking {
        listOf(
            "ai_501/other" to "av_target_7",
            "ai_501" to "av_target_7/other",
        ).forEach { (actionItemId, activityVersion) ->
            val client = FakeLocalApiClient(response = response(acknowledgedDocument))
            val streams = capturedStreams()

            val exit = AcknowledgeCommand(client, streams.output).acknowledge(actionItemId, activityVersion, OutputMode.HUMAN)

            assertEquals(CliExit.USAGE_ERROR, exit)
            assertTrue(client.putRequests.isEmpty())
            assertTrue(client.getPaths.isEmpty())
            assertEquals("", streams.stdout())
            assertEquals("", streams.stderr())
        }
    }

    @Test
    fun `acknowledge writes successful JSON bytes without reserialization`() = runBlocking {
        val document = "{\"result\":{\"requestedVersion\":\"av_target_7\",\"actionItemId\":\"ai_501\",\"type\":\"alreadyAcknowledged\"},\"requestId\":\"req_json\",\"apiVersion\":\"1\"}".encodeToByteArray()
        val client = FakeLocalApiClient(response = response(document))
        val streams = capturedStreams()

        val exit = AcknowledgeCommand(client, streams.output).acknowledge("ai_501", "av_target_7", OutputMode.JSON)

        assertEquals(CliExit.SUCCESS, exit)
        assertTrue(streams.standardOut.toByteArray().contentEquals(document + '\n'.code.toByte()))
        assertEquals("", streams.stderr())
    }

    @Test
    fun `acknowledge maps non-200 and known protocol failures to exit four`() = runBlocking {
        listOf(
            FakeLocalApiClient(response = response(acknowledgedDocument, HttpStatusCode.Accepted)),
            FakeLocalApiClient(response = response(acknowledgedDocument, HttpStatusCode.Conflict)),
            FakeLocalApiClient(failure = IOException("socket refused")),
            FakeLocalApiClient(failure = LocalApiResponseTooLargeException(128)),
            FakeLocalApiClient(failure = SerializationException("bad response")),
        ).forEach { client ->
            val streams = capturedStreams()

            val exit = AcknowledgeCommand(client, streams.output).acknowledge("ai_501", "av_target_7", OutputMode.HUMAN)

            assertEquals(CliExit.SERVICE_OR_PROTOCOL_FAILURE, exit)
            assertEquals(listOf(PutRequest(ACKNOWLEDGMENT_PATH, requestBody)), client.putRequests)
            assertEquals(SERVICE_UNAVAILABLE_MESSAGE, streams.stdout())
            assertEquals("", streams.stderr())
        }
    }

    @Test
    fun `acknowledge preserves cancellation and unexpected failures`() {
        val cancelled = FakeLocalApiClient(failure = CancellationException("cancelled"))
        assertThrows(CancellationException::class.java) {
            runBlocking {
                AcknowledgeCommand(cancelled, capturedStreams().output).acknowledge("ai_501", "av_target_7", OutputMode.HUMAN)
            }
        }
        assertEquals(listOf(PutRequest(ACKNOWLEDGMENT_PATH, requestBody)), cancelled.putRequests)

        val unexpected = FakeLocalApiClient(failure = IllegalStateException("unexpected"))
        assertThrows(IllegalStateException::class.java) {
            runBlocking {
                AcknowledgeCommand(unexpected, capturedStreams().output).acknowledge("ai_501", "av_target_7", OutputMode.HUMAN)
            }
        }
        assertEquals(listOf(PutRequest(ACKNOWLEDGMENT_PATH, requestBody)), unexpected.putRequests)
    }

    private fun response(document: String, status: HttpStatusCode = HttpStatusCode.OK): RawResponse =
        response(document.encodeToByteArray(), status)

    private fun response(document: ByteArray, status: HttpStatusCode = HttpStatusCode.OK): RawResponse =
        RawResponse(document, status)

    private fun capturedStreams(): CapturedStreams {
        val standardOut = ByteArrayOutputStream()
        val standardErr = ByteArrayOutputStream()
        return CapturedStreams(
            standardOut = standardOut,
            standardErr = standardErr,
            output = CliOutput(standardOut, standardErr, TerminalCapability(false)),
        )
    }

    private class FakeLocalApiClient(
        private val response: RawResponse? = null,
        private val failure: Throwable? = null,
    ) : LocalApiClient {
        val getPaths = mutableListOf<String>()
        val postPaths = mutableListOf<String>()
        val putRequests = mutableListOf<PutRequest>()
        val deletePaths = mutableListOf<String>()

        override suspend fun <Response> get(path: String, responseSerializer: KSerializer<Response>): LocalApiResponse<Response> {
            getPaths += path
            error("Acknowledge command must not GET")
        }

        override suspend fun <Request, Response> post(
            path: String,
            request: Request,
            requestSerializer: KSerializer<Request>,
            responseSerializer: KSerializer<Response>,
        ): LocalApiResponse<Response> {
            postPaths += path
            error("Acknowledge command must not POST")
        }

        override suspend fun <Request, Response> put(
            path: String,
            request: Request,
            requestSerializer: KSerializer<Request>,
            responseSerializer: KSerializer<Response>,
        ): LocalApiResponse<Response> {
            putRequests += PutRequest(path, json.encodeToString(requestSerializer, request))
            failure?.let { throw it }
            val raw = requireNotNull(response) { "No response configured" }
            return LocalApiResponse(
                status = raw.status,
                body = raw.body,
                value = json.decodeFromString(responseSerializer, raw.body.decodeToString()),
                error = null,
            )
        }

        override suspend fun <Response> delete(path: String, responseSerializer: KSerializer<Response>): LocalApiResponse<Response> {
            deletePaths += path
            error("Acknowledge command must not DELETE")
        }

        override fun close() = Unit
    }

    private data class PutRequest(val path: String, val body: String)

    private data class RawResponse(val body: ByteArray, val status: HttpStatusCode)

    private data class CapturedStreams(
        val standardOut: ByteArrayOutputStream,
        val standardErr: ByteArrayOutputStream,
        val output: CliOutput,
    ) {
        fun stdout(): String = standardOut.toString(Charsets.UTF_8)
        fun stderr(): String = standardErr.toString(Charsets.UTF_8)
    }

    private companion object {
        const val ACKNOWLEDGMENT_PATH = "/api/v1/action-items/ai_501/acknowledgment"
        const val requestBody = "{\"apiVersion\":\"1\",\"activityVersion\":\"av_target_7\"}"
        const val SERVICE_UNAVAILABLE_MESSAGE =
            "Bitbucket Helper service is unavailable. Run 'bitbucket-helper service status' and then 'bitbucket-helper service start'.\n"

        val json = Json { explicitNulls = true }

        const val acknowledgedDocument =
            "{\"apiVersion\":\"1\",\"requestId\":\"req_ack\",\"result\":{\"type\":\"acknowledged\",\"actionItemId\":\"ai_501\",\"requestedVersion\":\"av_target_7\",\"acknowledgedAt\":\"2026-08-15T10:02:00Z\"}}"
        const val alreadyAcknowledgedDocument =
            "{\"apiVersion\":\"1\",\"requestId\":\"req_already\",\"result\":{\"type\":\"alreadyAcknowledged\",\"actionItemId\":\"ai_501\",\"requestedVersion\":\"av_target_7\"}}"
        const val staleDocument =
            "{\"apiVersion\":\"1\",\"requestId\":\"req_stale\",\"result\":{\"type\":\"staleActivityVersion\",\"actionItemId\":\"ai_501\",\"requestedVersion\":\"av_target_7\",\"hasNewerActivity\":true,\"current\":{\"actionItemId\":\"ai_501\",\"pullRequestId\":\"pr_184\",\"repositoryId\":\"repo_payments\",\"repositoryDisplayName\":\"Payments API\",\"pullRequestNumber\":184,\"pullRequestTitle\":\"Keep wire order\",\"activityVersion\":\"av_current_8\",\"kind\":\"review-requested\",\"actor\":{\"stableId\":\"user_ada\",\"displayName\":\"Ada Lovelace\"},\"activityAt\":\"2026-08-15T10:02:00Z\",\"state\":\"open\",\"acknowledgedAt\":null,\"webUrl\":\"https://bitbucket.org/mindtable/payments-api/pull-requests/184#activity-501\"}}}"
        const val rejectedDocument =
            "{\"apiVersion\":\"1\",\"requestId\":\"req_rejected\",\"result\":{\"type\":\"acknowledgmentRejected\",\"actionItemId\":\"ai_501\",\"requestedVersion\":\"av_target_7\"}}"
        const val missingDocument =
            "{\"apiVersion\":\"1\",\"requestId\":\"req_missing\",\"result\":{\"type\":\"actionItemNotFound\",\"actionItemId\":\"ai_501\",\"requestedVersion\":\"av_target_7\"}}"
    }
}

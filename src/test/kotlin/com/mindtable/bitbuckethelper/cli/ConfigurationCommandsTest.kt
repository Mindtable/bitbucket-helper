package com.mindtable.bitbuckethelper.cli

import io.ktor.http.HttpStatusCode
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ConfigurationCommandsTest {
    @Test
    fun `workspace show renders the configured workspace supplied by the API`() = runBlocking {
        val client = FakeLocalApiClient(listOf(response(workspaceAvailableDocument)))
        val streams = capturedStreams()

        val exit = WorkspaceCommands(client, streams.output).show(OutputMode.HUMAN)

        assertEquals(CliExit.SUCCESS, exit)
        assertEquals(listOf(ClientCall("GET", WORKSPACE_PATH)), client.calls)
        assertTrue(streams.stdout().contains("Workspace: Mindtable"))
        assertTrue(streams.stdout().contains("ID: ws_mindtable"))
        assertTrue(streams.stdout().contains("API base URL: https://api.bitbucket.org/2.0"))
        assertTrue(streams.stdout().contains("Repository: Payments API (repo_payments)"))
        assertEquals("", streams.stderr())
    }

    @Test
    fun `workspace show reports an unconfigured workspace as a business outcome`() = runBlocking {
        val client = FakeLocalApiClient(listOf(response(workspaceNotConfiguredDocument)))
        val streams = capturedStreams()

        val exit = WorkspaceCommands(client, streams.output).show(OutputMode.HUMAN)

        assertEquals(CliExit.BUSINESS_NOT_ACHIEVED, exit)
        assertEquals(listOf(ClientCall("GET", WORKSPACE_PATH)), client.calls)
        assertEquals("Workspace is not configured. Run bitbucket-helper workspace configure.\n", streams.stdout())
        assertEquals("", streams.stderr())
    }

    @Test
    fun `workspace show rejects an unrecognized generated setup command without leaking its payload`() = runBlocking {
        val maliciousPayload = "BITBUCKET_APP_PASSWORD=secret activity content upstream diagnostic"
        val document = """{"apiVersion":"1","requestId":"req_malicious","result":{"type":"workspaceNotConfigured","setupCommand":"$maliciousPayload"}}"""
        val client = FakeLocalApiClient(listOf(response(document)))
        val streams = capturedStreams()

        val exit = WorkspaceCommands(client, streams.output).show(OutputMode.HUMAN)

        assertEquals(CliExit.SERVICE_OR_PROTOCOL_FAILURE, exit)
        assertEquals(listOf(ClientCall("GET", WORKSPACE_PATH)), client.calls)
        assertEquals(SERVICE_UNAVAILABLE_MESSAGE, streams.stdout())
        assertFalse(streams.stdout().contains("BITBUCKET_APP_PASSWORD"))
        assertFalse(streams.stdout().contains("activity content"))
        assertFalse(streams.stdout().contains("upstream diagnostic"))
        assertEquals("", streams.stderr())
    }

    @Test
    fun `workspace configure sends exactly the supplied API base URL and slug`() = runBlocking {
        val client = FakeLocalApiClient(listOf(response(workspaceConfiguredDocument)))
        val streams = capturedStreams()

        val exit = WorkspaceCommands(client, streams.output).configure(
            apiBaseUrl = "https://api.bitbucket.org/2.0",
            slug = "mindtable",
            mode = OutputMode.HUMAN,
        )

        assertEquals(CliExit.SUCCESS, exit)
        assertEquals(
            listOf(
                ClientCall(
                    "PUT",
                    WORKSPACE_PATH,
                    "{\"apiVersion\":\"1\",\"bitbucketApiBaseUrl\":\"https://api.bitbucket.org/2.0\",\"workspaceSlug\":\"mindtable\"}",
                ),
            ),
            client.calls,
        )
        assertEquals("Workspace Mindtable configured.\n", streams.stdout())
        assertEquals("", streams.stderr())
    }

    @Test
    fun `workspace configure treats an identical immutable identity as successful`() = runBlocking {
        val client = FakeLocalApiClient(listOf(response(workspaceAlreadyConfiguredDocument)))
        val streams = capturedStreams()

        val exit = WorkspaceCommands(client, streams.output).configure(
            "https://api.bitbucket.org/2.0",
            "mindtable",
            OutputMode.HUMAN,
        )

        assertEquals(CliExit.SUCCESS, exit)
        assertEquals("Workspace Mindtable is already configured.\n", streams.stdout())
    }

    @Test
    fun `workspace configure gives safe immutable identity guidance`() = runBlocking {
        val client = FakeLocalApiClient(listOf(response(workspaceIdentityMismatchDocument)))
        val streams = capturedStreams()

        val exit = WorkspaceCommands(client, streams.output).configure(
            "https://api.bitbucket.org/2.0",
            "other-workspace",
            OutputMode.HUMAN,
        )

        assertEquals(CliExit.BUSINESS_NOT_ACHIEVED, exit)
        assertTrue(streams.stdout().contains("cannot be changed in place"))
        assertTrue(streams.stdout().contains("ws_mindtable"))
        assertFalse(streams.stdout().contains("BITBUCKET_"))
        assertFalse(streams.stdout().contains("upstream"))
        assertEquals("", streams.stderr())
    }

    @Test
    fun `workspace configure maps typed missing and unavailable results to business non achievement without upstream detail`() = runBlocking {
        listOf(
            workspaceNotFoundDocument to "Workspace was not found.\n",
            workspaceResolutionUnavailableDocument to "Workspace could not be resolved right now. Try again later.\n",
        ).forEach { (document, expectedHuman) ->
            val client = FakeLocalApiClient(listOf(response(document)))
            val streams = capturedStreams()

            val exit = WorkspaceCommands(client, streams.output).configure(
                "https://api.bitbucket.org/2.0",
                "mindtable",
                OutputMode.HUMAN,
            )

            assertEquals(CliExit.BUSINESS_NOT_ACHIEVED, exit)
            assertEquals(expectedHuman, streams.stdout())
            assertFalse(streams.stdout().contains("server diagnostic"))
            assertFalse(streams.stdout().contains("BITBUCKET_"))
            assertEquals("", streams.stderr())
        }
    }

    @Test
    fun `repository add maps every generated result to its command exit`() = runBlocking {
        listOf(
            repositoryAddedDocument to CliExit.SUCCESS,
            repositoryAlreadyConfiguredDocument to CliExit.SUCCESS,
            repositoryNotFoundDocument to CliExit.BUSINESS_NOT_ACHIEVED,
            repositoryResolutionUnavailableDocument to CliExit.BUSINESS_NOT_ACHIEVED,
            workspaceNotConfiguredDocument to CliExit.BUSINESS_NOT_ACHIEVED,
        ).forEach { (document, expectedExit) ->
            val client = FakeLocalApiClient(listOf(response(document)))
            val streams = capturedStreams()

            val exit = RepositoryCommands(client, streams.output).add("payments-api", OutputMode.HUMAN)

            assertEquals(expectedExit, exit)
            assertEquals(
                listOf(ClientCall("POST", REPOSITORIES_PATH, "{\"apiVersion\":\"1\",\"repositorySlug\":\"payments-api\"}")),
                client.calls,
            )
            assertFalse(streams.stdout().contains("BITBUCKET_"))
            assertFalse(streams.stdout().contains("activity content"))
            assertEquals("", streams.stderr())
        }
    }

    @Test
    fun `repository remove maps every generated result to its command exit`() = runBlocking {
        listOf(
            repositoryRemovedDocument to CliExit.SUCCESS,
            repositoryNotConfiguredDocument to CliExit.BUSINESS_NOT_ACHIEVED,
        ).forEach { (document, expectedExit) ->
            val client = FakeLocalApiClient(listOf(response(document)))
            val streams = capturedStreams()

            val exit = RepositoryCommands(client, streams.output).remove("repo_payments", OutputMode.HUMAN)

            assertEquals(expectedExit, exit)
            assertEquals(listOf(ClientCall("DELETE", "$REPOSITORIES_PATH/repo_payments")), client.calls)
            assertEquals("", streams.stderr())
        }
    }

    @Test
    fun `configuration JSON output is exactly one original response document`() = runBlocking {
        val original = workspaceIdentityMismatchDocument.encodeToByteArray()
        val client = FakeLocalApiClient(listOf(response(original)))
        val streams = capturedStreams()

        val exit = WorkspaceCommands(client, streams.output).configure(
            "https://api.bitbucket.org/2.0",
            "other-workspace",
            OutputMode.JSON,
        )

        assertEquals(CliExit.BUSINESS_NOT_ACHIEVED, exit)
        assertTrue(streams.standardOut.toByteArray().contentEquals(original + '\n'.code.toByte()))
        assertEquals("", streams.stderr())
    }

    @Test
    fun `configuration rejects malformed URL slugs and opaque repository ids before calling the service`() = runBlocking {
        listOf<suspend () -> CliExit>(
            { WorkspaceCommands(FakeLocalApiClient(), capturedStreams().output).configure("not a URL", "mindtable", OutputMode.HUMAN) },
            { WorkspaceCommands(FakeLocalApiClient(), capturedStreams().output).configure("https://api.bitbucket.org/2.0?token=value", "mindtable", OutputMode.HUMAN) },
            { WorkspaceCommands(FakeLocalApiClient(), capturedStreams().output).configure("https://api.bitbucket.org/2.0", "wrong/slug", OutputMode.HUMAN) },
            { RepositoryCommands(FakeLocalApiClient(), capturedStreams().output).add("wrong/slug", OutputMode.HUMAN) },
            { RepositoryCommands(FakeLocalApiClient(), capturedStreams().output).remove("repo_payments/other", OutputMode.HUMAN) },
        ).forEach { invoke ->
            assertEquals(CliExit.USAGE_ERROR, invoke())
        }
    }

    @Test
    fun `configuration accepts only exact HTTP 200`() = runBlocking {
        listOf(HttpStatusCode.Accepted, HttpStatusCode.Conflict).forEach { status ->
            val client = FakeLocalApiClient(listOf(response(repositoryAddedDocument, status)))
            val streams = capturedStreams()

            val exit = RepositoryCommands(client, streams.output).add("payments-api", OutputMode.HUMAN)

            assertEquals(CliExit.SERVICE_OR_PROTOCOL_FAILURE, exit, "HTTP ${status.value}")
            assertEquals(SERVICE_UNAVAILABLE_MESSAGE, streams.stdout())
            assertEquals("", streams.stderr())
        }
    }

    @Test
    fun `configuration classifies known transport and decode failures while preserving cancellation and unexpected failures`() {
        listOf(
            IOException("socket refused"),
            LocalApiResponseTooLargeException(128),
            SerializationException("malformed response"),
        ).forEach { failure ->
            val streams = capturedStreams()

            val exit = runBlocking {
                WorkspaceCommands(FakeLocalApiClient(failure = failure), streams.output).show(OutputMode.HUMAN)
            }

            assertEquals(CliExit.SERVICE_OR_PROTOCOL_FAILURE, exit)
            assertEquals(SERVICE_UNAVAILABLE_MESSAGE, streams.stdout())
            assertEquals("", streams.stderr())
        }

        assertThrows(CancellationException::class.java) {
            runBlocking {
                RepositoryCommands(FakeLocalApiClient(failure = CancellationException("cancelled")), capturedStreams().output)
                    .add("payments-api", OutputMode.HUMAN)
            }
        }
        assertThrows(IllegalStateException::class.java) {
            runBlocking {
                RepositoryCommands(FakeLocalApiClient(failure = IllegalStateException("unexpected")), capturedStreams().output)
                    .add("payments-api", OutputMode.HUMAN)
            }
        }
    }

    private fun response(document: String, status: HttpStatusCode = HttpStatusCode.OK): RawResponse =
        response(document.encodeToByteArray(), status)

    private fun response(document: ByteArray, status: HttpStatusCode = HttpStatusCode.OK): RawResponse =
        RawResponse(document, status)

    private fun capturedStreams(): CapturedStreams {
        val standardOut = ByteArrayOutputStream()
        val standardErr = ByteArrayOutputStream()
        return CapturedStreams(standardOut, standardErr, CliOutput(standardOut, standardErr, TerminalCapability(false)))
    }

    private class FakeLocalApiClient(
        private val responses: List<RawResponse> = emptyList(),
        private val failure: Throwable? = null,
    ) : LocalApiClient {
        val calls = mutableListOf<ClientCall>()
        private var nextResponse = 0

        override suspend fun <Response> get(path: String, responseSerializer: KSerializer<Response>): LocalApiResponse<Response> =
            respond(ClientCall("GET", path), responseSerializer)

        override suspend fun <Request, Response> post(
            path: String,
            request: Request,
            requestSerializer: KSerializer<Request>,
            responseSerializer: KSerializer<Response>,
        ): LocalApiResponse<Response> = respond(
            ClientCall("POST", path, json.encodeToString(requestSerializer, request)),
            responseSerializer,
        )

        override suspend fun <Request, Response> put(
            path: String,
            request: Request,
            requestSerializer: KSerializer<Request>,
            responseSerializer: KSerializer<Response>,
        ): LocalApiResponse<Response> = respond(
            ClientCall("PUT", path, json.encodeToString(requestSerializer, request)),
            responseSerializer,
        )

        override suspend fun <Response> delete(path: String, responseSerializer: KSerializer<Response>): LocalApiResponse<Response> =
            respond(ClientCall("DELETE", path), responseSerializer)

        override fun close() = Unit

        private fun <Response> respond(
            call: ClientCall,
            serializer: KSerializer<Response>,
        ): LocalApiResponse<Response> {
            calls += call
            failure?.let { throw it }
            val raw = responses.getOrNull(nextResponse++) ?: error("No response configured")
            return LocalApiResponse(
                status = raw.status,
                body = raw.body,
                value = json.decodeFromString(serializer, raw.body.decodeToString()),
                error = null,
            )
        }
    }

    private data class ClientCall(val method: String, val path: String, val body: String? = null)
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
        const val WORKSPACE_PATH = "/api/v1/configuration/workspace"
        const val REPOSITORIES_PATH = "/api/v1/configuration/workspace/repositories"
        const val SERVICE_UNAVAILABLE_MESSAGE =
            "Bitbucket Helper service is unavailable. Run 'bitbucket-helper service status' and then 'bitbucket-helper service start'.\n"

        val json = Json { explicitNulls = true }
        const val configuration = """{"workspaceId":"ws_mindtable","bitbucketApiBaseUrl":"https://api.bitbucket.org/2.0","workspaceSlug":"mindtable","workspaceDisplayName":"Mindtable","workspaceWebUrl":"https://bitbucket.org/mindtable","retentionDays":30,"repositories":[{"repositoryId":"repo_payments","slug":"payments-api","displayName":"Payments API","webUrl":"https://bitbucket.org/mindtable/payments-api"}]}"""
        const val repository = """{"repositoryId":"repo_payments","slug":"payments-api","displayName":"Payments API","webUrl":"https://bitbucket.org/mindtable/payments-api"}"""
        const val workspaceAvailableDocument = """{"apiVersion":"1","requestId":"req_show","result":{"type":"workspaceConfigured","configuration":$configuration}}"""
        const val workspaceConfiguredDocument = """{"apiVersion":"1","requestId":"req_configured","result":{"type":"workspaceConfigured","configuration":$configuration}}"""
        const val workspaceAlreadyConfiguredDocument = """{"apiVersion":"1","requestId":"req_already","result":{"type":"workspaceAlreadyConfigured","configuration":$configuration}}"""
        const val workspaceIdentityMismatchDocument = """{"apiVersion":"1","requestId":"req_mismatch","result":{"type":"workspaceIdentityMismatch","current":$configuration}}"""
        const val workspaceNotFoundDocument = """{"apiVersion":"1","requestId":"req_missing","result":{"type":"workspaceNotFound"}}"""
        const val workspaceResolutionUnavailableDocument = """{"apiVersion":"1","requestId":"req_unavailable","result":{"type":"workspaceResolutionUnavailable","failure":{"category":"upstream","retryable":true,"retryAt":null}}}"""
        const val workspaceNotConfiguredDocument = """{"apiVersion":"1","requestId":"req_unconfigured","result":{"type":"workspaceNotConfigured","setupCommand":"bitbucket-helper workspace configure"}}"""
        const val repositoryAddedDocument = """{"apiVersion":"1","requestId":"req_added","result":{"type":"repositoryAdded","repository":$repository}}"""
        const val repositoryAlreadyConfiguredDocument = """{"apiVersion":"1","requestId":"req_already","result":{"type":"repositoryAlreadyConfigured","repository":$repository}}"""
        const val repositoryNotFoundDocument = """{"apiVersion":"1","requestId":"req_missing","result":{"type":"repositoryNotFound"}}"""
        const val repositoryResolutionUnavailableDocument = """{"apiVersion":"1","requestId":"req_unavailable","result":{"type":"repositoryResolutionUnavailable","failure":{"category":"upstream","retryable":true,"retryAt":null}}}"""
        const val repositoryRemovedDocument = """{"apiVersion":"1","requestId":"req_removed","result":{"type":"repositoryRemoved","repositoryId":"repo_payments"}}"""
        const val repositoryNotConfiguredDocument = """{"apiVersion":"1","requestId":"req_not_configured","result":{"type":"repositoryNotConfigured","repositoryId":"repo_payments"}}"""
    }
}

package com.mindtable.bitbuckethelper.cli

import com.mindtable.bitbuckethelper.generated.api.v1.model.PullRequestDetailResponse
import io.ktor.http.HttpStatusCode
import java.io.ByteArrayOutputStream
import java.io.ByteArrayInputStream
import java.io.IOException
import java.time.Duration
import java.util.concurrent.CancellationException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class OpenCommandTest {
    @Test
    fun `open fetches the PR once and opens one valid Bitbucket HTTPS URL`() = runBlocking {
        val client = FakeLocalApiClient(response = response(found("https://bitbucket.org/mindtable/payments-api/pull-requests/184")))
        val opener = FakeOpenUrl()
        val streams = capturedStreams()

        val exit = OpenCommand(client, streams.output, opener).open("pr_184", OutputMode.HUMAN)

        assertEquals(CliExit.SUCCESS, exit)
        assertEquals(listOf("/api/v1/pull-requests/pr_184"), client.getPaths)
        assertEquals(listOf("https://bitbucket.org/mindtable/payments-api/pull-requests/184"), opener.urls)
        assertEquals("Opened pull request pr_184.\n", streams.stdout())
        assertEquals("", streams.stderr())
    }

    @Test
    fun `open does not invoke the opener for non-Bitbucket or malformed links`() = runBlocking {
        listOf(
            "http://bitbucket.org/mindtable/payments-api/pull-requests/184",
            "https://example.test/mindtable/payments-api/pull-requests/184",
            "https://bitbucket.org@evil.test/pull-requests/184",
            "https://bitbucket.org/a path",
        ).forEach { link ->
            val client = FakeLocalApiClient(response = response(found(link)))
            val opener = FakeOpenUrl()
            val streams = capturedStreams()

            val exit = OpenCommand(client, streams.output, opener).open("pr_184", OutputMode.HUMAN)

            assertEquals(CliExit.BUSINESS_NOT_ACHIEVED, exit, link)
            assertEquals(listOf("/api/v1/pull-requests/pr_184"), client.getPaths, link)
            assertTrue(opener.urls.isEmpty(), link)
            assertEquals("Pull request pr_184 has no safe Bitbucket HTTPS link.\n", streams.stdout(), link)
            assertEquals("", streams.stderr(), link)
        }
    }

    @Test
    fun `open does not invoke the opener for typed not-found or unavailable results`() = runBlocking {
        listOf(
            """{"apiVersion":"1","requestId":"req_missing","result":{"type":"pullRequestNotFound","pullRequestId":"pr_184"}}""" to
                "Pull request pr_184 was not found.\n",
            """{"apiVersion":"1","requestId":"req_unavailable","result":{"type":"workspaceNotConfigured","setupCommand":"bitbucket-helper workspace configure"}}""" to
                "Workspace is not configured. Run bitbucket-helper workspace configure.\n",
        ).forEach { (document, expectedOutput) ->
            val client = FakeLocalApiClient(response = response(document))
            val opener = FakeOpenUrl()
            val streams = capturedStreams()

            val exit = OpenCommand(client, streams.output, opener).open("pr_184", OutputMode.HUMAN)

            assertEquals(CliExit.BUSINESS_NOT_ACHIEVED, exit)
            assertEquals(listOf("/api/v1/pull-requests/pr_184"), client.getPaths)
            assertTrue(opener.urls.isEmpty())
            assertEquals(expectedOutput, streams.stdout())
            assertEquals("", streams.stderr())
        }
    }

    @Test
    fun `open rejects mismatched found and not found identities before invoking opener`() = runBlocking {
        listOf(
            found("https://bitbucket.org/mindtable/payments-api/pull-requests/999", "pr_999"),
            """{"apiVersion":"1","requestId":"req_missing","result":{"type":"pullRequestNotFound","pullRequestId":"pr_999"}}""",
        ).forEach { document ->
            val client = FakeLocalApiClient(response = response(document))
            val opener = FakeOpenUrl()
            val streams = capturedStreams()

            val exit = OpenCommand(client, streams.output, opener).open("pr_184", OutputMode.HUMAN)

            assertEquals(CliExit.SERVICE_OR_PROTOCOL_FAILURE, exit, document)
            assertTrue(opener.urls.isEmpty(), document)
            assertEquals(SERVICE_UNAVAILABLE_MESSAGE, streams.stdout(), document)
            assertEquals("", streams.stderr(), document)
        }
    }

    @Test
    fun `open treats non-200 and known protocol failures as unavailable without invoking opener`() = runBlocking {
        val successfulDocument = found("https://bitbucket.org/mindtable/payments-api/pull-requests/184")
        val failures = listOf(
            FakeLocalApiClient(response = response(successfulDocument, HttpStatusCode.Accepted)),
            FakeLocalApiClient(failure = IOException("socket refused")),
            FakeLocalApiClient(failure = LocalApiResponseTooLargeException(128)),
            FakeLocalApiClient(failure = SerializationException("bad response")),
        )

        failures.forEach { client ->
            val opener = FakeOpenUrl()
            val streams = capturedStreams()

            val exit = OpenCommand(client, streams.output, opener).open("pr_184", OutputMode.HUMAN)

            assertEquals(CliExit.SERVICE_OR_PROTOCOL_FAILURE, exit)
            assertEquals(listOf("/api/v1/pull-requests/pr_184"), client.getPaths)
            assertTrue(opener.urls.isEmpty())
            assertEquals(SERVICE_UNAVAILABLE_MESSAGE, streams.stdout())
            assertEquals("", streams.stderr())
        }
    }

    @Test
    fun `open rejects an invalid pull request ID before any side effect`() = runBlocking {
        val client = FakeLocalApiClient()
        val opener = FakeOpenUrl()
        val streams = capturedStreams()

        val exit = OpenCommand(client, streams.output, opener).open("pr_184/other", OutputMode.HUMAN)

        assertEquals(CliExit.USAGE_ERROR, exit)
        assertTrue(client.getPaths.isEmpty())
        assertTrue(opener.urls.isEmpty())
        assertEquals("", streams.stdout())
        assertEquals("", streams.stderr())
    }

    @Test
    fun `open reports one fixed local failure when opening fails`() = runBlocking {
        val client = FakeLocalApiClient(response = response(found("https://bitbucket.org/mindtable/payments-api/pull-requests/184")))
        val opener = FakeOpenUrl(result = false)
        val streams = capturedStreams()

        val exit = OpenCommand(client, streams.output, opener).open("pr_184", OutputMode.HUMAN)

        assertEquals(CliExit.UNEXPECTED_FAILURE, exit)
        assertEquals(listOf("/api/v1/pull-requests/pr_184"), client.getPaths)
        assertEquals(listOf("https://bitbucket.org/mindtable/payments-api/pull-requests/184"), opener.urls)
        assertEquals("", streams.stdout())
        assertEquals("Unable to open pull request in the browser.\n", streams.stderr())
    }

    @Test
    fun `open preserves cancellation without invoking the opener`() {
        val client = FakeLocalApiClient(failure = CancellationException("cancelled"))
        val opener = FakeOpenUrl()

        assertThrows(CancellationException::class.java) {
            runBlocking {
                OpenCommand(client, capturedStreams().output, opener).open("pr_184", OutputMode.HUMAN)
            }
        }
        assertEquals(listOf("/api/v1/pull-requests/pr_184"), client.getPaths)
        assertTrue(opener.urls.isEmpty())
    }

    @Test
    fun `macOS opener passes metacharacters as one argv element and waits with a bound`() {
        val process = FakeProcess(completed = true, exitCode = 0)
        val commands = mutableListOf<List<String>>()
        val url = "https://bitbucket.org/mindtable/a%20repo/pull-requests/184?note=one;two&next=$(whoami)"
        val opener = MacOsOpenUrl(
            processStarter = ProcessStarter { command ->
                commands += command
                process
            },
            waitTimeout = Duration.ofMillis(17),
        )

        assertTrue(opener.open(url))
        assertEquals(listOf(listOf("/usr/bin/open", url)), commands)
        assertEquals(17, process.waitTimeoutMillis)
        assertEquals(1, process.waitCalls)
        assertFalse(process.destroyed)
        assertFalse(process.forciblyDestroyed)
        assertTrue(process.stdin.closed)
        assertTrue(process.stdout.closed)
        assertTrue(process.stderr.closed)
    }

    @Test
    fun `macOS opener fails safely and terminates a process that exceeds its bound`() {
        val process = FakeProcess(completed = false, exitCode = 0)
        val opener = MacOsOpenUrl(
            processStarter = ProcessStarter { process },
            waitTimeout = Duration.ofMillis(17),
        )

        assertFalse(opener.open("https://bitbucket.org/mindtable/payments-api/pull-requests/184"))
        assertEquals(listOf(17L, 100L), process.waitTimeoutsMillis)
        assertTrue(process.forciblyDestroyed)
        assertTrue(process.stdin.closed)
        assertTrue(process.stdout.closed)
        assertTrue(process.stderr.closed)
    }

    @Test
    fun `macOS opener rejects a completed process with a nonzero exit code`() {
        val process = FakeProcess(completed = true, exitCode = 1)
        val opener = MacOsOpenUrl(
            processStarter = ProcessStarter { process },
            waitTimeout = Duration.ofMillis(17),
        )

        assertFalse(opener.open("https://bitbucket.org/mindtable/payments-api/pull-requests/184"))
        assertEquals(1, process.waitCalls)
        assertFalse(process.forciblyDestroyed)
    }

    @Test
    fun `macOS opener preserves interruption while closing and forcibly reaping the child`() {
        val process = FakeProcess(completed = false, exitCode = 0, interruptOnFirstWait = true)
        val opener = MacOsOpenUrl(
            processStarter = ProcessStarter { process },
            waitTimeout = Duration.ofMillis(17),
        )

        try {
            assertFalse(opener.open("https://bitbucket.org/mindtable/payments-api/pull-requests/184"))
            assertTrue(Thread.currentThread().isInterrupted)
            assertTrue(process.forciblyDestroyed)
            assertTrue(process.stdin.closed)
            assertTrue(process.stdout.closed)
            assertTrue(process.stderr.closed)
            assertEquals(listOf(17L, 100L), process.waitTimeoutsMillis)
        } finally {
            Thread.interrupted()
        }
    }

    private fun found(url: String, pullRequestId: String = "pr_184"): String = """
        {"apiVersion":"1","requestId":"req_detail","result":{"type":"pullRequestFound","pullRequest":{"pullRequest":{"pullRequestId":"$pullRequestId","repositoryId":"repo_payments","upstreamNumber":184,"title":"Keep wire order","author":{"stableId":"user_ada","displayName":"Ada Lovelace"},"draft":false,"createdAt":"2026-08-15T09:00:00Z","updatedAt":"2026-08-15T10:00:00Z","webUrl":"$url","readiness":{"type":"unavailable","safeReason":"Unavailable."},"buildState":"unknown","actionableItemCount":0,"acknowledgedItemCount":0,"actionItems":[]},"headCommit":"0123456789abcdef","builds":[],"freshness":{"type":"neverSynchronized"}}}}
    """.trimIndent()

    private fun response(document: String, status: HttpStatusCode = HttpStatusCode.OK): RawResponse =
        RawResponse(document.encodeToByteArray(), status)

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

        override suspend fun <Response> get(
            path: String,
            responseSerializer: KSerializer<Response>,
        ): LocalApiResponse<Response> {
            getPaths += path
            failure?.let { throw it }
            val raw = requireNotNull(response) { "No response configured" }
            return LocalApiResponse(
                status = raw.status,
                body = raw.body,
                value = json.decodeFromString(responseSerializer, raw.body.decodeToString()),
                error = null,
            )
        }

        override suspend fun <Request, Response> post(
            path: String,
            request: Request,
            requestSerializer: KSerializer<Request>,
            responseSerializer: KSerializer<Response>,
        ): LocalApiResponse<Response> = error("Open command fake does not support POST")

        override suspend fun <Request, Response> put(
            path: String,
            request: Request,
            requestSerializer: KSerializer<Request>,
            responseSerializer: KSerializer<Response>,
        ): LocalApiResponse<Response> = error("Open command fake does not support PUT")

        override suspend fun <Response> delete(
            path: String,
            responseSerializer: KSerializer<Response>,
        ): LocalApiResponse<Response> = error("Open command fake does not support DELETE")

        override fun close() = Unit
    }

    private class FakeOpenUrl(
        private val result: Boolean = true,
    ) : OpenUrl {
        val urls = mutableListOf<String>()

        override fun open(url: String): Boolean {
            urls += url
            return result
        }
    }

    private class FakeProcess(
        private val completed: Boolean,
        private val exitCode: Int,
        private val interruptOnFirstWait: Boolean = false,
    ) : Process() {
        var waitTimeoutMillis: Long? = null
        val waitTimeoutsMillis = mutableListOf<Long>()
        var waitCalls = 0
        var destroyed = false
        var forciblyDestroyed = false
        val stdin = TrackingOutputStream()
        val stdout = TrackingInputStream()
        val stderr = TrackingInputStream()

        override fun getOutputStream() = stdin

        override fun getInputStream() = stdout

        override fun getErrorStream() = stderr

        override fun waitFor(): Int = exitCode

        override fun waitFor(timeout: Long, unit: TimeUnit): Boolean {
            waitCalls += 1
            waitTimeoutMillis = unit.toMillis(timeout)
            waitTimeoutsMillis += unit.toMillis(timeout)
            if (interruptOnFirstWait && waitCalls == 1) throw InterruptedException("test interruption")
            return completed
        }

        override fun exitValue(): Int = exitCode

        override fun destroy() {
            destroyed = true
        }

        override fun destroyForcibly(): Process {
            forciblyDestroyed = true
            return this
        }

        override fun isAlive(): Boolean = !completed && !forciblyDestroyed
    }

    private class TrackingOutputStream : ByteArrayOutputStream() {
        var closed = false
            private set

        override fun close() {
            closed = true
            super.close()
        }
    }

    private class TrackingInputStream : ByteArrayInputStream(byteArrayOf()) {
        var closed = false
            private set

        override fun close() {
            closed = true
            super.close()
        }
    }

    private data class RawResponse(
        val body: ByteArray,
        val status: HttpStatusCode,
    )

    private data class CapturedStreams(
        val standardOut: ByteArrayOutputStream,
        val standardErr: ByteArrayOutputStream,
        val output: CliOutput,
    ) {
        fun stdout(): String = standardOut.toString(Charsets.UTF_8)
        fun stderr(): String = standardErr.toString(Charsets.UTF_8)
    }

    private companion object {
        val json = Json { explicitNulls = true }
        const val SERVICE_UNAVAILABLE_MESSAGE =
            "Bitbucket Helper service is unavailable. Run 'bitbucket-helper service status' and then 'bitbucket-helper service start'.\n"
    }
}

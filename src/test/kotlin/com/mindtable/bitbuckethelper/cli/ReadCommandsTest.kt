package com.mindtable.bitbuckethelper.cli

import com.mindtable.bitbuckethelper.generated.api.v1.model.InboxResponse
import com.mindtable.bitbuckethelper.generated.api.v1.model.PullRequestDetailResponse
import com.mindtable.bitbuckethelper.generated.api.v1.model.PullRequestListResponse
import io.ktor.http.HttpStatusCode
import java.io.ByteArrayOutputStream
import java.io.IOException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ReadCommandsTest {
    @Test
    fun `pr list keeps server repository and pull request order and renders readiness values`() = runBlocking {
        val client = FakeLocalApiClient(
            responses = mapOf("/api/v1/pull-requests" to response(prListDocument)),
        )
        val streams = capturedStreams(isTerminal = false)

        val exit = PullRequestCommands(client, streams.output).list(OutputMode.HUMAN)

        assertEquals(CliExit.SUCCESS, exit)
        assertEquals(listOf("/api/v1/pull-requests"), client.getPaths)
        val human = streams.stdout()
        assertTrue(human.indexOf("Repository: Payments API") < human.indexOf("Repository: Catalog API"))
        assertTrue(human.indexOf("PR pr_184 (#184): Keep wire order") < human.indexOf("PR pr_183 (#183): No recalculation"))
        assertTrue(human.contains("Readiness: 6 of 7 checks"))
        assertTrue(human.contains("Readiness: unavailable (Upstream checks are unavailable.)"))
        assertTrue(human.contains("https://bitbucket.org/mindtable/payments-api/pull-requests/184"))
        assertEquals("", streams.stderr())
    }

    @Test
    fun `pr show renders checks builds action metadata freshness and links exactly as supplied`() = runBlocking {
        val client = FakeLocalApiClient(
            responses = mapOf("/api/v1/pull-requests/pr_184" to response(prDetailDocument)),
        )
        val streams = capturedStreams(isTerminal = false)

        val exit = PullRequestCommands(client, streams.output).show("pr_184", OutputMode.HUMAN)

        assertEquals(CliExit.SUCCESS, exit)
        assertEquals(listOf("/api/v1/pull-requests/pr_184"), client.getPaths)
        val human = streams.stdout()
        assertTrue(human.contains("Head commit: 0123456789abcdef"))
        assertTrue(human.contains("Freshness: stale; snapshot at 2026-08-15T10:00:00Z; age 120000ms; stale since 2026-08-15T10:01:00Z"))
        assertTrue(human.contains("Checks:"))
        assertTrue(human.contains("merge-check: passed"))
        assertTrue(human.contains("security-check: failed (Policy requires approval.)"))
        assertTrue(human.contains("Builds:"))
        assertTrue(human.contains("unit: successful"))
        assertTrue(human.contains("integration: failed"))
        assertTrue(human.contains("Action ai_501: review-requested"))
        assertTrue(human.contains("Activity version: av_exact_7"))
        assertTrue(human.contains("Actor: Ada Lovelace (user_ada)"))
        assertTrue(human.contains("Activity at: 2026-08-15T10:02:00Z"))
        assertTrue(human.contains("Acknowledged at: none"))
        assertTrue(human.contains("https://bitbucket.org/mindtable/payments-api/pull-requests/184#activity-501"))
        assertEquals("", streams.stderr())
    }

    @Test
    fun `inbox displays only API actionable items with their exact activity versions`() = runBlocking {
        val client = FakeLocalApiClient(responses = mapOf("/api/v1/inbox" to response(inboxDocument)))
        val streams = capturedStreams(isTerminal = false)

        val exit = InboxCommand(client, streams.output).execute(OutputMode.HUMAN)

        assertEquals(CliExit.SUCCESS, exit)
        assertEquals(listOf("/api/v1/inbox"), client.getPaths)
        val human = streams.stdout()
        assertTrue(human.contains("Inbox item: ai_501"))
        assertTrue(human.contains("Activity version: av_exact_7"))
        assertTrue(human.contains("Repository: Payments API (repo_payments)"))
        assertTrue(human.contains("Pull request: #184 Keep wire order (pr_184)"))
        assertTrue(human.contains("Kind: review-requested"))
        assertTrue(human.contains("State: open"))
        assertFalse(human.contains("ai_closed"))
        assertEquals("", streams.stderr())
    }

    @Test
    fun `read commands render successful empty and typed missing states without treating them as transport failures`() = runBlocking {
        val client = FakeLocalApiClient(
            responses = mapOf(
                "/api/v1/pull-requests" to response("""{"apiVersion":"1","requestId":"req_empty","result":{"type":"available","repositoryGroups":[]}}"""),
                "/api/v1/inbox" to response("""{"apiVersion":"1","requestId":"req_inbox_empty","result":{"type":"available","inbox":{"items":[]}}}"""),
                "/api/v1/pull-requests/pr_unknown" to response("""{"apiVersion":"1","requestId":"req_missing","result":{"type":"pullRequestNotFound","pullRequestId":"pr_unknown"}}"""),
            ),
        )
        val listStreams = capturedStreams(isTerminal = false)
        val inboxStreams = capturedStreams(isTerminal = false)
        val showStreams = capturedStreams(isTerminal = false)

        assertEquals(CliExit.SUCCESS, PullRequestCommands(client, listStreams.output).list(OutputMode.HUMAN))
        assertEquals(CliExit.SUCCESS, InboxCommand(client, inboxStreams.output).execute(OutputMode.HUMAN))
        assertEquals(CliExit.SUCCESS, PullRequestCommands(client, showStreams.output).show("pr_unknown", OutputMode.HUMAN))

        assertEquals("No pull requests.\n", listStreams.stdout())
        assertEquals("No actionable inbox items.\n", inboxStreams.stdout())
        assertEquals("Pull request pr_unknown was not found.\n", showStreams.stdout())
        assertEquals(
            listOf("/api/v1/pull-requests", "/api/v1/inbox", "/api/v1/pull-requests/pr_unknown"),
            client.getPaths,
        )
    }

    @Test
    fun `pr show rejects syntactically invalid opaque IDs before any API call`() = runBlocking {
        val client = FakeLocalApiClient(emptyMap())
        val streams = capturedStreams(isTerminal = false)

        val exit = PullRequestCommands(client, streams.output).show("pr_184/other", OutputMode.HUMAN)

        assertEquals(CliExit.USAGE_ERROR, exit)
        assertTrue(client.getPaths.isEmpty())
        assertEquals("", streams.stdout())
        assertEquals("", streams.stderr())
    }

    @Test
    fun `connection failure renders service unavailable with stable exit four`() = runBlocking {
        val client = FakeLocalApiClient(failure = IOException("socket refused"))
        val streams = capturedStreams(isTerminal = false)

        val exit = InboxCommand(client, streams.output).execute(OutputMode.HUMAN)

        assertEquals(CliExit.SERVICE_OR_PROTOCOL_FAILURE, exit)
        assertEquals(
            "Bitbucket Helper service is unavailable. Run 'bitbucket-helper service status' and then 'bitbucket-helper service start'.\n",
            streams.stdout(),
        )
        assertEquals("", streams.stderr())
    }

    @Test
    fun `JSON output writes the original successful API document bytes`() = runBlocking {
        val original = """{"result":{"inbox":{"items":[]},"type":"available"},"requestId":"req_json","apiVersion":"1"}""".encodeToByteArray()
        val client = FakeLocalApiClient(responses = mapOf("/api/v1/inbox" to response(original)))
        val streams = capturedStreams(isTerminal = false)

        val exit = InboxCommand(client, streams.output).execute(OutputMode.JSON)

        assertEquals(CliExit.SUCCESS, exit)
        assertTrue(streams.standardOut.toByteArray().contentEquals(original + '\n'.code.toByte()))
        assertEquals("", streams.stderr())
    }

    private fun response(document: String): RawResponse = response(document.encodeToByteArray())

    private fun response(document: ByteArray): RawResponse = RawResponse(document)

    private fun capturedStreams(isTerminal: Boolean): CapturedStreams {
        val standardOut = ByteArrayOutputStream()
        val standardErr = ByteArrayOutputStream()
        return CapturedStreams(
            standardOut = standardOut,
            standardErr = standardErr,
            output = CliOutput(standardOut, standardErr, TerminalCapability(isTerminal)),
        )
    }

    private class FakeLocalApiClient(
        private val responses: Map<String, RawResponse> = emptyMap(),
        private val failure: IOException? = null,
    ) : LocalApiClient {
        val getPaths = mutableListOf<String>()

        override suspend fun <Response> get(
            path: String,
            responseSerializer: KSerializer<Response>,
        ): LocalApiResponse<Response> {
            getPaths += path
            failure?.let { throw it }
            val raw = requireNotNull(responses[path]) { "No response configured for $path" }
            return LocalApiResponse(
                status = HttpStatusCode.OK,
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
        ): LocalApiResponse<Response> = error("Read command fake does not support POST")

        override suspend fun <Request, Response> put(
            path: String,
            request: Request,
            requestSerializer: KSerializer<Request>,
            responseSerializer: KSerializer<Response>,
        ): LocalApiResponse<Response> = error("Read command fake does not support PUT")

        override suspend fun <Response> delete(
            path: String,
            responseSerializer: KSerializer<Response>,
        ): LocalApiResponse<Response> = error("Read command fake does not support DELETE")

        override fun close() = Unit
    }

    private data class RawResponse(val body: ByteArray)

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

        const val prListDocument = """
            {"apiVersion":"1","requestId":"req_list","result":{"type":"available","repositoryGroups":[
              {"repositoryId":"repo_payments","slug":"payments-api","displayName":"Payments API","webUrl":"https://bitbucket.org/mindtable/payments-api","repositoryRevision":"rr_2","synchronization":{"repositoryId":"repo_payments","activity":"idle","lastAttemptAt":null,"lastAttemptOutcome":null,"lastSuccessAt":null,"freshness":{"type":"neverSynchronized"},"problem":{"type":"none"}},"readinessSummary":{"readyPullRequestCount":0,"availablePullRequestCount":1,"unavailablePullRequestCount":0},"pullRequests":[
                {"pullRequestId":"pr_184","repositoryId":"repo_payments","upstreamNumber":184,"title":"Keep wire order","author":{"stableId":"user_ada","displayName":"Ada Lovelace"},"draft":false,"createdAt":"2026-08-15T09:00:00Z","updatedAt":"2026-08-15T10:00:00Z","webUrl":"https://bitbucket.org/mindtable/payments-api/pull-requests/184","readiness":{"type":"available","passed":6,"total":7,"checks":[{"name":"merge-check","passed":true,"safeReason":null}]},"buildState":"successful","actionableItemCount":1,"acknowledgedItemCount":0,"actionItems":[]}
              ]},
              {"repositoryId":"repo_catalog","slug":"catalog-api","displayName":"Catalog API","webUrl":"https://bitbucket.org/mindtable/catalog-api","repositoryRevision":"rr_3","synchronization":{"repositoryId":"repo_catalog","activity":"idle","lastAttemptAt":null,"lastAttemptOutcome":null,"lastSuccessAt":null,"freshness":{"type":"neverSynchronized"},"problem":{"type":"none"}},"readinessSummary":{"readyPullRequestCount":0,"availablePullRequestCount":0,"unavailablePullRequestCount":1},"pullRequests":[
                {"pullRequestId":"pr_183","repositoryId":"repo_catalog","upstreamNumber":183,"title":"No recalculation","author":{"stableId":"user_grace","displayName":"Grace Hopper"},"draft":true,"createdAt":"2026-08-15T08:00:00Z","updatedAt":"2026-08-15T09:00:00Z","webUrl":"https://bitbucket.org/mindtable/catalog-api/pull-requests/183","readiness":{"type":"unavailable","safeReason":"Upstream checks are unavailable."},"buildState":"unknown","actionableItemCount":0,"acknowledgedItemCount":2,"actionItems":[]}
              ]}
            ]}}"""

        const val prDetailDocument = """
            {"apiVersion":"1","requestId":"req_detail","result":{"type":"pullRequestFound","pullRequest":{"pullRequest":{"pullRequestId":"pr_184","repositoryId":"repo_payments","upstreamNumber":184,"title":"Keep wire order","author":{"stableId":"user_ada","displayName":"Ada Lovelace"},"draft":false,"createdAt":"2026-08-15T09:00:00Z","updatedAt":"2026-08-15T10:00:00Z","webUrl":"https://bitbucket.org/mindtable/payments-api/pull-requests/184","readiness":{"type":"available","passed":6,"total":7,"checks":[{"name":"merge-check","passed":true,"safeReason":null},{"name":"security-check","passed":false,"safeReason":"Policy requires approval."}]},"buildState":"failed","actionableItemCount":1,"acknowledgedItemCount":0,"actionItems":[{"actionItemId":"ai_501","pullRequestId":"pr_184","repositoryId":"repo_payments","repositoryDisplayName":"Payments API","pullRequestNumber":184,"pullRequestTitle":"Keep wire order","activityVersion":"av_exact_7","kind":"review-requested","actor":{"stableId":"user_ada","displayName":"Ada Lovelace"},"activityAt":"2026-08-15T10:02:00Z","state":"open","acknowledgedAt":null,"webUrl":"https://bitbucket.org/mindtable/payments-api/pull-requests/184#activity-501"}]},"headCommit":"0123456789abcdef","builds":[{"key":"unit","state":"successful"},{"key":"integration","state":"failed"}],"freshness":{"type":"stale","snapshotAt":"2026-08-15T10:00:00Z","ageMilliseconds":120000,"staleSince":"2026-08-15T10:01:00Z"}}}}"""

        const val inboxDocument = """
            {"apiVersion":"1","requestId":"req_inbox","result":{"type":"available","inbox":{"items":[
              {"actionItemId":"ai_501","pullRequestId":"pr_184","repositoryId":"repo_payments","repositoryDisplayName":"Payments API","pullRequestNumber":184,"pullRequestTitle":"Keep wire order","activityVersion":"av_exact_7","kind":"review-requested","actor":{"stableId":"user_ada","displayName":"Ada Lovelace"},"activityAt":"2026-08-15T10:02:00Z","state":"open","acknowledgedAt":null,"webUrl":"https://bitbucket.org/mindtable/payments-api/pull-requests/184#activity-501"}
            ]}}}"""
    }
}

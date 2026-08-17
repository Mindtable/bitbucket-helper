package com.mindtable.bitbuckethelper.cli

import com.mindtable.bitbuckethelper.generated.api.v1.model.GetRefreshRunResponse
import io.ktor.http.HttpStatusCode
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.ArrayDeque
import java.util.concurrent.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class RefreshCommandTest {
    @Test
    fun `generated refresh status DTO decodes B0 valid active polling advice`() {
        assertDoesNotThrow {
            json.decodeFromString(
                GetRefreshRunResponse.serializer(),
                """
                {
                  "apiVersion": "1",
                  "requestId": "req_status",
                  "result": {
                    "type": "refreshRunInProgress",
                    "refreshRun": {
                      "refreshRunId": "rr_refresh",
                      "createdAt": "2030-01-01T00:00:00Z",
                      "expiresAt": "2030-01-01T00:10:00Z",
                      "repositories": [{"type": "queued", "repositoryId": "repo_alpha"}]
                    },
                    "polling": {"type": "active", "afterMilliseconds": 750}
                  }
                }
                """.trimIndent(),
            )
        }
    }

    @Test
    fun `refresh defaults to all configured repositories and closes the client`() = runBlocking {
        val client = FakeLocalApiClient(listOf(response(registeredDocument("rr_all"))))
        val sleeper = FakeSleeper()
        val streams = capturedStreams()

        val exit = command(client, streams.output, sleeper).refresh(
            repositoryIds = emptyList(),
            noWait = true,
            mode = OutputMode.HUMAN,
        )

        assertEquals(CliExit.SUCCESS, exit)
        assertEquals(
            listOf(ClientCall("POST", REFRESH_RUNS_PATH, allConfiguredTargetBody)),
            client.calls,
        )
        assertEquals(emptyList<Long>(), sleeper.delays)
        assertEquals(
            "Refresh run rr_all registered; not waiting for completion.\n" +
                "Registration dispositions:\n" +
                "  repo_alpha: started\n",
            streams.stdout(),
        )
        assertEquals("", streams.stderr())
        assertEquals(1, client.closeCount)
    }

    @Test
    fun `refresh deduplicates repeated explicit repository ids without reordering them`() = runBlocking {
        val client = FakeLocalApiClient(listOf(response(registeredDocument("rr_deduplicated"))))

        val exit = command(client, capturedStreams().output, FakeSleeper()).refresh(
            repositoryIds = listOf("repo_beta", "repo_alpha", "repo_beta"),
            noWait = true,
            mode = OutputMode.HUMAN,
        )

        assertEquals(CliExit.SUCCESS, exit)
        assertEquals(
            listOf(ClientCall("POST", REFRESH_RUNS_PATH, explicitTargetBody("repo_beta", "repo_alpha"))),
            client.calls,
        )
        assertEquals(1, client.closeCount)
    }

    @Test
    fun `refresh joins existing work then polls with the server delay until terminal success`() = runBlocking {
        val client = FakeLocalApiClient(
            listOf(
                response(
                    registeredDocument(
                        runId = "rr_join",
                        dispositions = "[{'type':'started','repositoryId':'repo_alpha'},{'type':'joinedExisting','repositoryId':'repo_beta'}]"
                            .replace('\'', '"'),
                    ),
                ),
                response(inProgressDocument("rr_join", afterMilliseconds = 750)),
                response(completedDocument("rr_join", succeededEntry("repo_alpha"))),
            ),
        )
        val sleeper = FakeSleeper()
        val streams = capturedStreams()

        val exit = command(client, streams.output, sleeper).refresh(
            repositoryIds = listOf("repo_alpha", "repo_beta"),
            noWait = false,
            mode = OutputMode.HUMAN,
        )

        assertEquals(CliExit.SUCCESS, exit)
        assertEquals(
            listOf(
                ClientCall("POST", REFRESH_RUNS_PATH, explicitTargetBody("repo_alpha", "repo_beta")),
                ClientCall("GET", "$REFRESH_RUNS_PATH/rr_join"),
                ClientCall("GET", "$REFRESH_RUNS_PATH/rr_join"),
            ),
            client.calls,
        )
        assertEquals(listOf(750L), sleeper.delays)
        assertEquals(
            "Refresh run rr_join completed.\n" +
                "Registration dispositions:\n" +
                "  repo_alpha: started\n" +
                "  repo_beta: joined existing\n",
            streams.stdout(),
        )
        assertEquals("", streams.stderr())
        assertEquals(1, client.closeCount)
    }

    @Test
    fun `refresh no wait returns the registration envelope without polling`() = runBlocking {
        val registration = "{\"result\":{\"dispositions\":[{\"repositoryId\":\"repo_alpha\",\"type\":\"started\"}],\"refreshRun\":${refreshRun("rr_nowait")},\"type\":\"refreshRunRegistered\"},\"requestId\":\"req_nowait\",\"apiVersion\":\"1\"}"
        val client = FakeLocalApiClient(listOf(response(registration)))
        val sleeper = FakeSleeper()
        val streams = capturedStreams()

        val exit = command(client, streams.output, sleeper).refresh(
            repositoryIds = listOf("repo_alpha"),
            noWait = true,
            mode = OutputMode.JSON,
        )

        assertEquals(CliExit.SUCCESS, exit)
        assertEquals(listOf(ClientCall("POST", REFRESH_RUNS_PATH, explicitTargetBody("repo_alpha"))), client.calls)
        assertEquals(emptyList<Long>(), sleeper.delays)
        assertTrue(streams.standardOut.toByteArray().contentEquals(registration.encodeToByteArray() + '\n'.code.toByte()))
        assertEquals("", streams.stderr())
        assertEquals(1, client.closeCount)
    }

    @Test
    fun `refresh no wait classifies every registration disposition in API order`() = runBlocking {
        val dispositions = """[{"type":"started","repositoryId":"repo_started"},{"type":"joinedExisting","repositoryId":"repo_joined"},{"type":"deferredByBackoff","repositoryId":"repo_deferred","retryAt":"$RETRY_AT"},{"type":"repositoryNotConfigured","repositoryId":"repo_missing"}]"""
        val client = FakeLocalApiClient(listOf(response(registeredDocument("rr_mixed_nowait", dispositions = dispositions))))
        val streams = capturedStreams()

        val exit = command(client, streams.output, FakeSleeper()).refresh(
            repositoryIds = listOf("repo_started", "repo_joined", "repo_deferred", "repo_missing"),
            noWait = true,
            mode = OutputMode.HUMAN,
        )

        assertEquals(CliExit.BUSINESS_NOT_ACHIEVED, exit)
        assertEquals(
            """
            Refresh run rr_mixed_nowait registered; not waiting for completion.
            Registration dispositions:
              repo_started: started
              repo_joined: joined existing
              repo_deferred: deferred by backoff until $RETRY_AT
              repo_missing: repository not configured
            """.trimIndent() + "\n",
            streams.stdout(),
        )
        assertEquals(1, client.calls.size)
    }

    @Test
    fun `refresh keeps mixed registration as business non achievement after started subset completes`() = runBlocking {
        val dispositions = """[{"type":"started","repositoryId":"repo_started"},{"type":"deferredByBackoff","repositoryId":"repo_deferred","retryAt":"$RETRY_AT"}]"""
        val completed = completedDocument("rr_mixed_wait", succeededEntry("repo_started"))
        val client = FakeLocalApiClient(
            listOf(
                response(registeredDocument("rr_mixed_wait", dispositions = dispositions)),
                response(completed),
            ),
        )
        val streams = capturedStreams()

        val exit = command(client, streams.output, FakeSleeper()).refresh(
            repositoryIds = listOf("repo_started", "repo_deferred"),
            noWait = false,
            mode = OutputMode.HUMAN,
        )

        assertEquals(CliExit.BUSINESS_NOT_ACHIEVED, exit)
        assertEquals(
            """
            Refresh run rr_mixed_wait completed, but not all requested repositories were registered.
            Registration dispositions:
              repo_started: started
              repo_deferred: deferred by backoff until $RETRY_AT
            """.trimIndent() + "\n",
            streams.stdout(),
        )
        assertEquals(2, client.calls.size)
    }

    @Test
    fun `refresh mixed registration JSON keeps the one applicable original envelope and exit three`() = runBlocking {
        val dispositions = """[{"type":"started","repositoryId":"repo_started"},{"type":"repositoryNotConfigured","repositoryId":"repo_missing"}]"""
        val registration = registeredDocument("rr_mixed_json", dispositions = dispositions)
        val completed = completedDocument("rr_mixed_json", succeededEntry("repo_started"))

        listOf(
            Triple(true, listOf(response(registration)), registration),
            Triple(false, listOf(response(registration), response(completed)), completed),
        ).forEach { (noWait, responses, expectedDocument) ->
            val client = FakeLocalApiClient(responses)
            val streams = capturedStreams()

            val exit = command(client, streams.output, FakeSleeper()).refresh(
                repositoryIds = listOf("repo_started", "repo_missing"),
                noWait = noWait,
                mode = OutputMode.JSON,
            )

            assertEquals(CliExit.BUSINESS_NOT_ACHIEVED, exit)
            assertTrue(
                streams.standardOut.toByteArray().contentEquals(expectedDocument.encodeToByteArray() + '\n'.code.toByte()),
            )
        }
    }

    @Test
    fun `refresh rejects empty unknown or malformed registration disposition protocol data`() = runBlocking {
        listOf(
            "[]",
            """[{"type":"futureDisposition","repositoryId":"repo_alpha"}]""",
            """[{"type":"started","repositoryId":"repo_alpha/other"}]""",
            """[{"type":"joinedExisting","repositoryId":"not-a-repository-id"}]""",
            """[{"type":"deferredByBackoff","repositoryId":"repo_bad/other","retryAt":"$RETRY_AT"}]""",
            """[{"type":"repositoryNotConfigured","repositoryId":"repo_bad/other"}]""",
        ).forEachIndexed { index, dispositions ->
            val client = FakeLocalApiClient(
                listOf(response(registeredDocument("rr_bad_disposition_$index", dispositions = dispositions))),
            )
            val streams = capturedStreams()

            val exit = command(client, streams.output, FakeSleeper()).refresh(
                repositoryIds = emptyList(),
                noWait = true,
                mode = OutputMode.HUMAN,
            )

            assertEquals(CliExit.SERVICE_OR_PROTOCOL_FAILURE, exit, dispositions)
            assertEquals(SERVICE_UNAVAILABLE_MESSAGE, streams.stdout(), dispositions)
        }
    }

    @Test
    fun `refresh human disposition detail visibly escapes API controls`() = runBlocking {
        val dispositions = """[{"type":"deferredByBackoff","repositoryId":"repo_alpha","retryAt":"soon\u001B[31m\nnow"}]"""
        val client = FakeLocalApiClient(
            listOf(response(registeredDocument("rr_escaped", dispositions = dispositions))),
        )
        val streams = capturedStreams()

        val exit = command(client, streams.output, FakeSleeper()).refresh(
            repositoryIds = listOf("repo_alpha"),
            noWait = true,
            mode = OutputMode.HUMAN,
        )

        assertEquals(CliExit.BUSINESS_NOT_ACHIEVED, exit)
        assertTrue(streams.stdout().contains("deferred by backoff until soon\\u001B[31m\\nnow"))
        assertFalse(streams.stdout().contains('\u001B'))
        assertFalse(streams.stdout().contains("\nnow"))
    }

    @Test
    fun `refresh maps typed registration and unavailable results to business non achievement`() = runBlocking {
        listOf(
            listOf(response(workspaceNotConfiguredDocument)) to listOf(ClientCall("POST", REFRESH_RUNS_PATH, allConfiguredTargetBody)),
            listOf(response(noRepositoriesConfiguredDocument)) to listOf(ClientCall("POST", REFRESH_RUNS_PATH, allConfiguredTargetBody)),
            listOf(response(registeredDocument("rr_unavailable")), response(unavailableDocument("rr_unavailable"))) to listOf(
                ClientCall("POST", REFRESH_RUNS_PATH, allConfiguredTargetBody),
                ClientCall("GET", "$REFRESH_RUNS_PATH/rr_unavailable"),
            ),
        ).forEach { (responses, expectedCalls) ->
            val client = FakeLocalApiClient(responses)
            val streams = capturedStreams()

            val exit = command(client, streams.output, FakeSleeper()).refresh(
                repositoryIds = emptyList(),
                noWait = false,
                mode = OutputMode.HUMAN,
            )

            assertEquals(CliExit.BUSINESS_NOT_ACHIEVED, exit)
            assertEquals(expectedCalls, client.calls)
            assertEquals(1, client.closeCount)
        }
    }

    @Test
    fun `refresh maps terminal partial failed and deferred repositories to business non achievement`() = runBlocking {
        listOf(
            partialFailureEntry("repo_alpha"),
            failedEntry("repo_alpha"),
            deferredEntry("repo_alpha"),
        ).forEachIndexed { index, terminalEntry ->
            val runId = "rr_terminal_$index"
            val client = FakeLocalApiClient(
                listOf(
                    response(registeredDocument(runId)),
                    response(completedDocument(runId, terminalEntry)),
                ),
            )
            val streams = capturedStreams()

            val exit = command(client, streams.output, FakeSleeper()).refresh(
                repositoryIds = emptyList(),
                noWait = false,
                mode = OutputMode.HUMAN,
            )

            assertEquals(CliExit.BUSINESS_NOT_ACHIEVED, exit)
            assertEquals(
                listOf(
                    ClientCall("POST", REFRESH_RUNS_PATH, allConfiguredTargetBody),
                    ClientCall("GET", "$REFRESH_RUNS_PATH/$runId"),
                ),
                client.calls,
            )
            assertEquals(
                "Refresh run $runId completed with unsuccessful repositories.\n" +
                    "Registration dispositions:\n" +
                    "  repo_alpha: started\n",
                streams.stdout(),
            )
            assertEquals(1, client.closeCount)
        }
    }

    @Test
    fun `refresh JSON writes only the final applicable API envelope`() = runBlocking {
        val registration = "{\"requestId\":\"req_start\",\"apiVersion\":\"1\",\"result\":{\"type\":\"refreshRunRegistered\",\"dispositions\":[{\"type\":\"started\",\"repositoryId\":\"repo_alpha\"}],\"refreshRun\":${refreshRun("rr_json")}}}"
        val inProgress = "{\"apiVersion\":\"1\",\"result\":{\"type\":\"refreshRunInProgress\",\"polling\":{\"type\":\"active\",\"afterMilliseconds\":3},\"refreshRun\":${refreshRun("rr_json")}},\"requestId\":\"req_progress\"}"
        val completed = "{\"result\":{\"refreshRun\":${refreshRun("rr_json", repositories = "[${succeededEntry("repo_alpha")}]")},\"type\":\"refreshRunCompleted\"},\"requestId\":\"req_complete\",\"apiVersion\":\"1\"}"
        val client = FakeLocalApiClient(listOf(response(registration), response(inProgress), response(completed)))
        val streams = capturedStreams()

        val exit = command(client, streams.output, FakeSleeper()).refresh(
            repositoryIds = emptyList(),
            noWait = false,
            mode = OutputMode.JSON,
        )

        assertEquals(CliExit.SUCCESS, exit)
        assertTrue(streams.standardOut.toByteArray().contentEquals(completed.encodeToByteArray() + '\n'.code.toByte()))
        assertEquals("", streams.stderr())
        assertEquals(1, client.closeCount)
    }

    @Test
    fun `refresh caps polling at run expiry instead of issuing a post expiry request`() = runBlocking {
        val expiresAt = "2030-01-01T00:00:00.050Z"
        val client = FakeLocalApiClient(
            listOf(
                response(registeredDocument("rr_expiring", expiresAt = expiresAt)),
                response(inProgressDocument("rr_expiring", expiresAt = expiresAt, afterMilliseconds = 250)),
            ),
        )
        val sleeper = FakeSleeper()
        val streams = capturedStreams()

        val exit = command(client, streams.output, sleeper).refresh(
            repositoryIds = emptyList(),
            noWait = false,
            mode = OutputMode.HUMAN,
        )

        assertEquals(CliExit.BUSINESS_NOT_ACHIEVED, exit)
        assertEquals(
            listOf(
                ClientCall("POST", REFRESH_RUNS_PATH, allConfiguredTargetBody),
                ClientCall("GET", "$REFRESH_RUNS_PATH/rr_expiring"),
            ),
            client.calls,
        )
        assertEquals(listOf(50L), sleeper.delays)
        assertEquals(
            "Refresh run rr_expiring expired before completion.\n" +
                "Registration dispositions:\n" +
                "  repo_alpha: started\n",
            streams.stdout(),
        )
        assertEquals(1, client.closeCount)
    }

    @Test
    fun `refresh does not issue a post expiry request when the sleeper resumes late`() = runBlocking {
        val expiresAt = "2030-01-01T00:00:00.100Z"
        val inProgress = inProgressDocument("rr_late", expiresAt = expiresAt, afterMilliseconds = 25)
        val client = FakeLocalApiClient(
            listOf(
                response(registeredDocument("rr_late", expiresAt = expiresAt)),
                response(inProgress),
                response(completedDocument("rr_late", succeededEntry("repo_alpha"))),
            ),
        )
        val clock = MutableClock(Instant.parse(NOW))
        val sleeper = AdvancingSleeper(clock, elapsedMilliseconds = 150)
        val streams = capturedStreams()

        val exit = command(client, streams.output, sleeper, clock).refresh(
            repositoryIds = emptyList(),
            noWait = false,
            mode = OutputMode.JSON,
        )

        assertEquals(CliExit.BUSINESS_NOT_ACHIEVED, exit)
        assertEquals(
            listOf(
                ClientCall("POST", REFRESH_RUNS_PATH, allConfiguredTargetBody),
                ClientCall("GET", "$REFRESH_RUNS_PATH/rr_late"),
            ),
            client.calls,
        )
        assertEquals(listOf(25L), sleeper.delays)
        assertEquals(1, client.unusedResponseCount)
        assertTrue(streams.standardOut.toByteArray().contentEquals(inProgress.encodeToByteArray() + '\n'.code.toByte()))
        assertEquals("", streams.stderr())
        assertEquals(1, client.closeCount)
    }

    @Test
    fun `refresh interruption ends synchronous polling without detached work and closes the client`() {
        val client = FakeLocalApiClient(
            listOf(
                response(registeredDocument("rr_cancelled")),
                response(inProgressDocument("rr_cancelled", afterMilliseconds = 500)),
                response(completedDocument("rr_cancelled", succeededEntry("repo_alpha"))),
            ),
        )
        val sleeper = FakeSleeper(CancellationException("interrupted"))

        assertThrows(CancellationException::class.java) {
            runBlocking {
                command(client, capturedStreams().output, sleeper).refresh(
                    repositoryIds = emptyList(),
                    noWait = false,
                    mode = OutputMode.HUMAN,
                )
            }
        }

        assertEquals(
            listOf(
                ClientCall("POST", REFRESH_RUNS_PATH, allConfiguredTargetBody),
                ClientCall("GET", "$REFRESH_RUNS_PATH/rr_cancelled"),
            ),
            client.calls,
        )
        assertEquals(listOf(500L), sleeper.delays)
        assertEquals(1, client.unusedResponseCount)
        assertEquals(1, client.closeCount)
    }

    @Test
    fun `refresh maps non 200 and known protocol failures to exit four`() = runBlocking {
        val registration = registeredDocument("rr_protocol")
        listOf(
            FakeLocalApiClient(listOf(response(registration, HttpStatusCode.Accepted))) to 1,
            FakeLocalApiClient(
                listOf(
                    response(registration),
                    response(unavailableDocument("rr_protocol"), HttpStatusCode.Conflict),
                ),
            ) to 2,
            FakeLocalApiClient(listOf(failure(IOException("socket refused")))) to 1,
            FakeLocalApiClient(listOf(failure(LocalApiResponseTooLargeException(128)))) to 1,
            FakeLocalApiClient(listOf(failure(SerializationException("bad response")))) to 1,
        ).forEach { (client, expectedCallCount) ->
            val streams = capturedStreams()

            val exit = command(client, streams.output, FakeSleeper()).refresh(
                repositoryIds = emptyList(),
                noWait = false,
                mode = OutputMode.HUMAN,
            )

            assertEquals(CliExit.SERVICE_OR_PROTOCOL_FAILURE, exit)
            assertEquals(expectedCallCount, client.calls.size)
            assertEquals(SERVICE_UNAVAILABLE_MESSAGE, streams.stdout())
            assertEquals("", streams.stderr())
            assertEquals(1, client.closeCount)
        }
    }

    @Test
    fun `refresh preserves unexpected failures after closing the client`() {
        val client = FakeLocalApiClient(listOf(failure(IllegalStateException("unexpected"))))

        assertThrows(IllegalStateException::class.java) {
            runBlocking {
                command(client, capturedStreams().output, FakeSleeper()).refresh(
                    repositoryIds = emptyList(),
                    noWait = false,
                    mode = OutputMode.HUMAN,
                )
            }
        }

        assertEquals(listOf(ClientCall("POST", REFRESH_RUNS_PATH, allConfiguredTargetBody)), client.calls)
        assertEquals(1, client.closeCount)
    }

    @Test
    fun `refresh rejects malformed repository ids before registering a run and still closes the client`() = runBlocking {
        val client = FakeLocalApiClient(emptyList())
        val streams = capturedStreams()

        val exit = command(client, streams.output, FakeSleeper()).refresh(
            repositoryIds = listOf("repo_alpha/other"),
            noWait = false,
            mode = OutputMode.HUMAN,
        )

        assertEquals(CliExit.USAGE_ERROR, exit)
        assertTrue(client.calls.isEmpty())
        assertEquals("", streams.stdout())
        assertEquals("", streams.stderr())
        assertEquals(1, client.closeCount)
    }

    private fun command(
        client: FakeLocalApiClient,
        output: CliOutput,
        sleeper: Sleeper,
        clock: Clock = Clock.fixed(Instant.parse(NOW), ZoneOffset.UTC),
    ): RefreshCommand = RefreshCommand(
        client = client,
        output = output,
        sleeper = sleeper,
        clock = clock,
    )

    private fun response(document: String, status: HttpStatusCode = HttpStatusCode.OK): PlannedResponse =
        PlannedResponse.Response(document.encodeToByteArray(), status)

    private fun failure(cause: Throwable): PlannedResponse = PlannedResponse.Failure(cause)

    private fun registeredDocument(
        runId: String,
        expiresAt: String = FUTURE,
        dispositions: String = "[{\"type\":\"started\",\"repositoryId\":\"repo_alpha\"}]",
    ): String =
        "{\"apiVersion\":\"1\",\"requestId\":\"req_start\",\"result\":{\"type\":\"refreshRunRegistered\",\"refreshRun\":${refreshRun(runId, expiresAt)},\"dispositions\":$dispositions}}"

    private fun inProgressDocument(
        runId: String,
        expiresAt: String = FUTURE,
        afterMilliseconds: Long,
    ): String =
        "{\"apiVersion\":\"1\",\"requestId\":\"req_progress\",\"result\":{\"type\":\"refreshRunInProgress\",\"refreshRun\":${refreshRun(runId, expiresAt)},\"polling\":{\"type\":\"active\",\"afterMilliseconds\":$afterMilliseconds}}}"

    private fun completedDocument(runId: String, terminalEntry: String): String =
        "{\"apiVersion\":\"1\",\"requestId\":\"req_completed\",\"result\":{\"type\":\"refreshRunCompleted\",\"refreshRun\":${refreshRun(runId, repositories = "[$terminalEntry]")}}}"

    private fun unavailableDocument(runId: String): String =
        "{\"apiVersion\":\"1\",\"requestId\":\"req_unavailable\",\"result\":{\"type\":\"refreshRunUnavailable\",\"refreshRunId\":\"$runId\"}}"

    private fun refreshRun(
        runId: String,
        expiresAt: String = FUTURE,
        repositories: String = "[{\"type\":\"queued\",\"repositoryId\":\"repo_alpha\"}]",
    ): String =
        "{\"refreshRunId\":\"$runId\",\"createdAt\":\"$NOW\",\"expiresAt\":\"$expiresAt\",\"repositories\":$repositories}"

    private fun succeededEntry(repositoryId: String): String =
        "{\"type\":\"succeeded\",\"repositoryId\":\"$repositoryId\",\"completedAt\":\"$COMPLETED_AT\"}"

    private fun partialFailureEntry(repositoryId: String): String =
        "{\"type\":\"partialFailure\",\"repositoryId\":\"$repositoryId\",\"completedAt\":\"$COMPLETED_AT\",\"partialFailure\":{\"attemptedCount\":2,\"succeededCount\":1,\"failedCount\":1,\"failures\":[{\"category\":\"upstream\",\"retryable\":true,\"retryAt\":\"$RETRY_AT\"}]}}"

    private fun failedEntry(repositoryId: String): String =
        "{\"type\":\"failed\",\"repositoryId\":\"$repositoryId\",\"completedAt\":\"$COMPLETED_AT\",\"failure\":{\"category\":\"upstream\",\"retryable\":true,\"retryAt\":\"$RETRY_AT\"}}"

    private fun deferredEntry(repositoryId: String): String =
        "{\"type\":\"deferredByBackoff\",\"repositoryId\":\"$repositoryId\",\"retryAt\":\"$RETRY_AT\"}"

    private fun capturedStreams(): CapturedStreams {
        val standardOut = ByteArrayOutputStream()
        val standardErr = ByteArrayOutputStream()
        return CapturedStreams(
            standardOut = standardOut,
            standardErr = standardErr,
            output = CliOutput(standardOut, standardErr, TerminalCapability(false)),
        )
    }

    private class FakeSleeper(
        private val failure: Throwable? = null,
    ) : Sleeper {
        val delays = mutableListOf<Long>()

        override suspend fun sleep(milliseconds: Long) {
            delays += milliseconds
            failure?.let { throw it }
        }
    }

    private class AdvancingSleeper(
        private val clock: MutableClock,
        private val elapsedMilliseconds: Long,
    ) : Sleeper {
        val delays = mutableListOf<Long>()

        override suspend fun sleep(milliseconds: Long) {
            delays += milliseconds
            clock.advanceBy(elapsedMilliseconds)
        }
    }

    private class MutableClock(
        private var current: Instant,
    ) : Clock() {
        override fun getZone(): ZoneId = ZoneOffset.UTC

        override fun withZone(zone: ZoneId): Clock = this

        override fun instant(): Instant = current

        fun advanceBy(milliseconds: Long) {
            current = current.plusMillis(milliseconds)
        }
    }

    private class FakeLocalApiClient(
        responses: List<PlannedResponse>,
    ) : LocalApiClient {
        private val responses = ArrayDeque(responses)
        val calls = mutableListOf<ClientCall>()
        var closeCount = 0
            private set

        val unusedResponseCount: Int
            get() = responses.size

        override suspend fun <Response> get(
            path: String,
            responseSerializer: KSerializer<Response>,
        ): LocalApiResponse<Response> {
            calls += ClientCall("GET", path)
            return nextResponse(responseSerializer)
        }

        override suspend fun <Request, Response> post(
            path: String,
            request: Request,
            requestSerializer: KSerializer<Request>,
            responseSerializer: KSerializer<Response>,
        ): LocalApiResponse<Response> {
            calls += ClientCall("POST", path, json.encodeToString(requestSerializer, request))
            return nextResponse(responseSerializer)
        }

        override suspend fun <Request, Response> put(
            path: String,
            request: Request,
            requestSerializer: KSerializer<Request>,
            responseSerializer: KSerializer<Response>,
        ): LocalApiResponse<Response> = error("Refresh command must not PUT")

        override suspend fun <Response> delete(
            path: String,
            responseSerializer: KSerializer<Response>,
        ): LocalApiResponse<Response> = error("Refresh command must not DELETE")

        override fun close() {
            closeCount += 1
        }

        private fun <Response> nextResponse(responseSerializer: KSerializer<Response>): LocalApiResponse<Response> =
            when (val planned = requireNotNull(responses.pollFirst()) { "No response configured" }) {
                is PlannedResponse.Failure -> throw planned.cause
                is PlannedResponse.Response -> LocalApiResponse(
                    status = planned.status,
                    body = planned.body,
                    value = json.decodeFromString(responseSerializer, planned.body.decodeToString()),
                    error = null,
                )
            }
    }

    private sealed interface PlannedResponse {
        data class Response(val body: ByteArray, val status: HttpStatusCode) : PlannedResponse

        data class Failure(val cause: Throwable) : PlannedResponse
    }

    private data class ClientCall(
        val method: String,
        val path: String,
        val body: String? = null,
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
        const val REFRESH_RUNS_PATH = "/api/v1/refresh-runs"
        const val NOW = "2030-01-01T00:00:00Z"
        const val FUTURE = "2030-01-01T00:10:00Z"
        const val COMPLETED_AT = "2030-01-01T00:00:01Z"
        const val RETRY_AT = "2030-01-01T00:01:00Z"
        const val allConfiguredTargetBody =
            "{\"apiVersion\":\"1\",\"target\":{\"type\":\"allConfiguredRepositories\"}}"
        const val SERVICE_UNAVAILABLE_MESSAGE =
            "Bitbucket Helper service is unavailable. Run 'bitbucket-helper service status' and then 'bitbucket-helper service start'.\n"
        const val workspaceNotConfiguredDocument =
            "{\"apiVersion\":\"1\",\"requestId\":\"req_workspace\",\"result\":{\"type\":\"workspaceNotConfigured\",\"setupCommand\":\"bitbucket-helper workspace configure\"}}"
        const val noRepositoriesConfiguredDocument =
            "{\"apiVersion\":\"1\",\"requestId\":\"req_empty\",\"result\":{\"type\":\"noRepositoriesConfigured\"}}"

        fun explicitTargetBody(vararg repositoryIds: String): String =
            "{\"apiVersion\":\"1\",\"target\":{\"type\":\"repositories\",\"repositoryIds\":[${repositoryIds.joinToString(",") { "\"$it\"" }}]}}"

        val json = Json { explicitNulls = true }
    }
}

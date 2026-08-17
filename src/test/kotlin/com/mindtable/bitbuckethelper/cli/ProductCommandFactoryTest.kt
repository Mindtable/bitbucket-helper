package com.mindtable.bitbuckethelper.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.UsageError
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.testing.test
import com.mindtable.bitbuckethelper.generated.api.v1.model.RequestErrorEnvelope
import io.ktor.http.HttpStatusCode
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class ProductCommandFactoryTest {
    @TempDir
    lateinit var temporaryDirectory: Path

    @Test
    fun `help exposes the complete product tree without creating a client or touching the socket`() {
        val harness = Harness(temporaryDirectory.resolve("missing.sock"))

        val helpCases = listOf(
            "--help" to listOf("pr", "inbox", "open", "ack", "refresh", "workspace", "repository", "--output"),
            "pr --help" to listOf("list", "show", "--output"),
            "workspace --help" to listOf("show", "configure", "--output"),
            "repository --help" to listOf("add", "remove", "--output"),
            "pr list --help" to listOf("--output"),
            "pr show --help" to listOf("<pull-request-id>", "--output"),
            "inbox --help" to listOf("--output"),
            "open --help" to listOf("<pull-request-id>", "--output"),
            "ack --help" to listOf("<action-item-id>", "<activity-version>", "--output"),
            "refresh --help" to listOf("--repository", "--no-wait", "--output"),
            "workspace show --help" to listOf("--output"),
            "workspace configure --help" to listOf("--api-base-url", "--slug", "--output"),
            "repository add --help" to listOf("<slug>", "--output"),
            "repository remove --help" to listOf("<repository-id>", "--output"),
        )

        helpCases.forEach { (argv, expectedFragments) ->
            val result = root(harness).test(argv)

            assertEquals(0, result.statusCode, argv)
            expectedFragments.forEach { fragment ->
                assertTrue(result.output.contains(fragment), "$argv must advertise $fragment")
            }
        }
        assertEquals(0, harness.clientCreations)
        assertTrue(Files.notExists(harness.socketPath))
        assertEquals(emptyList<String>(), harness.openedUrls)
        assertEquals(emptyList<Long>(), harness.sleeps)
    }

    @Test
    fun `every product argv reaches its Unix client operation and closes the resource`() {
        val cases = listOf(
            Invocation("pr list", ClientCall("GET", "/api/v1/pull-requests")),
            Invocation("pr show pr_184", ClientCall("GET", "/api/v1/pull-requests/pr_184")),
            Invocation("inbox", ClientCall("GET", "/api/v1/inbox")),
            Invocation("open pr_184", ClientCall("GET", "/api/v1/pull-requests/pr_184")),
            Invocation(
                "ack ai_501 av_target_7",
                ClientCall(
                    "PUT",
                    "/api/v1/action-items/ai_501/acknowledgment",
                    "{\"apiVersion\":\"1\",\"activityVersion\":\"av_target_7\"}",
                ),
            ),
            Invocation(
                "refresh --repository repo_alpha --repository repo_beta --no-wait",
                ClientCall(
                    "POST",
                    "/api/v1/refresh-runs",
                    "{\"apiVersion\":\"1\",\"target\":{\"type\":\"repositories\",\"repositoryIds\":[\"repo_alpha\",\"repo_beta\"]}}",
                ),
                closeInvocations = 2,
            ),
            Invocation("workspace show", ClientCall("GET", "/api/v1/configuration/workspace")),
            Invocation(
                "workspace configure --api-base-url https://api.bitbucket.org/2.0 --slug mindtable",
                ClientCall(
                    "PUT",
                    "/api/v1/configuration/workspace",
                    "{\"apiVersion\":\"1\",\"bitbucketApiBaseUrl\":\"https://api.bitbucket.org/2.0\",\"workspaceSlug\":\"mindtable\"}",
                ),
            ),
            Invocation(
                "repository add payments-api",
                ClientCall(
                    "POST",
                    "/api/v1/configuration/workspace/repositories",
                    "{\"apiVersion\":\"1\",\"repositorySlug\":\"payments-api\"}",
                ),
            ),
            Invocation(
                "repository remove repo_payments",
                ClientCall("DELETE", "/api/v1/configuration/workspace/repositories/repo_payments"),
            ),
        )

        cases.forEach { invocation ->
            val harness = Harness(temporaryDirectory.resolve("product.sock"))
            val result = root(harness).test(invocation.argv)
            val client = harness.clients.single()

            assertEquals(CliExit.SERVICE_OR_PROTOCOL_FAILURE.code, result.statusCode, invocation.argv)
            assertEquals(listOf(harness.socketPath), harness.factoryPaths, invocation.argv)
            assertEquals(listOf(invocation.call), client.calls, invocation.argv)
            assertEquals(invocation.closeInvocations, client.closeInvocations, invocation.argv)
            assertEquals(1, client.resourceCloseCount, invocation.argv)
            assertEquals(SERVICE_UNAVAILABLE_MESSAGE, harness.stdout())
            assertEquals("", harness.stderr())
        }
    }

    @Test
    fun `root group and leaf JSON modes preserve an API body ending in LF and append one LF`() {
        val document =
            "{\"requestId\":\"req_lf\",\"result\":{\"type\":\"available\",\"repositoryGroups\":[]},\"apiVersion\":\"1\"}\n"

        listOf(
            "--output json pr list",
            "pr --output json list",
            "pr list --output json",
        ).forEachIndexed { index, argv ->
            val harness = Harness(
                socketPath = temporaryDirectory.resolve("product-$index.sock"),
                clientSupplier = { SuccessfulReadClient(document.encodeToByteArray()) },
            )

            val result = root(harness).test(argv)

            assertEquals(CliExit.SUCCESS.code, result.statusCode, argv)
            assertEquals(document + "\n", harness.stdout(), argv)
            assertEquals("", harness.stderr(), argv)
            assertEquals(1, harness.clients.single().resourceCloseCount, argv)
        }
    }

    @Test
    fun `every command preserves exact request error bytes in JSON with exit four`() {
        val document =
            "{ \"error\" : { \"violations\" : [ ], \"message\" : \"Cafe\u0301 Ω\", \"code\" : \"INVALID_REQUEST\" }, \"requestId\" : \"req_unicode_4xx\", \"apiVersion\" : \"1\" }"
                .encodeToByteArray()
        val commands = listOf(
            "--output json pr list",
            "--output json pr show pr_184",
            "--output json inbox",
            "--output json open pr_184",
            "--output json ack ai_501 av_target_7",
            "--output json refresh --repository repo_alpha --no-wait",
            "--output json workspace show",
            "--output json workspace configure --api-base-url https://api.bitbucket.org/2.0 --slug mindtable",
            "--output json repository add payments-api",
            "--output json repository remove repo_payments",
        )

        commands.forEachIndexed { index, argv ->
            val harness = Harness(
                socketPath = temporaryDirectory.resolve("request-error-$index.sock"),
                clientSupplier = { RequestErrorClient(document) },
            )

            val result = root(harness).test(argv)

            assertEquals(CliExit.SERVICE_OR_PROTOCOL_FAILURE.code, result.statusCode, argv)
            assertTrue(harness.standardOutBytes().contentEquals(document + '\n'.code.toByte()), argv)
            assertEquals("", harness.stderr(), argv)
        }
    }

    @Test
    fun `leaf output overrides root global output`() {
        val document =
            "{\"requestId\":\"req_override\",\"result\":{\"type\":\"available\",\"repositoryGroups\":[]},\"apiVersion\":\"1\"}"
        val harness = Harness(
            socketPath = temporaryDirectory.resolve("product.sock"),
            clientSupplier = { SuccessfulReadClient(document.encodeToByteArray()) },
        )

        val result = root(harness).test("--output json pr list --output human")

        assertEquals(CliExit.SUCCESS.code, result.statusCode)
        assertEquals("No pull requests.\n", harness.stdout())
        assertEquals("", harness.stderr())
        assertEquals(1, harness.clients.single().resourceCloseCount)
    }

    @Test
    fun `global output selection and command validation use usage exit two before any API call`() {
        listOf(
            "--output yaml pr list",
            "pr list --output yaml",
            "pr show",
            "ack ai_501",
            "workspace configure --api-base-url https://api.bitbucket.org/2.0",
            "repository remove invalid",
        ).forEach { argv ->
            val harness = Harness(temporaryDirectory.resolve("product.sock"))

            val result = root(harness).test(argv)

            assertEquals(CliExit.USAGE_ERROR.code, result.statusCode, argv)
            assertTrue(harness.clients.all { it.calls.isEmpty() }, argv)
            assertTrue(harness.clients.all { it.resourceCloseCount == 1 }, argv)
            assertEquals("", harness.stdout(), argv)
            assertEquals("", harness.stderr(), argv)
        }
    }

    private fun root(harness: Harness): CliktCommand =
        TestRoot(harness.globalOutputSelection).subcommands(productCommands(harness.dependencies()))

    private class TestRoot(
        private val globalOutputSelection: GlobalOutputSelection,
    ) : CliktCommand(name = "bitbucket-helper") {
        private val requestedOutput by option(
            "--output",
            metavar = "human|json",
            help = "Output format: human or json (default: human).",
        )

        override fun run() {
            globalOutputSelection.mode = requestedOutput?.let { value ->
                when (value) {
                    "human" -> OutputMode.HUMAN
                    "json" -> OutputMode.JSON
                    else -> throw UsageError(
                        "invalid value for --output: $value (choose human or json)",
                        "--output",
                        CliExit.USAGE_ERROR.code,
                    )
                }
            }
        }
    }

    private class GlobalOutputSelection {
        var mode: OutputMode? = null
    }

    private class Harness(
        val socketPath: Path,
        private val clientSupplier: () -> RecordingClient = { UnavailableClient() },
    ) {
        private val standardOut = ByteArrayOutputStream()
        private val standardErr = ByteArrayOutputStream()
        val factoryPaths = mutableListOf<Path>()
        val clients = mutableListOf<RecordingClient>()
        val openedUrls = mutableListOf<String>()
        val sleeps = mutableListOf<Long>()
        val globalOutputSelection = GlobalOutputSelection()
        val clientCreations: Int get() = clients.size

        fun dependencies(): ProductCommandDependencies = ProductCommandDependencies(
            socketPath = socketPath,
            standardOut = standardOut,
            standardErr = standardErr,
            terminal = TerminalCapability(false),
            openUrl = OpenUrl { url -> openedUrls.add(url) },
            sleeper = Sleeper { milliseconds -> sleeps.add(milliseconds) },
            clientFactory = { path ->
                factoryPaths.add(path)
                clientSupplier().also(clients::add)
            },
            globalOutputMode = { globalOutputSelection.mode },
        )

        fun stdout(): String = standardOut.toString(Charsets.UTF_8)
        fun standardOutBytes(): ByteArray = standardOut.toByteArray()
        fun stderr(): String = standardErr.toString(Charsets.UTF_8)
    }

    private interface RecordingClient : LocalApiClient {
        val calls: MutableList<ClientCall>
        val closeInvocations: Int
        val resourceCloseCount: Int
    }

    private abstract class BaseRecordingClient : RecordingClient {
        final override val calls = mutableListOf<ClientCall>()
        final override var closeInvocations: Int = 0
            private set
        final override var resourceCloseCount: Int = 0
            private set

        final override fun close() {
            closeInvocations += 1
            if (resourceCloseCount == 0) resourceCloseCount = 1
        }
    }

    private class UnavailableClient : BaseRecordingClient() {
        override suspend fun <Response> get(
            path: String,
            responseSerializer: KSerializer<Response>,
        ): LocalApiResponse<Response> = unavailable(ClientCall("GET", path))

        override suspend fun <Request, Response> post(
            path: String,
            request: Request,
            requestSerializer: KSerializer<Request>,
            responseSerializer: KSerializer<Response>,
        ): LocalApiResponse<Response> = unavailable(
            ClientCall("POST", path, json.encodeToString(requestSerializer, request)),
        )

        override suspend fun <Request, Response> put(
            path: String,
            request: Request,
            requestSerializer: KSerializer<Request>,
            responseSerializer: KSerializer<Response>,
        ): LocalApiResponse<Response> = unavailable(
            ClientCall("PUT", path, json.encodeToString(requestSerializer, request)),
        )

        override suspend fun <Response> delete(
            path: String,
            responseSerializer: KSerializer<Response>,
        ): LocalApiResponse<Response> = unavailable(ClientCall("DELETE", path))

        private fun <Response> unavailable(call: ClientCall): LocalApiResponse<Response> {
            calls += call
            throw IOException("local service unavailable")
        }
    }

    private class SuccessfulReadClient(
        private val body: ByteArray,
    ) : BaseRecordingClient() {
        override suspend fun <Response> get(
            path: String,
            responseSerializer: KSerializer<Response>,
        ): LocalApiResponse<Response> {
            calls += ClientCall("GET", path)
            return LocalApiResponse(
                status = HttpStatusCode.OK,
                body = body,
                value = json.decodeFromString(responseSerializer, body.decodeToString()),
                error = null,
            )
        }

        override suspend fun <Request, Response> post(
            path: String,
            request: Request,
            requestSerializer: KSerializer<Request>,
            responseSerializer: KSerializer<Response>,
        ): LocalApiResponse<Response> = error("POST was not expected")

        override suspend fun <Request, Response> put(
            path: String,
            request: Request,
            requestSerializer: KSerializer<Request>,
            responseSerializer: KSerializer<Response>,
        ): LocalApiResponse<Response> = error("PUT was not expected")

        override suspend fun <Response> delete(
            path: String,
            responseSerializer: KSerializer<Response>,
        ): LocalApiResponse<Response> = error("DELETE was not expected")
    }

    private class RequestErrorClient(
        private val body: ByteArray,
    ) : BaseRecordingClient() {
        override suspend fun <Response> get(
            path: String,
            responseSerializer: KSerializer<Response>,
        ): LocalApiResponse<Response> = requestError(ClientCall("GET", path))

        override suspend fun <Request, Response> post(
            path: String,
            request: Request,
            requestSerializer: KSerializer<Request>,
            responseSerializer: KSerializer<Response>,
        ): LocalApiResponse<Response> = requestError(
            ClientCall("POST", path, json.encodeToString(requestSerializer, request)),
        )

        override suspend fun <Request, Response> put(
            path: String,
            request: Request,
            requestSerializer: KSerializer<Request>,
            responseSerializer: KSerializer<Response>,
        ): LocalApiResponse<Response> = requestError(
            ClientCall("PUT", path, json.encodeToString(requestSerializer, request)),
        )

        override suspend fun <Response> delete(
            path: String,
            responseSerializer: KSerializer<Response>,
        ): LocalApiResponse<Response> = requestError(ClientCall("DELETE", path))

        private fun <Response> requestError(call: ClientCall): LocalApiResponse<Response> {
            calls += call
            return LocalApiResponse(
                status = HttpStatusCode.BadRequest,
                body = body,
                value = null,
                error = json.decodeFromString(RequestErrorEnvelope.serializer(), body.decodeToString()),
            )
        }
    }

    private data class Invocation(
        val argv: String,
        val call: ClientCall,
        val closeInvocations: Int = 1,
    )

    private data class ClientCall(
        val method: String,
        val path: String,
        val body: String? = null,
    )

    private companion object {
        val json = Json { explicitNulls = true }
        const val SERVICE_UNAVAILABLE_MESSAGE =
            "Bitbucket Helper service is unavailable. Run 'bitbucket-helper service status' and then 'bitbucket-helper service start'.\n"
    }
}

package com.mindtable.bitbuckethelper

import com.mindtable.bitbuckethelper.adapter.outbound.persistence.JooqApplicationPersistence
import com.mindtable.bitbuckethelper.application.model.StoredConfiguredRepository
import com.mindtable.bitbuckethelper.application.model.StoredInstallationConfiguration
import com.mindtable.bitbuckethelper.bootstrap.BitbucketCredentials
import com.mindtable.bitbuckethelper.bootstrap.ServiceConfiguration
import com.mindtable.bitbuckethelper.bootstrap.ServiceRuntime
import com.mindtable.bitbuckethelper.cli.UnixSocketLocalApiClient
import com.mindtable.bitbuckethelper.domain.shared.RepositoryId
import com.mindtable.bitbuckethelper.domain.shared.WorkspaceId
import com.mindtable.bitbuckethelper.generated.api.v1.model.AllConfiguredRepositoriesTarget
import com.mindtable.bitbuckethelper.generated.api.v1.model.ApiVersion
import com.mindtable.bitbuckethelper.generated.api.v1.model.GetRefreshRunResponse
import com.mindtable.bitbuckethelper.generated.api.v1.model.HealthResponse
import com.mindtable.bitbuckethelper.generated.api.v1.model.RefreshRunCompletedResult
import com.mindtable.bitbuckethelper.generated.api.v1.model.RefreshRunRegisteredResult
import com.mindtable.bitbuckethelper.generated.api.v1.model.StartRefreshRunRequest
import com.mindtable.bitbuckethelper.generated.api.v1.model.StartRefreshRunResponse
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.net.InetSocketAddress
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets.ISO_8859_1
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.sql.DriverManager
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.Base64
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir

class WalkingSkeletonEndToEndTest {
    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    fun `v1 composition crosses generated Bitbucket SQLite Quartz and both local transports`(
        @TempDir directory: Path,
    ) {
        val token = "sentinel-v1-token"
        val username = "person@example.com"
        val authorization = "Basic " + Base64.getEncoder()
            .encodeToString("$username:$token".toByteArray(UTF_8))

        val captured = captureDiagnostics {
            runBlocking {
                Files.setPosixFilePermissions(directory, PosixFilePermissions.fromString("rwx------"))
                val notificationExecutable = directory.resolve("desktop-notifications")
                Files.writeString(notificationExecutable, "not invoked")
                check(notificationExecutable.toFile().setExecutable(true, true))
                val databasePath = directory.resolve("v1.sqlite")
                val socketPath = directory.resolve("service.sock")
                val fakeBitbucket = FakeBitbucketServer.emptyRepository()
                seedConfiguration(databasePath, fakeBitbucket.baseUrl)
                val runtime = ServiceRuntime.create(
                    configuration = ServiceConfiguration(
                        httpHost = "127.0.0.1",
                        httpPort = 0,
                        databasePath = databasePath,
                        unixSocketPath = socketPath,
                        notificationExecutablePath = notificationExecutable,
                        bitbucketRequestTimeout = Duration.ofSeconds(2),
                        credentials = BitbucketCredentials(username, token),
                    ),
                    clock = Clock.fixed(Instant.parse("2026-08-15T10:15:30Z"), ZoneOffset.UTC),
                )

                try {
                    runtime.start()
                    val browserPort = runtime.resolvedHttpPort()
                    UnixSocketLocalApiClient(socketPath).use { client ->
                        val start = client.post(
                            "/api/v1/refresh-runs",
                            StartRefreshRunRequest(ApiVersion._1, AllConfiguredRepositoriesTarget()),
                            StartRefreshRunRequest.serializer(),
                            StartRefreshRunResponse.serializer(),
                        )
                        assertEquals(200, start.status.value)
                        val registered = start.value?.result as RefreshRunRegisteredResult
                        eventuallyWithin(Duration.ofSeconds(5)) {
                            client.get(
                                "/api/v1/refresh-runs/${registered.refreshRun.refreshRunId}",
                                GetRefreshRunResponse.serializer(),
                            ).value?.result as? RefreshRunCompletedResult
                        }

                        val unixHealth = requireNotNull(
                            client.get("/api/v1/health", HealthResponse.serializer()).value,
                        )
                        val browserHealth = HttpClient.newHttpClient().use { http ->
                            http.send(
                                HttpRequest.newBuilder(URI("http://127.0.0.1:$browserPort/api/v1/health"))
                                    .GET()
                                    .build(),
                                HttpResponse.BodyHandlers.ofString(UTF_8),
                            )
                        }
                        assertEquals(200, browserHealth.statusCode())
                        val decodedBrowserHealth = JSON.decodeFromString(
                            HealthResponse.serializer(),
                            browserHealth.body(),
                        )
                        assertEquals(
                            unixHealth.result.serviceInstanceId,
                            decodedBrowserHealth.result.serviceInstanceId,
                        )
                        assertEquals(unixHealth.result.startedAt, decodedBrowserHealth.result.startedAt)
                    }

                    DriverManager.getConnection("jdbc:sqlite:${databasePath.toAbsolutePath().normalize()}").use { connection ->
                        connection.createStatement().use { statement ->
                            statement.executeQuery("SELECT last_attempt_outcome FROM synchronization_checkpoint").use { result ->
                                assertTrue(result.next())
                                assertEquals("SUCCEEDED", result.getString(1))
                                assertFalse(result.next())
                            }
                        }
                    }
                    assertEquals("GET", fakeBitbucket.capturedMethod)
                    assertEquals(authorization, fakeBitbucket.capturedAuthorization)
                    assertTrue(Files.isRegularFile(databasePath))
                    assertCredentialAbsent(Files.readString(databasePath, ISO_8859_1), token, authorization)
                    assertTrue(Files.exists(socketPath))
                } finally {
                    runtime.close()
                    fakeBitbucket.close()
                }

                assertFalse(Files.exists(socketPath))
            }
        }

        val thrown = captured.result.exceptionOrNull()
        val diagnostics = listOf(
            captured.standardOut,
            captured.standardErr,
            thrown?.stackTraceToString().orEmpty(),
        ).joinToString("\n")
        assertCredentialAbsent(diagnostics, token, authorization)
        assertNull(thrown, "walking skeleton threw a diagnostic")
    }

    private suspend fun seedConfiguration(databasePath: Path, apiBaseUrl: URI) {
        JooqApplicationPersistence.open(databasePath).use { persistence ->
            persistence.inTransaction {
                val workspaceId = WorkspaceId("ws_22222222-2222-2222-2222-222222222222")
                configurationStore.save(
                    StoredInstallationConfiguration(
                        workspaceId = workspaceId,
                        bitbucketApiBaseUrl = apiBaseUrl,
                        workspaceSlug = "acme-engineering",
                        workspaceDisplayName = "Acme Engineering",
                        workspaceWebUrl = URI("https://bitbucket.org/acme-engineering"),
                        currentUserStableId = "11111111-1111-1111-1111-111111111111",
                        currentUserDisplayName = "Ada Lovelace",
                        configuredAt = Instant.parse("2026-08-15T10:15:30Z"),
                        retentionDays = 30,
                        repositories = listOf(
                            StoredConfiguredRepository(
                                id = RepositoryId("repo_33333333-3333-3333-3333-333333333333"),
                                workspaceId = workspaceId,
                                slug = "release-tools",
                                displayName = "Release Tools",
                                webUrl = URI("https://bitbucket.org/acme-engineering/release-tools"),
                                removedAt = null,
                            ),
                        ),
                    ),
                )
            }
        }
    }

    private suspend fun <T : Any> eventuallyWithin(timeout: Duration, condition: suspend () -> T?): T {
        val deadline = System.nanoTime() + timeout.toNanos()
        while (true) {
            condition()?.let { return it }
            val remainingNanos = deadline - System.nanoTime()
            if (remainingNanos <= 0) throw AssertionError("Condition was not satisfied before the monotonic deadline")
            delay(minOf(25L, (remainingNanos / 1_000_000L).coerceAtLeast(1L)))
        }
    }

    private fun <T> captureDiagnostics(block: () -> T): CapturedExecution<T> {
        val standardOut = ByteArrayOutputStream()
        val standardErr = ByteArrayOutputStream()
        val originalOut = System.out
        val originalErr = System.err
        val replacementOut = PrintStream(standardOut, true, UTF_8)
        val replacementErr = PrintStream(standardErr, true, UTF_8)
        System.setOut(replacementOut)
        System.setErr(replacementErr)
        return try {
            val result = runCatching(block)
            replacementOut.flush()
            replacementErr.flush()
            CapturedExecution(result, standardOut.toString(UTF_8), standardErr.toString(UTF_8))
        } finally {
            System.setOut(originalOut)
            System.setErr(originalErr)
            replacementOut.close()
            replacementErr.close()
        }
    }

    private fun assertCredentialAbsent(text: String, token: String, authorization: String) {
        assertFalse(text.contains(token), "token escaped its request-header boundary")
        assertFalse(text.contains(authorization), "Authorization header escaped the fake server")
    }

    private data class CapturedExecution<T>(
        val result: Result<T>,
        val standardOut: String,
        val standardErr: String,
    )

    private class FakeBitbucketServer private constructor(
        private val server: HttpServer,
        private val method: AtomicReference<String?>,
        private val authorization: AtomicReference<String?>,
    ) : AutoCloseable {
        val baseUrl: URI = URI("http://127.0.0.1:${server.address.port}/2.0")
        val capturedMethod: String? get() = method.get()
        val capturedAuthorization: String? get() = authorization.get()

        override fun close() = server.stop(0)

        companion object {
            fun emptyRepository(): FakeBitbucketServer {
                val method = AtomicReference<String?>()
                val authorization = AtomicReference<String?>()
                val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
                server.createContext("/2.0/repositories/acme-engineering/release-tools/pullrequests") { exchange ->
                    try {
                        method.set(exchange.requestMethod)
                        authorization.set(exchange.requestHeaders.getFirst("Authorization"))
                        exchange.respond(200, "{\"values\":[]}")
                    } finally {
                        exchange.close()
                    }
                }
                server.start()
                return FakeBitbucketServer(server, method, authorization)
            }
        }
    }

    private companion object {
        val JSON = Json {
            ignoreUnknownKeys = false
            explicitNulls = true
        }
    }
}

private fun HttpExchange.respond(status: Int, body: String) {
    val bytes = body.toByteArray(UTF_8)
    responseHeaders.set("Content-Type", "application/json")
    sendResponseHeaders(status, bytes.size.toLong())
    responseBody.use { it.write(bytes) }
}

package com.mindtable.bitbuckethelper

import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.charset.StandardCharsets.ISO_8859_1
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.time.Duration
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.api.parallel.ResourceLock

@ResourceLock("global-tls-test-state")
class V1SecurityAndPrivacyTest {
    @Test
    @Timeout(value = 45, unit = TimeUnit.SECONDS)
    fun `real composition enforces local boundaries and raw content has one narrow surface`(
        @TempDir directory: Path,
    ) {
        var socketPath: Path? = null
        val captured = captureDiagnostics {
            runBlocking {
                V1TestRig.create(directory).use { rig ->
                    socketPath = rig.socketPath
                    assertEquals(
                        PosixFilePermissions.fromString("rw-------"),
                        Files.getPosixFilePermissions(rig.socketPath),
                    )

                    val session = rig.browser(HttpMethod.Get, "/api/v1/browser-session")
                    assertSafeSuccess(session)
                    assertCorsDisabled(session)

                    val wrongHost = rig.browser(
                        method = HttpMethod.Get,
                        path = "/api/v1/dashboard",
                        headers = mapOf(HttpHeaders.Host to "localhost:1"),
                        authorizeMutation = false,
                    )
                    assertSafeError(wrongHost, 403, rig)

                    val wrongOrigin = rig.browser(
                        method = HttpMethod.Get,
                        path = "/api/v1/dashboard",
                        headers = mapOf(HttpHeaders.Origin to "https://attacker.invalid"),
                        authorizeMutation = false,
                    )
                    assertSafeError(wrongOrigin, 403, rig)

                    val missingOrigin = rig.browser(
                        method = HttpMethod.Post,
                        path = "/api/v1/refresh-runs",
                        body = ALL_REPOSITORIES_BODY,
                        authorizeMutation = false,
                    )
                    assertSafeError(missingOrigin, 403, rig)

                    val missingCsrf = rig.browser(
                        method = HttpMethod.Post,
                        path = "/api/v1/refresh-runs",
                        body = ALL_REPOSITORIES_BODY,
                        headers = mapOf(HttpHeaders.Origin to rig.browserBase),
                        authorizeMutation = false,
                    )
                    assertSafeError(missingCsrf, 403, rig)

                    val wrongContentType = rig.browser(
                        method = HttpMethod.Post,
                        path = "/api/v1/refresh-runs",
                        body = V1TestRig.RAW_ACTIVITY_MARKER,
                        headers = mapOf(HttpHeaders.Origin to rig.browserBase),
                        authorizeMutation = false,
                        bodyContentType = ContentType.Text.Plain,
                    )
                    assertSafeError(wrongContentType, 415, rig)

                    val unknown = rig.browser(
                        HttpMethod.Get,
                        "/api/v1/not-a-route?private-query-marker=1",
                        headers = mapOf(
                            HttpHeaders.Authorization to "Basic private-authorization-marker",
                            HttpHeaders.Cookie to "session=private-cookie-marker",
                            "X-Private-Upstream-Header" to "private-upstream-header-marker",
                        ),
                    )
                    assertSafeError(unknown, 404, rig)

                    val configured = rig.unix(
                        HttpMethod.Put,
                        "/api/v1/configuration/workspace",
                        """{"apiVersion":"1","bitbucketApiBaseUrl":"${rig.bitbucket.apiBaseUrl}","workspaceSlug":"acme-engineering"}""",
                    )
                    assertEquals("workspaceConfigured", configured.result().string("type"))
                    val added = rig.unix(
                        HttpMethod.Post,
                        "/api/v1/configuration/workspace/repositories",
                        """{"apiVersion":"1","repositorySlug":"release-tools"}""",
                    )
                    assertEquals("repositoryAdded", added.result().string("type"))
                    val registered = rig.unix(HttpMethod.Post, "/api/v1/refresh-runs", ALL_REPOSITORIES_BODY)
                    val runId = registered.result().objectValue("refreshRun").string("refreshRunId")
                    rig.awaitRefresh(runId, V1Transport.UNIX)

                    val bulk = listOf(
                        rig.browser(HttpMethod.Get, "/api/v1/dashboard"),
                        rig.unix(HttpMethod.Get, "/api/v1/pull-requests"),
                        rig.browser(HttpMethod.Get, "/api/v1/inbox"),
                        rig.unix(HttpMethod.Get, "/api/v1/synchronization"),
                    )
                    val dashboard = bulk.first().result().objectValue("snapshot")
                    val action = dashboard.array("repositoryGroups").single().jsonObject
                        .array("pullRequests").single().jsonObject
                        .array("actionItems").first().jsonObject
                    val live = rig.unix(
                        HttpMethod.Get,
                        "/api/v1/action-items/${action.string("actionItemId")}/content?activityVersion=${action.string("activityVersion")}",
                    )
                    assertEquals("contentAvailable", live.result().string("type"))
                    assertEquals(V1TestRig.LIVE_MARKDOWN, live.result().string("markdown"))

                    bulk.forEach { response ->
                        assertSafeSuccess(response)
                        assertPrivateValuesAbsent(response.body, rig)
                        assertFalse(response.body.contains(V1TestRig.RAW_ACTIVITY_MARKER))
                        assertFalse(response.body.contains(V1TestRig.LIVE_MARKDOWN))
                    }

                    val authorization = rig.expectedAuthorization
                    val databaseSurfaces = Files.list(directory).use { paths ->
                        paths.filter { path ->
                            Files.isRegularFile(path) &&
                                (path.fileName.toString().contains("sqlite") || path.fileName.toString().endsWith("-wal"))
                        }.map { Files.readString(it, ISO_8859_1) }.toList()
                    }
                    assertTrue(databaseSurfaces.isNotEmpty())
                    databaseSurfaces.forEach { surface ->
                        assertFalse(surface.contains(V1TestRig.RAW_ACTIVITY_MARKER))
                        assertFalse(surface.contains(V1TestRig.LIVE_MARKDOWN))
                        assertFalse(surface.contains(rig.token))
                        assertFalse(surface.contains(authorization))
                    }

                    eventuallyWithin(Duration.ofSeconds(5)) { Files.exists(rig.notificationArgumentsPath) }
                    val notificationArguments = Files.readString(rig.notificationArgumentsPath, UTF_8)
                    assertFalse(notificationArguments.contains(V1TestRig.RAW_ACTIVITY_MARKER))
                    assertFalse(notificationArguments.contains(V1TestRig.LIVE_MARKDOWN))
                    assertFalse(notificationArguments.contains(rig.token))
                    assertFalse(notificationArguments.contains(authorization))
                }
            }
        }

        assertFalse(Files.exists(requireNotNull(socketPath)), "Unix socket survived runtime close")
        assertFalse(captured.standardOut.contains(V1TestRig.RAW_ACTIVITY_MARKER))
        assertFalse(captured.standardErr.contains(V1TestRig.RAW_ACTIVITY_MARKER))
        assertFalse(captured.standardOut.contains(V1TestRig.LIVE_MARKDOWN))
        assertFalse(captured.standardErr.contains(V1TestRig.LIVE_MARKDOWN))
        assertFalse(captured.standardOut.contains("v1-secret-token-sentinel"))
        assertFalse(captured.standardErr.contains("v1-secret-token-sentinel"))
        PRIVATE_SURFACE_MARKERS.forEach { marker ->
            assertFalse(captured.standardOut.contains(marker), "stdout exposed $marker")
            assertFalse(captured.standardErr.contains(marker), "stderr exposed $marker")
        }
        captured.result.getOrThrow()
    }

    private fun assertSafeSuccess(response: V1HttpResponse) {
        assertEquals(200, response.status, response.body)
        assertTrue(response.body.length <= MAX_RESPONSE_CHARACTERS, "response was not bounded")
        assertCorsDisabled(response)
    }

    private fun assertSafeError(response: V1HttpResponse, expectedStatus: Int, rig: V1TestRig) {
        assertEquals(expectedStatus, response.status, response.body)
        assertTrue(response.body.length <= MAX_RESPONSE_CHARACTERS, "error was not bounded")
        assertEquals("1", response.root().string("apiVersion"))
        assertTrue(response.root().objectValue("error").string("code").isNotBlank())
        assertFalse(response.body.contains("private-query-marker"))
        PRIVATE_SURFACE_MARKERS.forEach { marker ->
            assertFalse(response.body.contains(marker), "response exposed $marker")
        }
        assertPrivateValuesAbsent(response.body, rig)
        assertCorsDisabled(response)
    }

    private fun assertPrivateValuesAbsent(text: String, rig: V1TestRig) {
        assertFalse(text.contains(rig.token), "token escaped into an API surface")
        assertFalse(text.contains(rig.expectedAuthorization), "Authorization escaped into an API surface")
        assertFalse(text.contains("private-upstream-detail"), "upstream body escaped into an API surface")
    }

    private fun assertCorsDisabled(response: V1HttpResponse) {
        assertTrue(
            response.headers.keys.none { it.startsWith("Access-Control-", ignoreCase = true) },
            "CORS header was enabled: ${response.headers}",
        )
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

    private fun eventuallyWithin(timeout: Duration, condition: () -> Boolean) {
        val deadline = System.nanoTime() + timeout.toNanos()
        while (!condition()) {
            if (System.nanoTime() >= deadline) throw AssertionError("condition was not satisfied before timeout")
            Thread.sleep(10)
        }
    }

    private data class CapturedExecution<T>(
        val result: Result<T>,
        val standardOut: String,
        val standardErr: String,
    )

    private companion object {
        const val ALL_REPOSITORIES_BODY =
            """{"apiVersion":"1","target":{"type":"allConfiguredRepositories"}}"""
        const val MAX_RESPONSE_CHARACTERS = 16_384
        val PRIVATE_SURFACE_MARKERS = listOf(
            "private-query-marker",
            "private-authorization-marker",
            "private-cookie-marker",
            "private-upstream-header-marker",
        )
    }
}

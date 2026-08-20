package com.mindtable.bitbuckethelper.adapter.inbound.http

import com.mindtable.bitbuckethelper.application.model.HealthComponent
import com.mindtable.bitbuckethelper.application.model.HealthComponentSnapshot
import com.mindtable.bitbuckethelper.application.model.HealthSnapshot
import com.mindtable.bitbuckethelper.application.model.HealthStatus
import com.mindtable.bitbuckethelper.application.model.GetRefreshRunResult
import com.mindtable.bitbuckethelper.application.model.RefreshRunSnapshot
import com.mindtable.bitbuckethelper.application.model.StartRefreshRunResult
import com.mindtable.bitbuckethelper.application.port.inbound.GetHealthSnapshot
import com.mindtable.bitbuckethelper.application.port.inbound.GetRefreshRun
import com.mindtable.bitbuckethelper.application.port.inbound.StartRefreshRun
import com.mindtable.bitbuckethelper.domain.shared.RefreshRunId
import com.mindtable.bitbuckethelper.observability.BackendEventRecorder
import com.mindtable.bitbuckethelper.observability.BackendLogEvent
import com.mindtable.bitbuckethelper.observability.BackendLogLevel
import com.mindtable.bitbuckethelper.observability.MonotonicTimeSource
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.application.call
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.testing.testApplication
import java.time.Instant
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test

class ApiV1ObservabilityTest {
    @Test
    fun `successful polling emits one debug event with the response request id`() = testApplication {
        val events = mutableListOf<BackendLogEvent>()
        var now = 1_000_000L
        val time = MonotonicTimeSource {
            now += 3_000_000L
            now
        }

        application {
            installApiV1(
                transportKind = TransportKind.BROWSER,
                backendEventRecorder = BackendEventRecorder(events::add),
                monotonicTimeSource = time,
            ) {
                installHealthRoutes(GetHealthSnapshot { healthSnapshot() })
            }
        }

        val response = client.get("/api/v1/health?private=never-log")
        val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        val requestId = body.getValue("requestId").jsonPrimitive.content

        assertEquals(HttpStatusCode.OK, response.status)
        val event = events.single() as BackendLogEvent.HttpRequestCompleted
        assertEquals(requestId, event.requestId)
        assertEquals("browser", event.transport)
        assertEquals("GET", event.method)
        assertEquals("health", event.operation)
        assertEquals(200, event.status)
        assertEquals("health_snapshot", event.outcome)
        assertEquals(3L, event.durationMilliseconds)
        assertEquals(false, event.mutation)
        assertFalse(event.toString().contains("never-log"))
    }

    @Test
    fun `malformed JSON emits one warning request event without request data`() = testApplication {
        val events = mutableListOf<BackendLogEvent>()
        application {
            installApiV1(
                transportKind = TransportKind.UNIX,
                backendEventRecorder = BackendEventRecorder(events::add),
            ) {
                post("/test/body") {
                    call.observeApiOperation(ApiOperation.CONFIGURE_WORKSPACE)
                    call.receiveApiV1<JsonObject>()
                    call.observeApiOutcome(ApiOutcome.WORKSPACE_CONFIGURED)
                }
            }
        }

        val response = client.post("/api/v1/test/body?private=query-sentinel") {
            contentType(ContentType.Application.Json)
            header(HttpHeaders.Authorization, "Bearer header-sentinel")
            setBody("{\"privateBody\":sentinel-body")
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
        val event = events.single() as BackendLogEvent.HttpRequestRejected
        assertEquals("unix", event.transport)
        assertEquals("configure_workspace", event.operation)
        assertEquals("INVALID_REQUEST", event.requestErrorCode)
        assertEquals(BackendLogLevel.WARN, event.level)
        assertFalse(event.toString().contains("query-sentinel"))
        assertFalse(event.toString().contains("header-sentinel"))
        assertFalse(event.toString().contains("sentinel-body"))
    }

    @Test
    fun `browser authorization rejection emits one warning event`() = testApplication {
        val events = mutableListOf<BackendLogEvent>()
        val security = BrowserSecurity(resolvedPort = { 49152 }, serviceInstanceId = "svc_test")
        application {
            installBrowserSecurity(security)
            installApiV1(
                transportKind = TransportKind.BROWSER,
                backendEventRecorder = BackendEventRecorder(events::add),
            ) {
                installHealthRoutes(GetHealthSnapshot { healthSnapshot() })
            }
        }

        val response = client.get("/api/v1/health?private=query-sentinel") {
            header(HttpHeaders.Authorization, "Bearer header-sentinel")
        }

        assertEquals(HttpStatusCode.Forbidden, response.status)
        val event = events.single() as BackendLogEvent.HttpRequestRejected
        assertEquals("health", event.operation)
        assertEquals("FORBIDDEN", event.requestErrorCode)
        assertEquals(BackendLogLevel.WARN, event.level)
        assertFalse(event.toString().contains("query-sentinel"))
        assertFalse(event.toString().contains("header-sentinel"))
    }

    @Test
    fun `missing route uses fixed operation and does not record private path`() = testApplication {
        val events = mutableListOf<BackendLogEvent>()
        application {
            installApiV1(
                transportKind = TransportKind.UNIX,
                backendEventRecorder = BackendEventRecorder(events::add),
            )
        }

        val response = client.get("/api/v1/private-path-sentinel?private=query-sentinel")

        assertEquals(HttpStatusCode.NotFound, response.status)
        val event = events.single() as BackendLogEvent.HttpRequestRejected
        assertEquals("route_not_found", event.operation)
        assertEquals("ROUTE_NOT_FOUND", event.requestErrorCode)
        assertEquals(BackendLogLevel.WARN, event.level)
        assertFalse(event.toString().contains("private-path-sentinel"))
        assertFalse(event.toString().contains("query-sentinel"))
    }

    @Test
    fun `known route method failure keeps fixed operation`() = testApplication {
        val events = mutableListOf<BackendLogEvent>()
        application {
            installApiV1(
                transportKind = TransportKind.UNIX,
                backendEventRecorder = BackendEventRecorder(events::add),
            ) {
                installHealthRoutes(GetHealthSnapshot { healthSnapshot() })
            }
        }

        val response = client.patch("/api/v1/health")

        assertEquals(HttpStatusCode.MethodNotAllowed, response.status)
        val event = events.single() as BackendLogEvent.HttpRequestRejected
        assertEquals("health", event.operation)
        assertEquals("METHOD_NOT_ALLOWED", event.requestErrorCode)
        assertEquals(BackendLogLevel.WARN, event.level)
    }

    @Test
    fun `unsupported media type emits one warning event`() = testApplication {
        val events = mutableListOf<BackendLogEvent>()
        application {
            installApiV1(
                transportKind = TransportKind.UNIX,
                backendEventRecorder = BackendEventRecorder(events::add),
            ) {
                post("/test/body") {
                    call.observeApiOperation(ApiOperation.CONFIGURE_WORKSPACE)
                    call.receiveApiV1<JsonObject>()
                    call.observeApiOutcome(ApiOutcome.WORKSPACE_CONFIGURED)
                }
            }
        }

        val response = client.post("/api/v1/test/body") {
            contentType(ContentType.Text.Plain)
            setBody("private-body-sentinel")
        }

        assertEquals(HttpStatusCode.UnsupportedMediaType, response.status)
        val event = events.single() as BackendLogEvent.HttpRequestRejected
        assertEquals("UNSUPPORTED_CONTENT_TYPE", event.requestErrorCode)
        assertEquals(BackendLogLevel.WARN, event.level)
        assertFalse(event.toString().contains("private-body-sentinel"))
    }

    @Test
    fun `unexpected failure emits one error event with a redacted diagnostic`() = testApplication {
        val events = mutableListOf<BackendLogEvent>()
        application {
            installApiV1(
                transportKind = TransportKind.UNIX,
                backendEventRecorder = BackendEventRecorder(events::add),
            ) {
                get("/test/failure") {
                    call.observeApiOperation(ApiOperation.HEALTH)
                    error("private-token-sentinel private-body-sentinel /private/path\\u001b[31m")
                }
            }
        }

        val response = client.get("/api/v1/test/failure?private=query-sentinel") {
            header(HttpHeaders.Cookie, "private-cookie-sentinel")
        }

        assertEquals(HttpStatusCode.InternalServerError, response.status)
        val event = events.single() as BackendLogEvent.HttpRequestFailed
        assertEquals("health", event.operation)
        assertEquals(500, event.status)
        assertEquals(BackendLogLevel.ERROR, event.level)
        assertFalse(event.toString().contains("private-token-sentinel"))
        assertFalse(event.toString().contains("private-body-sentinel"))
        assertFalse(event.toString().contains("private/path"))
        assertFalse(event.toString().contains("query-sentinel"))
        assertFalse(event.toString().contains("private-cookie-sentinel"))
    }

    @Test
    fun `registered refresh outcome carries only its validated run id`() = testApplication {
        val events = mutableListOf<BackendLogEvent>()
        val refreshRun = RefreshRunSnapshot(
            id = RefreshRunId("rr_test"),
            createdAt = Instant.parse("2026-08-20T00:00:00Z"),
            expiresAt = Instant.parse("2026-08-20T01:00:00Z"),
            repositories = emptyList(),
        )
        application {
            installApiV1(
                transportKind = TransportKind.UNIX,
                backendEventRecorder = BackendEventRecorder(events::add),
            ) {
                installRefreshRunRoutes(
                    RefreshRunApiV1Dependencies(
                        startRefreshRun = StartRefreshRun {
                            StartRefreshRunResult.RefreshRunRegistered(refreshRun, emptyList())
                        },
                        getRefreshRun = GetRefreshRun { id -> GetRefreshRunResult.RefreshRunUnavailable(id) },
                    ),
                )
            }
        }

        val response = client.post("/api/v1/refresh-runs") {
            contentType(ContentType.Application.Json)
            setBody("{\"apiVersion\":\"1\",\"target\":{\"type\":\"allConfiguredRepositories\"}}")
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val event = events.single() as BackendLogEvent.HttpRequestCompleted
        assertEquals("start_refresh_run", event.operation)
        assertEquals("refresh_run_registered", event.outcome)
        assertEquals("rr_test", event.refreshRunId)
        assertEquals(null, event.repositoryId)
        assertEquals(BackendLogLevel.INFO, event.level)
    }

    private fun healthSnapshot() = HealthSnapshot(
        status = HealthStatus.HEALTHY,
        serviceVersion = "test",
        supportedApiVersion = "1",
        serviceInstanceId = "svc_test",
        startedAt = Instant.parse("2026-08-20T00:00:00Z"),
        components = listOf(
            HealthComponentSnapshot(
                component = HealthComponent.PERSISTENCE,
                status = HealthStatus.HEALTHY,
                safeCode = "OK",
            ),
        ),
    )
}

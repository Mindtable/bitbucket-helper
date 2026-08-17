package com.mindtable.bitbuckethelper.adapter.inbound.http

import com.mindtable.bitbuckethelper.application.model.HealthComponent
import com.mindtable.bitbuckethelper.application.model.HealthComponentSnapshot
import com.mindtable.bitbuckethelper.application.model.HealthSnapshot
import com.mindtable.bitbuckethelper.application.model.HealthStatus
import com.mindtable.bitbuckethelper.application.port.inbound.GetHealthSnapshot
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.cio.CIO as ServerCIO
import io.ktor.server.cio.CIOApplicationEngine
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import java.time.Instant
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test

class HealthRoutesTest {
    @Test
    fun `health maps every status and component to exact typed 200 snapshots`() = runBlocking {
        val snapshots = ArrayDeque(
            listOf(
                healthSnapshot(HealthStatus.HEALTHY),
                healthSnapshot(HealthStatus.DEGRADED),
                healthSnapshot(HealthStatus.UNHEALTHY),
            ),
        )
        withHealthServer(GetHealthSnapshot { snapshots.removeFirst() }) { client, port ->
            val expectedStatuses = listOf("healthy", "degraded", "unhealthy")
            for (expectedStatus in expectedStatuses) {
                val response = client.get("http://127.0.0.1:$port/api/v1/health")
                val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
                val result = body.getValue("result").jsonObject

                assertEquals(HttpStatusCode.OK, response.status)
                assertEquals("no-store", response.headers[HttpHeaders.CacheControl])
                assertEquals("healthSnapshot", result.getValue("type").jsonPrimitive.content)
                assertEquals(expectedStatus, result.getValue("status").jsonPrimitive.content)
                assertEquals("0.1.0-test", result.getValue("serviceVersion").jsonPrimitive.content)
                assertEquals("1", result.getValue("supportedApiVersion").jsonPrimitive.content)
                assertEquals("svc_health-test", result.getValue("serviceInstanceId").jsonPrimitive.content)
                assertEquals("2026-08-17T07:00:00Z", result.getValue("startedAt").jsonPrimitive.content)
                assertEquals(
                    listOf(
                        Triple("persistence", "healthy", "READY"),
                        Triple("scheduler", "degraded", "STARTING"),
                        Triple("installationPath", "unhealthy", "INVALID"),
                        Triple("notificationAdapter", "healthy", "AVAILABLE"),
                    ),
                    result.getValue("components").jsonArray.map { component ->
                        val value = component.jsonObject
                        Triple(
                            value.getValue("component").jsonPrimitive.content,
                            value.getValue("status").jsonPrimitive.content,
                            value.getValue("safeCode").jsonPrimitive.content,
                        )
                    },
                )
            }
        }
    }

    @Test
    fun `health failure uses one fixed safe server message`() = runBlocking {
        val secret = "sentinel-credential /private/sentinel.db?query=secret Authorization"
        withHealthServer(GetHealthSnapshot { error(secret) }) { client, port ->
            val response = client.get("http://127.0.0.1:$port/api/v1/health")
            val text = response.bodyAsText()
            val error = Json.parseToJsonElement(text).jsonObject.getValue("error").jsonObject

            assertEquals(HttpStatusCode.InternalServerError, response.status)
            assertEquals("INTERNAL_SERVER_ERROR", error.getValue("code").jsonPrimitive.content)
            assertEquals("The server could not process the request.", error.getValue("message").jsonPrimitive.content)
            assertFalse(text.contains(secret))
            assertFalse(text.contains("Authorization"))
        }
    }

    private suspend fun withHealthServer(
        getHealthSnapshot: GetHealthSnapshot,
        block: suspend (HttpClient, Int) -> Unit,
    ) {
        val server = embeddedServer(ServerCIO, host = "127.0.0.1", port = 0) {
            installApiV1(TransportKind.UNIX) { installHealthRoutes(getHealthSnapshot) }
        }.start(wait = false)
        val client = HttpClient(CIO)
        try {
            block(client, server.resolvedPort())
        } finally {
            client.close()
            server.stop(gracePeriodMillis = 100, timeoutMillis = 2_000)
        }
    }

    private suspend fun EmbeddedServer<CIOApplicationEngine, CIOApplicationEngine.Configuration>.resolvedPort(): Int =
        engine.resolvedConnectors().single().port

    private fun healthSnapshot(status: HealthStatus) = HealthSnapshot(
        status = status,
        serviceVersion = "0.1.0-test",
        supportedApiVersion = "1",
        serviceInstanceId = "svc_health-test",
        startedAt = Instant.parse("2026-08-17T07:00:00Z"),
        components = listOf(
            HealthComponentSnapshot(HealthComponent.PERSISTENCE, HealthStatus.HEALTHY, "READY"),
            HealthComponentSnapshot(HealthComponent.SCHEDULER, HealthStatus.DEGRADED, "STARTING"),
            HealthComponentSnapshot(HealthComponent.INSTALLATION_PATH, HealthStatus.UNHEALTHY, "INVALID"),
            HealthComponentSnapshot(HealthComponent.NOTIFICATION_ADAPTER, HealthStatus.HEALTHY, "AVAILABLE"),
        ),
    )
}

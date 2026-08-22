package com.mindtable.bitbuckethelper.adapter.inbound.http

import com.mindtable.bitbuckethelper.observability.BackendEventRecorder
import com.mindtable.bitbuckethelper.observability.BackendLogEvent
import com.mindtable.bitbuckethelper.observability.BackendLogLevel
import com.mindtable.bitbuckethelper.observability.MonotonicTimeSource
import com.mindtable.bitbuckethelper.observability.SafeExceptionDiagnostic
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.request
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.testing.testApplication
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SpaObservabilityTest {
    @Test
    fun `served shell emits exactly one completed browser event`() = testApplication {
        val events = mutableListOf<BackendLogEvent>()
        application { installObservedSpa(events) }

        val response = client.get("/?private-query-sentinel") { exactHost() }

        assertEquals(HttpStatusCode.OK, response.status)
        val completed = events.single() as BackendLogEvent.HttpRequestCompleted
        assertEquals(response.headers["X-Request-ID"], completed.requestId)
        assertEquals("spa_shell", completed.operation)
        assertEquals("spa_served", completed.outcome)
        assertEquals("browser", completed.transport)
        assertEquals("GET", completed.method)
        assertEquals(200, completed.status)
        assertEquals(3L, completed.durationMilliseconds)
        assertEquals(false, completed.mutation)
        assertEquals(BackendLogLevel.DEBUG, completed.level)
        assertFalse(completed.toString().contains("private-query-sentinel"))
    }

    @Test
    fun `static rejection categories use fixed operations and error codes`() = testApplication {
        val events = mutableListOf<BackendLogEvent>()
        application { installObservedSpa(events) }

        client.get("/assets/private-path-sentinel.txt") { exactHost() }
        client.get("/private-path-sentinel") { exactHost() }
        client.post("/") { exactHost() }
        client.get("/assets/app.js") { header(HttpHeaders.Host, "attacker.invalid") }

        assertEquals(4, events.size)
        val rejected = events.map { it as BackendLogEvent.HttpRequestRejected }
        assertEquals(
            listOf("spa_asset", "spa_unknown", "spa_shell", "spa_asset"),
            rejected.map { it.operation },
        )
        assertEquals(
            listOf("ROUTE_NOT_FOUND", "ROUTE_NOT_FOUND", "METHOD_NOT_ALLOWED", "FORBIDDEN"),
            rejected.map { it.requestErrorCode },
        )
        assertTrue(rejected.all { it.level == BackendLogLevel.WARN })
        assertTrue(rejected.none { it.toString().contains("private-path-sentinel") })
    }

    @Test
    fun `loader failure emits one failed event with sanitized diagnostics`() = testApplication {
        val message = "private-exception-message-sentinel"
        val events = mutableListOf<BackendLogEvent>()
        application {
            installObservedSpa(
                events = events,
                assets = SpaAssets(SpaResourceReader { throw IllegalStateException(message) }),
            )
        }

        val response = client.get("/assets/private-path-sentinel.js?private-query-sentinel") {
            exactHost()
            header(HttpHeaders.Cookie, "private-cookie-sentinel")
            header(HttpHeaders.Origin, TEST_ORIGIN)
        }

        assertEquals(HttpStatusCode.InternalServerError, response.status)
        val failed = events.single() as BackendLogEvent.HttpRequestFailed
        assertEquals("spa_asset", failed.operation)
        assertEquals("browser", failed.transport)
        assertEquals("GET", failed.method)
        assertEquals(500, failed.status)
        assertEquals(3L, failed.durationMilliseconds)
        assertEquals(BackendLogLevel.ERROR, failed.level)
        val diagnostic = SafeExceptionDiagnostic.from(failed.failure)
        assertTrue(diagnostic.exceptionTypes.contains("java.lang.IllegalStateException"))
        listOf(message, "private-path-sentinel", "private-query-sentinel", "private-cookie-sentinel")
            .forEach { sentinel -> assertFalse(failed.toString().contains(sentinel)) }
    }

    @Test
    fun `recorder failure never changes completed rejected or failed static responses`() = testApplication {
        val assets = SpaAssets(SpaResourceReader { name ->
            when (name) {
                "spa/index.html" -> SHELL.encodeToByteArray()
                "spa/assets/failure.js" -> throw IllegalStateException("private-loader-message-sentinel")
                else -> null
            }
        })
        application {
            installObservedSpa(
                events = mutableListOf(),
                assets = assets,
                recorder = BackendEventRecorder { throw AssertionError("private-recorder-message-sentinel") },
            )
        }

        val completed = client.get("/") { exactHost() }
        val rejected = client.get("/missing") { exactHost() }
        val failed = client.get("/assets/failure.js") { exactHost() }

        assertEquals(HttpStatusCode.OK, completed.status)
        assertEquals(SHELL, completed.bodyAsText())
        assertEquals(HttpStatusCode.NotFound, rejected.status)
        assertEquals("", rejected.bodyAsText())
        assertEquals(HttpStatusCode.InternalServerError, failed.status)
        assertEquals("", failed.bodyAsText())
    }

    @Test
    fun `arbitrary method is recorded only as OTHER and never retained`() = testApplication {
        val events = mutableListOf<BackendLogEvent>()
        application { installObservedSpa(events) }

        val response = client.request("/") {
            method = HttpMethod(CUSTOM_METHOD_SENTINEL)
            exactHost()
        }

        assertEquals(HttpStatusCode.MethodNotAllowed, response.status)
        val rejected = events.single() as BackendLogEvent.HttpRequestRejected
        assertEquals("OTHER", rejected.method)
        assertEquals("METHOD_NOT_ALLOWED", rejected.requestErrorCode)
        assertFalse(rejected.toString().contains(CUSTOM_METHOD_SENTINEL))
    }

    private fun Application.installObservedSpa(
        events: MutableList<BackendLogEvent>,
        assets: SpaAssets = testAssets(),
        recorder: BackendEventRecorder = BackendEventRecorder(events::add),
    ) {
        installBrowserSecurity(BrowserSecurity({ TEST_PORT }, "svc_observation-test"))
        installApiV1(TransportKind.BROWSER, recorder, deterministicTimeSource())
        installSpa(assets, recorder, deterministicTimeSource())
    }

    private fun deterministicTimeSource(): MonotonicTimeSource {
        var now = 1_000_000L
        return MonotonicTimeSource { now.also { now += 3_000_000L } }
    }

    private fun io.ktor.client.request.HttpRequestBuilder.exactHost() {
        header(HttpHeaders.Host, TEST_AUTHORITY)
    }

    private companion object {
        const val TEST_PORT = 49152
        const val TEST_AUTHORITY = "127.0.0.1:$TEST_PORT"
        const val TEST_ORIGIN = "http://$TEST_AUTHORITY"
        const val SHELL = "<!doctype html><div id=app></div>"
        const val CUSTOM_METHOD_SENTINEL = "PRIVATE_CUSTOM_METHOD_SENTINEL"

        fun testAssets() = SpaAssets(SpaResourceReader { name ->
            when (name) {
                "spa/index.html" -> SHELL.encodeToByteArray()
                "spa/assets/app.js" -> "console.log('spa')".encodeToByteArray()
                else -> null
            }
        })
    }
}

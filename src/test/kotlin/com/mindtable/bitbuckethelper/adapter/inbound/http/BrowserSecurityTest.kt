package com.mindtable.bitbuckethelper.adapter.inbound.http

import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.application.call
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class BrowserSecurityTest {
    @Test
    fun `browser reads require the exact configured Host and accept only the exact optional Origin`() =
        testApplication {
            val invocations = InvocationCounter()
            val security = BrowserSecurity { TEST_PORT }
            application { installSecuredProbe(security, invocations) }

            val acceptedWithoutOrigin = client.get(PROBE_PATH) { exactHost() }
            val acceptedWithOrigin = client.get(PROBE_PATH) {
                exactHost()
                header(HttpHeaders.Origin, TEST_ORIGIN)
            }
            val wrongHosts = listOf("localhost:$TEST_PORT", "127.0.0.1:${TEST_PORT + 1}")
                .map { host -> client.get(PROBE_PATH) { header(HttpHeaders.Host, host) } }
            val wrongOrigin = client.get(PROBE_PATH) {
                exactHost()
                header(HttpHeaders.Origin, "http://localhost:$TEST_PORT")
            }

            assertEquals(HttpStatusCode.OK, acceptedWithoutOrigin.status)
            assertEquals(HttpStatusCode.OK, acceptedWithOrigin.status)
            for (response in wrongHosts) assertForbidden(response)
            assertForbidden(wrongOrigin)
            assertEquals(2, invocations.reads)
        }

    @Test
    fun `browser mutations require exact Origin JSON and CSRF before invoking a route`() = testApplication {
        val invocations = InvocationCounter()
        val security = BrowserSecurity { TEST_PORT }
        application { installSecuredProbe(security, invocations) }

        val rejected = listOf(
            client.post(PROBE_PATH) {
                exactHost()
                contentType(ContentType.Application.Json)
                header(CSRF_HEADER, security.csrfToken)
                setBody("{}")
            },
            client.post(PROBE_PATH) {
                exactHost()
                header(HttpHeaders.Origin, "http://localhost:$TEST_PORT")
                contentType(ContentType.Application.Json)
                header(CSRF_HEADER, security.csrfToken)
                setBody("{}")
            },
            client.post(PROBE_PATH) {
                exactHost()
                header(HttpHeaders.Origin, TEST_ORIGIN)
                contentType(ContentType.Text.Plain)
                header(CSRF_HEADER, security.csrfToken)
                setBody("sentinel-raw-body")
            },
            client.post(PROBE_PATH) {
                exactHost()
                header(HttpHeaders.Origin, TEST_ORIGIN)
                contentType(ContentType.Application.Json)
                header(CSRF_HEADER, "sentinel-wrong-csrf")
                setBody("{}")
            },
        )

        assertForbidden(rejected[0])
        assertForbidden(rejected[1])
        assertEquals(HttpStatusCode.UnsupportedMediaType, rejected[2].status)
        assertForbidden(rejected[3])
        assertEquals(0, invocations.mutations)

        val accepted = client.post(PROBE_PATH) {
            exactHost()
            header(HttpHeaders.Origin, TEST_ORIGIN)
            contentType(ContentType.Application.Json)
            header(CSRF_HEADER, security.csrfToken)
            setBody("{}")
        }

        assertEquals(HttpStatusCode.OK, accepted.status)
        assertEquals(1, invocations.mutations)
    }

    @Test
    fun `browser session state is random in memory and changes with a restarted server`() {
        val first = readNewBrowserSession()
        val second = readNewBrowserSession()

        assertTrue(first.csrfToken.length >= 32)
        assertTrue(first.serviceInstanceId.matches(Regex("^svc_[A-Za-z0-9_-]+$")))
        assertNotEquals(first.csrfToken, second.csrfToken)
        assertNotEquals(first.serviceInstanceId, second.serviceInstanceId)
    }

    @Test
    fun `browser responses never emit CORS headers`() = testApplication {
        val security = BrowserSecurity { TEST_PORT }
        application { installSecuredProbe(security, InvocationCounter()) }

        val accepted = client.get(PROBE_PATH) {
            exactHost()
            header(HttpHeaders.Origin, TEST_ORIGIN)
        }
        val rejected = client.get(PROBE_PATH) {
            exactHost()
            header(HttpHeaders.Origin, "https://attacker.invalid")
        }

        listOf(accepted, rejected).forEach { response ->
            assertFalse(response.headers.contains(HttpHeaders.AccessControlAllowOrigin))
            assertFalse(response.headers.contains(HttpHeaders.AccessControlAllowHeaders))
            assertFalse(response.headers.contains(HttpHeaders.AccessControlAllowMethods))
            assertFalse(response.headers.contains(HttpHeaders.AccessControlAllowCredentials))
        }
    }

    private fun io.ktor.server.application.Application.installSecuredProbe(
        security: BrowserSecurity,
        invocations: InvocationCounter,
    ) {
        installBrowserSecurity(security)
        installApiV1(TransportKind.BROWSER) {
            installBrowserSessionRoute(security)
            get("/probe") {
                invocations.reads += 1
                call.respondText("read")
            }
            post("/probe") {
                invocations.mutations += 1
                call.respondText("mutation")
            }
        }
    }

    private fun readNewBrowserSession(): BrowserSessionDocument {
        lateinit var document: BrowserSessionDocument
        testApplication {
            val security = BrowserSecurity { TEST_PORT }
            application {
                installBrowserSecurity(security)
                installApiV1(TransportKind.BROWSER) { installBrowserSessionRoute(security) }
            }

            val response = client.get("/api/v1/browser-session") { exactHost() }
            assertEquals(HttpStatusCode.OK, response.status)
            val result = Json.parseToJsonElement(response.bodyAsText()).jsonObject
                .getValue("result").jsonObject
            document = BrowserSessionDocument(
                csrfToken = result.getValue("csrfToken").jsonPrimitive.content,
                serviceInstanceId = result.getValue("serviceInstanceId").jsonPrimitive.content,
            )
        }
        return document
    }

    private fun io.ktor.client.request.HttpRequestBuilder.exactHost() {
        header(HttpHeaders.Host, TEST_AUTHORITY)
    }

    private suspend fun assertForbidden(response: HttpResponse) {
        assertEquals(HttpStatusCode.Forbidden, response.status)
        val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertEquals("FORBIDDEN", body.getValue("error").jsonObject.getValue("code").jsonPrimitive.content)
        assertEquals("no-store", response.headers[HttpHeaders.CacheControl])
    }

    private data class BrowserSessionDocument(
        val csrfToken: String,
        val serviceInstanceId: String,
    )

    private class InvocationCounter {
        var reads: Int = 0
        var mutations: Int = 0
    }

    private companion object {
        const val TEST_PORT = 49152
        const val TEST_AUTHORITY = "127.0.0.1:$TEST_PORT"
        const val TEST_ORIGIN = "http://$TEST_AUTHORITY"
        const val PROBE_PATH = "/api/v1/probe"
        const val CSRF_HEADER = "X-CSRF-Token"
    }
}

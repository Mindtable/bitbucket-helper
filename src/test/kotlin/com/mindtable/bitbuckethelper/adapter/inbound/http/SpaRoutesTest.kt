package com.mindtable.bitbuckethelper.adapter.inbound.http

import com.mindtable.bitbuckethelper.observability.BackendEventRecorder
import com.mindtable.bitbuckethelper.observability.BackendLogEvent
import com.mindtable.bitbuckethelper.observability.MonotonicTimeSource
import io.ktor.client.request.get
import io.ktor.client.request.head
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.withCharset
import io.ktor.server.application.Application
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SpaRoutesTest {
    @Test
    fun `root and index serve the shell with hardened browser headers and no CORS`() = testApplication {
        val events = mutableListOf<BackendLogEvent>()
        application { installTestSpa(events = events) }

        val root = client.get("/") { exactHost() }
        val index = client.get("/index.html") { exactHost() }

        listOf(root, index).forEach { response ->
            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals(ContentType.Text.Html.withCharset(Charsets.UTF_8), response.contentType())
            assertEquals(SHELL_BYTES.decodeToString(), response.bodyAsText())
            assertSpaPolicy(response, "no-store")
        }
        assertEquals(listOf("spa_shell", "spa_shell"), events.map { it.operation })
    }

    @Test
    fun `hashed JavaScript and CSS assets are immutable and HEAD matches GET metadata`() = testApplication {
        val events = mutableListOf<BackendLogEvent>()
        application { installTestSpa(events = events) }

        val javascript = client.get("/assets/app-a1b2c3.js") { exactHost() }
        val css = client.get("/assets/app-a1b2c3.css") { exactHost() }
        val head = client.head("/assets/app-a1b2c3.js") { exactHost() }
        val shellHead = client.head("/") { exactHost() }

        assertEquals(HttpStatusCode.OK, javascript.status)
        assertEquals(ContentType.parse("text/javascript; charset=UTF-8"), javascript.contentType())
        assertEquals(JS_BYTES.decodeToString(), javascript.bodyAsText())
        assertSpaPolicy(javascript, IMMUTABLE_CACHE)
        assertEquals(ContentType.Text.CSS.withCharset(Charsets.UTF_8), css.contentType())
        assertEquals(CSS_BYTES.decodeToString(), css.bodyAsText())
        assertSpaPolicy(css, IMMUTABLE_CACHE)
        assertEquals(HttpStatusCode.OK, head.status)
        assertEquals(javascript.contentType(), head.contentType())
        assertEquals(JS_BYTES.size.toString(), head.headers[HttpHeaders.ContentLength])
        assertEquals("", head.bodyAsText())
        assertSpaPolicy(head, IMMUTABLE_CACHE)
        assertEquals(HttpStatusCode.OK, shellHead.status)
        assertEquals(ContentType.Text.Html.withCharset(Charsets.UTF_8), shellHead.contentType())
        assertEquals(SHELL_BYTES.size.toString(), shellHead.headers[HttpHeaders.ContentLength])
        assertEquals("", shellHead.bodyAsText())
        assertSpaPolicy(shellHead, "no-store")
        assertEquals(
            listOf("spa_asset", "spa_asset", "spa_asset", "spa_shell"),
            events.map { it.operation },
        )
    }

    @Test
    fun `wrong Host and wrong optional Origin return fixed forbidden before asset lookup`() = testApplication {
        val reads = mutableListOf<String>()
        val assets = testAssets(reads)
        val events = mutableListOf<BackendLogEvent>()
        application { installTestSpa(assets = assets, events = events) }

        val wrongHost = client.get("/assets/app-a1b2c3.js") {
            header(HttpHeaders.Host, "localhost:$TEST_PORT")
        }
        val wrongOrigin = client.get("/") {
            exactHost()
            header(HttpHeaders.Origin, "http://localhost:$TEST_PORT")
        }

        listOf(wrongHost, wrongOrigin).forEach { response ->
            assertEquals(HttpStatusCode.Forbidden, response.status)
            assertEquals("", response.bodyAsText())
            assertSpaPolicy(response, "no-store")
        }
        assertTrue(reads.isEmpty())
        assertEquals(listOf("FORBIDDEN", "FORBIDDEN"), events.map { it.requestErrorCode })
    }

    @Test
    fun `unsupported method missing paths and traversal return fixed empty transport errors`() = testApplication {
        val events = mutableListOf<BackendLogEvent>()
        application { installTestSpa(events = events) }

        val method = client.post("/") { exactHost() }
        val missing = client.get("/private-path-sentinel") { exactHost() }
        val traversal = client.get("/assets/../private-path-sentinel.js") { exactHost() }
        val encodedTraversal = client.get("/assets/%2e%2e/private-path-sentinel.js") { exactHost() }

        assertEquals(HttpStatusCode.MethodNotAllowed, method.status)
        assertEquals(HttpStatusCode.NotFound, missing.status)
        assertEquals(HttpStatusCode.NotFound, traversal.status)
        assertEquals(HttpStatusCode.NotFound, encodedTraversal.status)
        listOf(method, missing, traversal, encodedTraversal).forEach { response ->
            assertEquals("", response.bodyAsText())
            assertSpaPolicy(response, "no-store")
        }
        assertEquals(
            listOf("METHOD_NOT_ALLOWED", "ROUTE_NOT_FOUND", "ROUTE_NOT_FOUND", "ROUTE_NOT_FOUND"),
            events.map { it.requestErrorCode },
        )
    }

    @Test
    fun `loader failure returns fixed empty 500 without exception details`() = testApplication {
        val message = "private-loader-message-sentinel"
        val assets = SpaAssets(SpaResourceReader { throw IllegalStateException(message) })
        val events = mutableListOf<BackendLogEvent>()
        application { installTestSpa(assets = assets, events = events) }

        val response = client.get("/") { exactHost() }

        assertEquals(HttpStatusCode.InternalServerError, response.status)
        assertEquals("", response.bodyAsText())
        assertFalse(response.toString().contains(message))
        assertSpaPolicy(response, "no-store")
        val event = events.single() as BackendLogEvent.HttpRequestFailed
        assertFalse(event.toString().contains(message))
    }

    @Test
    fun `unknown API route keeps its versioned JSON envelope`() = testApplication {
        val events = mutableListOf<BackendLogEvent>()
        application { installTestSpa(events = events) }

        val response = client.get("/api/v1/private-sentinel") { exactHost() }

        assertEquals(HttpStatusCode.NotFound, response.status)
        val document = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertEquals("1", document.getValue("apiVersion").jsonPrimitive.content)
        assertEquals(
            "ROUTE_NOT_FOUND",
            document.getValue("error").jsonObject.getValue("code").jsonPrimitive.content,
        )
        assertEquals("no-store", response.headers[HttpHeaders.CacheControl])
        assertEquals(1, events.size)
        assertEquals("route_not_found", events.single().operation)
    }

    private fun Application.installTestSpa(
        assets: SpaAssets = testAssets(),
        events: MutableList<BackendLogEvent>,
    ) {
        val recorder = BackendEventRecorder(events::add)
        installBrowserSecurity(BrowserSecurity({ TEST_PORT }, "svc_spa-test"))
        installApiV1(
            transportKind = TransportKind.BROWSER,
            backendEventRecorder = recorder,
            monotonicTimeSource = deterministicTimeSource(),
        )
        installSpa(assets, recorder, deterministicTimeSource())
    }

    private fun assertSpaPolicy(response: HttpResponse, cacheControl: String) {
        assertEquals(cacheControl, response.headers[HttpHeaders.CacheControl])
        assertEquals("nosniff", response.headers["X-Content-Type-Options"])
        assertEquals("no-referrer", response.headers["Referrer-Policy"])
        assertEquals("same-origin", response.headers["Cross-Origin-Opener-Policy"])
        assertEquals("same-origin", response.headers["Cross-Origin-Resource-Policy"])
        assertEquals("DENY", response.headers["X-Frame-Options"])
        assertEquals("camera=(), geolocation=(), microphone=()", response.headers["Permissions-Policy"])
        assertEquals(CONTENT_SECURITY_POLICY, response.headers["Content-Security-Policy"])
        assertTrue(response.headers["X-Request-ID"]!!.startsWith("req_"))
        assertFalse(response.headers.contains(HttpHeaders.AccessControlAllowOrigin))
    }

    private fun io.ktor.client.request.HttpRequestBuilder.exactHost() {
        header(HttpHeaders.Host, TEST_AUTHORITY)
    }

    private fun deterministicTimeSource(): MonotonicTimeSource {
        var now = 1_000_000L
        return MonotonicTimeSource { now.also { now += 3_000_000L } }
    }

    private companion object {
        const val TEST_PORT = 49152
        const val TEST_AUTHORITY = "127.0.0.1:$TEST_PORT"
        const val IMMUTABLE_CACHE = "public, max-age=31536000, immutable"
        const val CONTENT_SECURITY_POLICY =
            "default-src 'none'; script-src 'self'; style-src 'self'; img-src 'self'; font-src 'self'; connect-src 'self'; object-src 'none'; base-uri 'none'; form-action 'none'; frame-ancestors 'none'"
        val SHELL_BYTES = "<!doctype html><div id=app></div>".encodeToByteArray()
        val JS_BYTES = "console.log('spa')".encodeToByteArray()
        val CSS_BYTES = "body{margin:0}".encodeToByteArray()

        fun testAssets(reads: MutableList<String>? = null) = SpaAssets(SpaResourceReader { name ->
            reads?.add(name)
            when (name) {
                "spa/index.html" -> SHELL_BYTES
                "spa/assets/app-a1b2c3.js" -> JS_BYTES
                "spa/assets/app-a1b2c3.css" -> CSS_BYTES
                else -> null
            }
        })
    }
}

private val BackendLogEvent.operation: String
    get() = when (this) {
        is BackendLogEvent.HttpRequestCompleted -> operation
        is BackendLogEvent.HttpRequestRejected -> operation
        is BackendLogEvent.HttpRequestFailed -> operation
        else -> error("Unexpected event $eventName")
    }

private val BackendLogEvent.requestErrorCode: String?
    get() = (this as? BackendLogEvent.HttpRequestRejected)?.requestErrorCode

package com.mindtable.bitbuckethelper.adapter.inbound.http

import com.mindtable.bitbuckethelper.generated.api.v1.model.AcknowledgeActionItemRequest
import com.mindtable.bitbuckethelper.generated.api.v1.model.ApiVersion
import com.mindtable.bitbuckethelper.generated.api.v1.model.DashboardResponse
import com.mindtable.bitbuckethelper.generated.api.v1.model.DashboardSnapshotUnchangedResult
import com.mindtable.bitbuckethelper.generated.api.v1.model.PollingIdle
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.plugins.statuspages.StatusPagesConfig
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import java.lang.reflect.Proxy
import java.util.concurrent.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ApiV1ModuleTest {
    private val json = Json
    private val requestIdPattern = Regex("^req_[A-Za-z0-9_-]+$")

    @Test
    fun `valid business outcome is a generated discriminated envelope under 200`() = testApplication {
        application { installTestApi() }

        val response = client.get("/api/v1/test/success")
        val body = response.jsonBody()

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals(JsonPrimitive("1"), body["apiVersion"])
        assertRequestId(body)
        assertEquals(JsonPrimitive("snapshotUnchanged"), body.result()["type"])
        assertEquals(JsonPrimitive("dr_test_revision"), body.result()["dashboardRevision"])
        assertEquals(JsonPrimitive("idle"), body.result()["polling"]!!.jsonObject["type"])
        assertResponsePolicy(response)
    }

    @Test
    fun `request IDs are unique and URL safe for independent requests`() = testApplication {
        application { installTestApi() }

        val first = client.get("/api/v1/test/success").jsonBody().requestId()
        val second = client.get("/api/v1/test/success").jsonBody().requestId()

        assertTrue(requestIdPattern.matches(first))
        assertTrue(requestIdPattern.matches(second))
        assertNotEquals(first, second)
    }

    @Test
    fun `malformed JSON is a sanitized 400 request envelope`() = testApplication {
        application { installTestApi() }

        val response = client.post("/api/v1/test/body?secret-query=sentinel-query") {
            contentType(ContentType.Application.Json)
            header(HttpHeaders.Authorization, "Bearer sentinel-authorization")
            setBody("{\"apiVersion\":\"1\",\"activityVersion\":sentinel-raw-body")
        }

        assertError(
            response = response,
            expectedStatus = HttpStatusCode.BadRequest,
            expectedCode = "INVALID_REQUEST",
            expectedMessage = "The request could not be parsed or validated.",
        )
        assertContainsNone(
            response.bodyAsText(),
            "sentinel-query",
            "sentinel-authorization",
            "sentinel-raw-body",
        )
    }

    @Test
    fun `invalid parameters preserve only bounded violations under 400`() = testApplication {
        application { installTestApi() }

        val response = client.get("/api/v1/test/invalid")
        val body = assertError(
            response = response,
            expectedStatus = HttpStatusCode.BadRequest,
            expectedCode = "INVALID_REQUEST",
            expectedMessage = "The request could not be parsed or validated.",
        )

        assertEquals(
            JsonArray(
                listOf(
                    JsonObject(
                        mapOf(
                            "field" to JsonPrimitive("afterRevision"),
                            "code" to JsonPrimitive("INVALID_IDENTIFIER"),
                            "message" to JsonPrimitive("must be a dashboard revision identifier"),
                        ),
                    ),
                ),
            ),
            body.error()["violations"],
        )
    }

    @Test
    fun `request violations are capped and use only catalog text`() = testApplication {
        application { installTestApi() }

        val response = client.get("/api/v1/test/many-invalid?credential=sentinel-credential")
        val body = assertError(
            response = response,
            expectedStatus = HttpStatusCode.BadRequest,
            expectedCode = "INVALID_REQUEST",
            expectedMessage = "The request could not be parsed or validated.",
        )
        val violations = body.error().getValue("violations") as JsonArray

        assertEquals(8, violations.size)
        assertEquals(
            listOf(
                "afterRevision",
                "pullRequestId",
                "actionItemId",
                "activityVersion",
                "activityVersion",
                "apiVersion",
                "target.repositoryIds",
                "target.repositoryIds",
            ),
            violations.map { it.jsonObject.getValue("field").jsonPrimitive.content },
        )
        assertContainsNone(response.bodyAsText(), "sentinel-credential")
    }

    @Test
    fun `unsupported request media type is a sanitized 415 envelope`() = testApplication {
        application { installTestApi() }

        val response = client.post("/api/v1/test/body") {
            contentType(ContentType.Text.Plain)
            setBody("sentinel-plain-body")
        }

        assertError(
            response = response,
            expectedStatus = HttpStatusCode.UnsupportedMediaType,
            expectedCode = "UNSUPPORTED_CONTENT_TYPE",
            expectedMessage = "The request content type must be application/json.",
        )
        assertContainsNone(response.bodyAsText(), "sentinel-plain-body")
    }

    @Test
    fun `unauthorized transport request is a sanitized 403 envelope`() = testApplication {
        application { installTestApi() }

        val response = client.get("/api/v1/test/forbidden?credential=sentinel-credential")

        assertError(
            response = response,
            expectedStatus = HttpStatusCode.Forbidden,
            expectedCode = "FORBIDDEN",
            expectedMessage = "The request is not authorized for this transport.",
        )
        assertContainsNone(response.bodyAsText(), "sentinel-credential")
    }

    @Test
    fun `known route with unsupported method is a sanitized 405 envelope`() = testApplication {
        application { installTestApi() }

        val response = client.patch("/api/v1/test/success")

        assertError(
            response = response,
            expectedStatus = HttpStatusCode.MethodNotAllowed,
            expectedCode = "METHOD_NOT_ALLOWED",
            expectedMessage = "The requested API route does not support this method.",
        )
    }

    @Test
    fun `unknown v1 route is a sanitized 404 envelope`() = testApplication {
        application { installTestApi() }

        val response = client.get("/api/v1/sentinel-unknown-route?secret=sentinel-query")

        assertError(
            response = response,
            expectedStatus = HttpStatusCode.NotFound,
            expectedCode = "ROUTE_NOT_FOUND",
            expectedMessage = "The requested API route does not exist.",
        )
        assertContainsNone(response.bodyAsText(), "sentinel-unknown-route", "sentinel-query")
    }

    @Test
    fun `unexpected exception is a sanitized 500 envelope`() = testApplication {
        application { installTestApi() }

        val response = client.get("/api/v1/test/failure")

        assertError(
            response = response,
            expectedStatus = HttpStatusCode.InternalServerError,
            expectedCode = "INTERNAL_SERVER_ERROR",
            expectedMessage = "The server could not process the request.",
        )
        assertContainsNone(
            response.bodyAsText(),
            "sentinel-api-token",
            "sentinel-database-internals",
        )
    }

    @Test
    fun `coroutine cancellation is rethrown instead of translated to 500`() = testApplication {
        application { installTestApi() }

        val response = client.get("/api/v1/test/cancellation")
        val responseBody = response.bodyAsText()

        assertFalse(responseBody.contains("INTERNAL_SERVER_ERROR"))
        assertFalse(responseBody.contains("The server could not process the request."))

        val statusPages = StatusPagesConfig().apply { installApiV1ErrorHandling() }
        val cancellation = CancellationException("sentinel-cancellation")
        val handler = statusPages.exceptions.getValue(CancellationException::class)
        val propagated = runBlocking {
            runCatching { handler(unusedApplicationCall(), cancellation) }.exceptionOrNull()
        }

        assertSame(cancellation, propagated)
    }

    @Test
    fun `non-v1 exceptions retain normal Ktor handling without v1 state`() = testApplication {
        application { installTestApiWithOutsideRoutes() }

        mapOf(
            "/outside/unexpected" to "sentinel-non-v1-unexpected",
            "/outside/invalid" to "InvalidApiRequestException",
            "/outside/forbidden" to "ForbiddenApiRequestException",
            "/outside/bad-request" to "sentinel-non-v1-bad-request",
        ).forEach { (path, expectedMarker) ->
            val response = client.get(path)
            val responseBody = response.bodyAsText()

            assertTrue(responseBody.contains(expectedMarker), "$path did not propagate its original failure")
            assertFalse(responseBody.contains("\"apiVersion\""), "$path received a v1 envelope")
            assertEquals(null, response.headers[HttpHeaders.CacheControl], "$path received v1 cache policy")
        }
    }

    private fun io.ktor.server.application.Application.installTestApi() {
        val dependencies = FakeApiV1Dependencies()
        installApiV1(TransportKind.UNIX) {
            dependencies.install(this)
        }
    }

    private fun io.ktor.server.application.Application.installTestApiWithOutsideRoutes() {
        installTestApi()
        routing {
            get("/outside/unexpected") {
                error("sentinel-non-v1-unexpected")
            }
            get("/outside/invalid") {
                throw InvalidApiRequestException(listOf(ApiRequestViolation.INVALID_AFTER_REVISION))
            }
            get("/outside/forbidden") {
                throw ForbiddenApiRequestException()
            }
            get("/outside/bad-request") {
                throw BadRequestException(
                    message = "sentinel-non-v1-bad-request",
                    cause = IllegalArgumentException("sentinel-non-v1-bad-request-cause"),
                )
            }
        }
    }

    private suspend fun assertError(
        response: HttpResponse,
        expectedStatus: HttpStatusCode,
        expectedCode: String,
        expectedMessage: String,
    ): JsonObject {
        val body = response.jsonBody()
        assertEquals(expectedStatus, response.status)
        assertEquals(JsonPrimitive("1"), body["apiVersion"])
        assertRequestId(body)
        assertEquals(JsonPrimitive(expectedCode), body.error()["code"])
        assertEquals(JsonPrimitive(expectedMessage), body.error()["message"])
        assertResponsePolicy(response)
        return body
    }

    private fun assertRequestId(body: JsonObject) {
        assertTrue(requestIdPattern.matches(body.requestId()))
    }

    private fun assertResponsePolicy(response: HttpResponse) {
        assertEquals("no-store", response.headers[HttpHeaders.CacheControl])
        assertEquals(ContentType.Application.Json, response.contentType()?.withoutParameters())
    }

    private fun assertContainsNone(text: String, vararg forbidden: String) {
        forbidden.forEach { value -> assertFalse(text.contains(value), "response exposed $value") }
    }

    private fun unusedApplicationCall(): ApplicationCall = Proxy.newProxyInstance(
        ApplicationCall::class.java.classLoader,
        arrayOf(ApplicationCall::class.java),
    ) { _, method, _ ->
        error("cancellation handler accessed ApplicationCall.${method.name}")
    } as ApplicationCall

    private suspend fun HttpResponse.jsonBody(): JsonObject =
        json.parseToJsonElement(bodyAsText()).jsonObject

    private fun JsonObject.requestId(): String = getValue("requestId").jsonPrimitive.content

    private fun JsonObject.result(): JsonObject = getValue("result").jsonObject

    private fun JsonObject.error(): JsonObject = getValue("error").jsonObject

    private class FakeApiV1Dependencies {
        fun install(route: Route) = with(route) {
            get("/test/success") {
                call.respondApiV1 { requestId ->
                    DashboardResponse(
                        apiVersion = ApiVersion._1,
                        requestId = requestId,
                        result = DashboardSnapshotUnchangedResult(
                            dashboardRevision = "dr_test_revision",
                            serverTime = "2026-08-16T00:00:00Z",
                            polling = PollingIdle(),
                        ),
                    )
                }
            }
            post("/test/body") {
                call.receiveApiV1<AcknowledgeActionItemRequest>()
                call.respondApiV1 { requestId ->
                    DashboardResponse(
                        apiVersion = ApiVersion._1,
                        requestId = requestId,
                        result = DashboardSnapshotUnchangedResult(
                            dashboardRevision = "dr_body_revision",
                            serverTime = "2026-08-16T00:00:00Z",
                            polling = PollingIdle(),
                        ),
                    )
                }
            }
            get("/test/invalid") {
                throw InvalidApiRequestException(
                    violations = listOf(ApiRequestViolation.INVALID_AFTER_REVISION),
                )
            }
            get("/test/many-invalid") {
                throw InvalidApiRequestException(ApiRequestViolation.entries)
            }
            get("/test/failure") {
                error("sentinel-api-token and sentinel-database-internals")
            }
            get("/test/forbidden") {
                throw ForbiddenApiRequestException()
            }
            get("/test/cancellation") {
                throw CancellationException()
            }
        }
    }
}

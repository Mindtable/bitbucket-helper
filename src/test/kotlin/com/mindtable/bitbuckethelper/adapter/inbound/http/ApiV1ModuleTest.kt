package com.mindtable.bitbuckethelper.adapter.inbound.http

import com.mindtable.bitbuckethelper.generated.api.v1.model.AcknowledgeActionItemRequest
import com.mindtable.bitbuckethelper.generated.api.v1.model.ApiVersion
import com.mindtable.bitbuckethelper.generated.api.v1.model.DashboardResponse
import com.mindtable.bitbuckethelper.generated.api.v1.model.DashboardSnapshotUnchangedResult
import com.mindtable.bitbuckethelper.generated.api.v1.model.PollingIdle
import com.mindtable.bitbuckethelper.generated.api.v1.model.RequestViolation
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
import io.ktor.server.application.call
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.testing.testApplication
import java.util.concurrent.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
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
    }

    private fun io.ktor.server.application.Application.installTestApi() {
        val dependencies = FakeApiV1Dependencies()
        installApiV1(TransportKind.UNIX) {
            dependencies.install(this)
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
                    violations = listOf(
                        RequestViolation(
                            field = "afterRevision",
                            code = "INVALID_IDENTIFIER",
                            message = "must be a dashboard revision identifier",
                        ),
                    ),
                )
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

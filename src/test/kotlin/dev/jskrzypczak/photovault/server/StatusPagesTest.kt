package dev.jskrzypczak.photovault.server

import dev.jskrzypczak.photovault.server.errors.ApiException
import dev.jskrzypczak.photovault.server.plugins.configureSerialization
import dev.jskrzypczak.photovault.server.plugins.configureStatusPages
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StatusPagesTest {

    /** Lenient parser used for asserting response bodies (ignores unknown fields). */
    private val lenientJson = Json { ignoreUnknownKeys = true }

    @Test
    fun `ApiException maps to problem+json response with correct slug and status`() = testApplication {
        application {
            configureSerialization()
            configureStatusPages()
            routing {
                get("/test-api-error") {
                    throw ApiException(
                        slug = "tag-not-found",
                        httpStatus = HttpStatusCode.NotFound,
                        title = "Tag Not Found",
                        detail = "Tag with id 'tag-99' does not exist",
                    )
                }
            }
        }

        val response = client.get("/test-api-error")

        assertEquals(HttpStatusCode.NotFound, response.status)

        val contentType = response.headers[HttpHeaders.ContentType] ?: ""
        assertTrue(contentType.startsWith("application/problem+json"), "Expected problem+json, got $contentType")

        val body = lenientJson.parseToJsonElement(response.bodyAsText()).jsonObject
        val typeUri = body["type"]?.jsonPrimitive?.content ?: ""
        assertTrue(typeUri.endsWith("tag-not-found"), "type URI should end with slug, was: $typeUri")
        assertEquals("Tag Not Found", body["title"]?.jsonPrimitive?.content)
        assertEquals(404, body["status"]?.jsonPrimitive?.int)
    }

    @Test
    fun `unhandled Throwable falls back to 500 internal-error without leaking stack trace`() = testApplication {
        application {
            configureSerialization()
            configureStatusPages()
            routing {
                get("/test-internal-error") {
                    error("unexpected failure in handler")
                }
            }
        }

        val response = client.get("/test-internal-error")

        assertEquals(HttpStatusCode.InternalServerError, response.status)

        val contentType = response.headers[HttpHeaders.ContentType] ?: ""
        assertTrue(contentType.startsWith("application/problem+json"), "Expected problem+json, got $contentType")

        val bodyText = response.bodyAsText()
        // Stack trace must not be exposed to the client
        assertFalse(bodyText.contains("at dev."), "Stack trace must not appear in response body")

        val body = lenientJson.parseToJsonElement(bodyText).jsonObject
        val typeUri = body["type"]?.jsonPrimitive?.content ?: ""
        assertTrue(typeUri.endsWith("internal-error"), "type URI should end with internal-error, was: $typeUri")
        assertEquals(500, body["status"]?.jsonPrimitive?.int)
    }
}

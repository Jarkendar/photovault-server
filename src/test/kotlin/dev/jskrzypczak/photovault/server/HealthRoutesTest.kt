package dev.jskrzypczak.photovault.server

import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class HealthRoutesTest {

    @Test
    fun `GET v1 health returns 200 with ok status and version`() = testApplication {
        application { module() }

        val response = client.get("/v1/health")

        assertEquals(HttpStatusCode.OK, response.status)

        val contentType = response.headers[HttpHeaders.ContentType] ?: ""
        assertTrue(contentType.startsWith("application/json"), "Expected application/json, got $contentType")

        val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertEquals("ok", body["status"]?.jsonPrimitive?.content)
        assertNotNull(body["version"]?.jsonPrimitive?.content)
    }
}

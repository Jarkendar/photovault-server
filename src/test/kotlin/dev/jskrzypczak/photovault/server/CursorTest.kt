package dev.jskrzypczak.photovault.server

import dev.jskrzypczak.photovault.server.errors.ApiException
import dev.jskrzypczak.photovault.server.photos.decodeCursor
import dev.jskrzypczak.photovault.server.photos.encodeCursor
import io.ktor.http.HttpStatusCode
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * Unit tests for cursor encode/decode logic — no database needed.
 */
class CursorTest {

    @Test
    fun `encode then decode returns original uploadedAt and id`() {
        val ts = Instant.parse("2026-04-18T17:24:30Z")
        val id = "photo-abc123"

        val encoded = encodeCursor(ts, id)
        val decoded = decodeCursor(encoded)

        assertEquals(ts.toString(), decoded.uploadedAt)
        assertEquals(id, decoded.id)
    }

    @Test
    fun `encoded cursor is a non-blank URL-safe base64 string`() {
        val encoded = encodeCursor(Instant.now(), "photo-x")
        // URL-safe base64 must not contain '+' or '/' or '='
        assert(encoded.isNotBlank())
        assert(encoded.none { it == '+' || it == '/' || it == '=' })
    }

    @Test
    fun `different timestamps produce different cursors`() {
        val t1 = encodeCursor(Instant.parse("2026-01-01T00:00:00Z"), "photo-1")
        val t2 = encodeCursor(Instant.parse("2026-06-01T00:00:00Z"), "photo-1")
        assert(t1 != t2)
    }

    @Test
    fun `decodeCursor with garbage string throws ApiException invalid-cursor 400`() {
        val ex = assertFailsWith<ApiException> { decodeCursor("###not-base64###") }
        assertEquals("invalid-cursor", ex.slug)
        assertEquals(HttpStatusCode.BadRequest, ex.httpStatus)
    }

    @Test
    fun `decodeCursor with valid base64 but non-JSON content throws ApiException invalid-cursor 400`() {
        // base64-encode a string that isn't valid JSON
        val notJson = java.util.Base64.getUrlEncoder().withoutPadding()
            .encodeToString("hello world".toByteArray())
        val ex = assertFailsWith<ApiException> { decodeCursor(notJson) }
        assertEquals("invalid-cursor", ex.slug)
    }

    @Test
    fun `decodeCursor with base64 JSON missing required fields throws ApiException invalid-cursor 400`() {
        // Valid base64 JSON, but missing `id` field
        val partial = java.util.Base64.getUrlEncoder().withoutPadding()
            .encodeToString("""{"uploadedAt":"2026-01-01T00:00:00Z"}""".toByteArray())
        val ex = assertFailsWith<ApiException> { decodeCursor(partial) }
        assertEquals("invalid-cursor", ex.slug)
    }
}

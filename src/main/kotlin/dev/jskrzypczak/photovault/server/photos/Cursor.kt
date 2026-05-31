package dev.jskrzypczak.photovault.server.photos

import dev.jskrzypczak.photovault.server.errors.ApiException
import dev.jskrzypczak.photovault.server.errors.appJson
import io.ktor.http.HttpStatusCode
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import java.time.Instant
import java.util.Base64

/**
 * Internal representation of a pagination cursor.
 *
 * Encodes the last item's [uploadedAt] timestamp (ISO-8601 UTC) and [id]
 * so that the next page query can use `(uploadedAt, id) < (?, ?)`.
 * The client receives this as an opaque base64-url string and must never
 * parse or construct it.
 */
@Serializable
data class Cursor(
    val uploadedAt: String,
    val id: String,
)

private val base64 = Base64.getUrlEncoder().withoutPadding()
private val base64Decoder = Base64.getUrlDecoder()

/**
 * Encodes [uploadedAt] and [id] into an opaque, URL-safe base64 cursor string.
 */
fun encodeCursor(uploadedAt: Instant, id: String): String {
    val json = appJson.encodeToString(Cursor(uploadedAt.toString(), id))
    return base64.encodeToString(json.toByteArray(Charsets.UTF_8))
}

/**
 * Decodes an opaque cursor string back into a [Cursor].
 *
 * @throws ApiException(invalid-cursor, 400) when [raw] is malformed or cannot be decoded.
 */
fun decodeCursor(raw: String): Cursor = try {
    val json = base64Decoder.decode(raw).toString(Charsets.UTF_8)
    appJson.decodeFromString<Cursor>(json)
} catch (e: Exception) {
    throw ApiException(
        slug = "invalid-cursor",
        httpStatus = HttpStatusCode.BadRequest,
        title = "Invalid Cursor",
        detail = "The pagination cursor is malformed or stale",
    )
}

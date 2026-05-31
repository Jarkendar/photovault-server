package dev.jskrzypczak.photovault.server.routes

import dev.jskrzypczak.photovault.server.errors.ApiException
import dev.jskrzypczak.photovault.server.photos.PhotoQuery
import dev.jskrzypczak.photovault.server.photos.PhotoService
import dev.jskrzypczak.photovault.server.storage.AssetVariant
import io.ktor.http.CacheControl
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.auth.principal
import io.ktor.server.http.content.LocalFileContent
import io.ktor.server.response.cacheControl
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.route

/**
 * Registers photo read endpoints under the caller's route prefix (expected: /v1).
 *
 * All endpoints require the "auth-jwt" principal:
 *   GET /photos                  — paginated photo list with optional filters
 *   GET /photos/{id}             — single photo by id
 *   GET /photos/{id}/thumbnail   — thumbnail binary (image/jpeg)
 *   GET /photos/{id}/medium      — medium binary (image/jpeg)
 *   GET /photos/{id}/original    — original binary (photo's own MIME type)
 */
fun Route.photoRoutes(photoService: PhotoService) {
    authenticate("auth-jwt") {
        route("/photos") {

            get {
                val userId = call.principal<JWTPrincipal>()!!.payload.subject

                // ── limit ─────────────────────────────────────────────────────
                val limit = call.parameters["limit"]?.let { raw ->
                    raw.toIntOrNull() ?: throw ApiException(
                        slug = "validation-failed",
                        httpStatus = HttpStatusCode.BadRequest,
                        title = "Validation Failed",
                        detail = "'limit' must be an integer",
                    )
                } ?: 30

                // ── csv helper ────────────────────────────────────────────────
                fun csvParam(name: String): List<String> =
                    call.parameters[name]
                        ?.split(",")
                        ?.map { it.trim() }
                        ?.filter { it.isNotEmpty() }
                        ?: emptyList()

                // ── uploadedBy — resolve "me" to the authenticated user id ───
                val uploadedByRaw = call.parameters["uploadedBy"]
                val uploadedBy = when {
                    uploadedByRaw == null -> null
                    uploadedByRaw == "me" -> userId
                    else -> uploadedByRaw
                }

                val query = PhotoQuery(
                    cursor = call.parameters["cursor"],
                    limit = limit,
                    q = call.parameters["q"],
                    tagIds = csvParam("tagIds"),
                    categoryIds = csvParam("categoryIds"),
                    labelIds = csvParam("labelIds"),
                    favoritesOnly = call.parameters["favoritesOnly"]?.lowercase() == "true",
                    uploadedBy = uploadedBy,
                )

                val page = photoService.listPhotos(query)
                call.respond(HttpStatusCode.OK, page)
            }

            get("/{id}") {
                val id = call.parameters["id"]!!
                val photo = photoService.getPhoto(id)
                call.respond(HttpStatusCode.OK, photo)
            }

            get("/{id}/thumbnail") { respondAsset(call, photoService, AssetVariant.THUMBNAIL) }
            get("/{id}/medium")    { respondAsset(call, photoService, AssetVariant.MEDIUM) }
            get("/{id}/original")  { respondAsset(call, photoService, AssetVariant.ORIGINAL) }
        }
    }
}

/**
 * Resolves and streams a binary photo asset, adding appropriate caching headers.
 *
 * - Sets `Cache-Control: private, max-age=31536000, immutable` (assets never change).
 * - Sets `ETag` and returns `304 Not Modified` when the client sends a matching `If-None-Match`.
 * - Streams the file via [LocalFileContent] (supports HTTP Range requests).
 */
private suspend fun respondAsset(call: ApplicationCall, photoService: PhotoService, variant: AssetVariant) {
    val id = call.parameters["id"]!!
    val asset = photoService.getAsset(id, variant)

    val etag = asset.etag
    val clientEtag = call.request.headers[HttpHeaders.IfNoneMatch]

    call.response.headers.append(HttpHeaders.ETag, etag)
    call.response.cacheControl(
        CacheControl.MaxAge(
            maxAgeSeconds = 31_536_000,
            visibility = CacheControl.Visibility.Private,
            mustRevalidate = false,
            proxyRevalidate = false,
        )
    )

    if (clientEtag != null && clientEtag == etag) {
        call.respond(HttpStatusCode.NotModified)
        return
    }

    call.respond(LocalFileContent(asset.file, ContentType.parse(asset.contentType)))
}

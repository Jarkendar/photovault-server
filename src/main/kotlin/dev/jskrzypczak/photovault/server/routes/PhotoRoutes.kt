package dev.jskrzypczak.photovault.server.routes

import dev.jskrzypczak.photovault.server.errors.ApiException
import dev.jskrzypczak.photovault.server.photos.PhotoQuery
import dev.jskrzypczak.photovault.server.photos.PhotoService
import io.ktor.http.HttpStatusCode
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.auth.principal
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.route

/**
 * Registers photo read endpoints under the caller's route prefix (expected: /v1).
 *
 * Both endpoints require the "auth-jwt" principal:
 *   GET /photos         — paginated photo list with optional filters
 *   GET /photos/{id}    — single photo by id
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
        }
    }
}

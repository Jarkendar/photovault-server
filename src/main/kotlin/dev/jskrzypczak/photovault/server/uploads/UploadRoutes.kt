package dev.jskrzypczak.photovault.server.uploads

import dev.jskrzypczak.photovault.server.errors.ApiException
import dev.jskrzypczak.photovault.server.uploads.dto.UploadMetadata
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.PartData
import io.ktor.http.content.forEachPart
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.auth.principal
import io.ktor.server.request.receiveMultipart
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.utils.io.*
import kotlinx.serialization.json.Json

private val lenient = Json { ignoreUnknownKeys = true }

/**
 * Registers all upload endpoints under the caller's route prefix (expected: /v1).
 *
 * All endpoints require the "auth-jwt" principal:
 *   POST   /uploads                — accept a multipart file, start async processing (202)
 *   GET    /uploads                — list uploads; optional ?status= CSV filter
 *   GET    /uploads/{id}           — poll a single upload
 *   DELETE /uploads/{id}           — cancel a cancellable upload
 *
 * IMPORTANT (Ktor 3 behaviour): `PartData` providers are invalidated when `forEachPart`
 * moves to the next iteration. All byte reading MUST happen inside the loop body.
 */
fun Route.uploadRoutes(uploadService: UploadService) {
    authenticate("auth-jwt") {
        route("/uploads") {

            post {
                val userId = call.principal<JWTPrincipal>()!!.payload.subject
                val multipart = call.receiveMultipart()

                var fileName: String? = null
                var contentType: String? = null
                var fileBytes: ByteArray? = null
                var metadataText: String? = null

                // Read all bytes INSIDE the loop — Ktor 3 cleans providers after each iteration.
                multipart.forEachPart { part ->
                    when {
                        part is PartData.FileItem && part.name == "file" -> {
                            fileName = part.originalFileName ?: "upload"
                            contentType = part.contentType?.toString() ?: "application/octet-stream"
                            fileBytes = part.provider().toByteArray()
                        }
                        part is PartData.FormItem && part.name == "metadata" -> {
                            metadataText = part.value
                        }
                        else -> {}
                    }
                    part.release()
                }

                if (fileBytes == null) {
                    throw ApiException(
                        slug = "validation-failed",
                        httpStatus = HttpStatusCode.BadRequest,
                        title = "Validation Failed",
                        detail = "Missing required multipart part: 'file'",
                        errors = mapOf("file" to listOf("required")),
                    )
                }

                val metadata = if (!metadataText.isNullOrBlank()) {
                    try { lenient.decodeFromString<UploadMetadata>(metadataText!!) }
                    catch (_: Exception) { UploadMetadata() }
                } else {
                    UploadMetadata()
                }

                val dto = uploadService.createUpload(
                    fileName = fileName!!,
                    sizeBytes = fileBytes!!.size.toLong(),
                    contentType = contentType!!,
                    bytes = fileBytes!!,
                    metadata = metadata,
                    ownerId = userId,
                )

                call.response.header(HttpHeaders.Location, "/v1/uploads/${dto.id}")
                call.respond(HttpStatusCode.Accepted, dto)
            }

            get {
                val userId = call.principal<JWTPrincipal>()!!.payload.subject
                val statusFilter = call.parameters["status"]
                    ?.split(",")
                    ?.map { it.trim() }
                    ?.filter { it.isNotEmpty() }
                    ?: emptyList()
                val list = uploadService.listUploads(userId, statusFilter)
                call.respond(HttpStatusCode.OK, list)
            }

            get("/{id}") {
                val userId = call.principal<JWTPrincipal>()!!.payload.subject
                val id = call.parameters["id"]!!
                val dto = uploadService.getUpload(id, userId)
                call.respond(HttpStatusCode.OK, dto)
            }

            delete("/{id}") {
                val userId = call.principal<JWTPrincipal>()!!.payload.subject
                val id = call.parameters["id"]!!
                uploadService.cancelUpload(id, userId)
                call.respond(HttpStatusCode.NoContent, "")
            }
        }
    }
}

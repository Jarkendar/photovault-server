package dev.jskrzypczak.photovault.server.routes

import dev.jskrzypczak.photovault.server.auth.requireAdmin
import dev.jskrzypczak.photovault.server.dto.FaceClusterListResponse
import dev.jskrzypczak.photovault.server.dto.FaceListResponse
import dev.jskrzypczak.photovault.server.dto.LabelClusterRequest
import dev.jskrzypczak.photovault.server.errors.ApiException
import dev.jskrzypczak.photovault.server.faces.FaceService
import io.ktor.http.HttpStatusCode
import io.ktor.server.auth.authenticate
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route

/**
 * Admin-only face cluster management endpoints mounted at `/v1/admin/face-clusters`.
 *
 * All routes require a valid JWT with `role = "admin"` — regular user tokens receive 403.
 * These endpoints are intended for a separate web/desktop admin tool, not the mobile app.
 */
fun Route.faceRoutes(faceService: FaceService) {
    authenticate("auth-jwt") {
        route("/admin/face-clusters") {

            // GET /v1/admin/face-clusters?labeled=true|false&unlabeled=true
            get {
                call.requireAdmin()
                val labeledOnly = call.parameters["labeled"]?.lowercase() == "true"
                val unlabeledOnly = call.parameters["unlabeled"]?.lowercase() == "true"
                val clusters = faceService.listClusters(
                    labeledOnly = labeledOnly,
                    unlabeledOnly = unlabeledOnly,
                )
                call.respond(HttpStatusCode.OK, FaceClusterListResponse(clusters))
            }

            // GET /v1/admin/face-clusters/{id}/faces
            get("/{id}/faces") {
                call.requireAdmin()
                val id = call.parameters["id"]!!
                val faces = faceService.listFacesInCluster(id)
                call.respond(HttpStatusCode.OK, FaceListResponse(faces))
            }

            // POST /v1/admin/face-clusters/{id}/label
            post("/{id}/label") {
                call.requireAdmin()
                val id = call.parameters["id"]!!
                val req = try {
                    call.receive<LabelClusterRequest>()
                } catch (e: Exception) {
                    throw ApiException(
                        slug = "validation-failed",
                        httpStatus = HttpStatusCode.BadRequest,
                        title = "Validation Failed",
                        detail = "Request body must be a valid JSON object with tagId or categoryId",
                    )
                }
                val cluster = faceService.labelCluster(id, req.tagId, req.categoryId)
                call.respond(HttpStatusCode.OK, cluster)
            }

            // DELETE /v1/admin/face-clusters/{id}
            delete("/{id}") {
                call.requireAdmin()
                val id = call.parameters["id"]!!
                faceService.deleteCluster(id)
                call.respond(HttpStatusCode.NoContent, "")
            }
        }
    }
}

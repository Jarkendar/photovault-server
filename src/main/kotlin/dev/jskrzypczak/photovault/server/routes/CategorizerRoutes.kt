package dev.jskrzypczak.photovault.server.routes

import dev.jskrzypczak.photovault.server.auth.requireAdmin
import dev.jskrzypczak.photovault.server.categorizer.CategorizerService
import io.ktor.http.HttpStatusCode
import io.ktor.server.auth.authenticate
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route

fun Route.categorizerRoutes(service: CategorizerService) {
    authenticate("auth-jwt") {
        route("/admin/categorizer") {

            // POST /v1/admin/categorizer/run — trigger a categorizer run for all pending photos
            post("/run") {
                call.requireAdmin()
                service.triggerRun()
                call.respond(HttpStatusCode.Accepted, service.status())
            }

            // GET /v1/admin/categorizer/status — current run state + photo counts
            get("/status") {
                call.requireAdmin()
                call.respond(HttpStatusCode.OK, service.status())
            }

            // POST /v1/admin/categorizer/recategorize/{photoId} — reset one photo to pending_categorization
            post("/recategorize/{photoId}") {
                call.requireAdmin()
                val photoId = call.parameters["photoId"]!!
                service.recategorizePhoto(photoId)
                call.respond(HttpStatusCode.NoContent, "")
            }
        }
    }
}

package dev.jskrzypczak.photovault.server.routes

import dev.jskrzypczak.photovault.server.dto.LabelListResponse
import dev.jskrzypczak.photovault.server.metadata.LabelService
import io.ktor.http.HttpStatusCode
import io.ktor.server.auth.authenticate
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.route

fun Route.labelRoutes(labelService: LabelService) {
    authenticate("auth-jwt") {
        route("/labels") {
            get {
                val labels = labelService.listLabels()
                call.respond(HttpStatusCode.OK, LabelListResponse(labels))
            }
            // POST, PATCH, DELETE are not registered — Ktor returns 405 automatically
            // when the path matches but no handler is registered for the method.
        }
    }
}

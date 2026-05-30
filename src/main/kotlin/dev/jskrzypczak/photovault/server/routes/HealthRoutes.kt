package dev.jskrzypczak.photovault.server.routes

import dev.jskrzypczak.photovault.server.dto.HealthDto
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get

/** Registers the `GET /health` route. Public — no authentication required. */
fun Route.healthRoutes(version: String) {
    get("/health") {
        call.respond(HealthDto(status = "ok", version = version))
    }
}

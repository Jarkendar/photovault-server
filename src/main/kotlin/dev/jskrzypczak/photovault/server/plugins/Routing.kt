package dev.jskrzypczak.photovault.server.plugins

import dev.jskrzypczak.photovault.server.auth.AuthService
import dev.jskrzypczak.photovault.server.routes.authRoutes
import dev.jskrzypczak.photovault.server.routes.healthRoutes
import io.ktor.server.application.Application
import io.ktor.server.routing.route
import io.ktor.server.routing.routing

/** Registers all API routes under the `/v1` prefix. */
fun Application.configureRouting(version: String, authService: AuthService) {
    routing {
        route("/v1") {
            healthRoutes(version)
            authRoutes(authService)
        }
    }
}

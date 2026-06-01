package dev.jskrzypczak.photovault.server.routes

import dev.jskrzypczak.photovault.server.users.UserService
import io.ktor.http.HttpStatusCode
import io.ktor.server.auth.authenticate
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.route

fun Route.userRoutes(userService: UserService) {
    authenticate("auth-jwt") {
        route("/users") {
            get {
                call.respond(HttpStatusCode.OK, userService.list())
            }
            // POST, PATCH, DELETE are not registered — Ktor returns 405 automatically
            // when the path matches but no handler is registered for the method.
        }
    }
}

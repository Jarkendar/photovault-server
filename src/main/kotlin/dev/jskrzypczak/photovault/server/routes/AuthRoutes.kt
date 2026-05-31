package dev.jskrzypczak.photovault.server.routes

import dev.jskrzypczak.photovault.server.auth.AuthService
import dev.jskrzypczak.photovault.server.dto.LoginRequest
import dev.jskrzypczak.photovault.server.dto.RefreshRequest
import dev.jskrzypczak.photovault.server.errors.ApiException
import io.ktor.http.HttpStatusCode
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.auth.principal
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route

/**
 * Registers the four auth endpoints under the caller's route prefix (expected: /v1).
 *
 * Public (no auth needed):
 *   POST /auth/login   — validate credentials, return JWT pair
 *   POST /auth/refresh — exchange a refresh token for a new pair
 *
 * Protected (requires "auth-jwt" principal):
 *   GET  /auth/me      — return the current user's profile
 *   POST /auth/logout  — revoke the session's refresh token
 */
fun Route.authRoutes(authService: AuthService) {
    route("/auth") {

        // ── Public endpoints ────────────────────────────────────────────────────

        post("/login") {
            val req = try {
                call.receive<LoginRequest>()
            } catch (e: Exception) {
                throw ApiException(
                    slug = "validation-failed",
                    httpStatus = HttpStatusCode.BadRequest,
                    title = "Validation Failed",
                    detail = "Request body must contain 'username' and 'password'",
                )
            }
            val response = authService.login(req.username, req.password)
            call.respond(HttpStatusCode.OK, response)
        }

        post("/refresh") {
            val req = try {
                call.receive<RefreshRequest>()
            } catch (e: Exception) {
                throw ApiException(
                    slug = "validation-failed",
                    httpStatus = HttpStatusCode.BadRequest,
                    title = "Validation Failed",
                    detail = "Request body must contain 'refreshToken'",
                )
            }
            val response = authService.refresh(req.refreshToken)
            call.respond(HttpStatusCode.OK, response)
        }

        // ── Protected endpoints ─────────────────────────────────────────────────

        authenticate("auth-jwt") {

            get("/me") {
                val userId = call.principal<JWTPrincipal>()!!.payload.subject
                val user = authService.currentUser(userId)
                call.respond(HttpStatusCode.OK, user)
            }

            post("/logout") {
                val rti = call.principal<JWTPrincipal>()!!
                    .payload
                    .getClaim("rti")
                    .asString()
                    ?: throw ApiException(
                        slug = "unauthenticated",
                        httpStatus = HttpStatusCode.Unauthorized,
                        title = "Unauthenticated",
                        detail = "Access token is missing the rti claim",
                    )
                authService.logout(rti)
                call.respond(HttpStatusCode.NoContent, "")
            }
        }
    }
}

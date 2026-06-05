package dev.jskrzypczak.photovault.server.auth

import dev.jskrzypczak.photovault.server.errors.ApiException
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.auth.principal

/**
 * Throws [ApiException] (403 Forbidden) unless the authenticated token carries `role = "admin"`.
 *
 * Used to protect `/v1/admin/` endpoints from regular user tokens.  The call site still
 * requires the route to be inside `authenticate("auth-jwt")` — this guard only checks the role
 * claim, it does not replace authentication.
 *
 * Usage:
 *   authenticate("auth-jwt") {
 *       route("/admin/face-clusters") {
 *           get { call.requireAdmin(); ... }
 *       }
 *   }
 */
fun ApplicationCall.requireAdmin() {
    val role = principal<JWTPrincipal>()?.payload?.getClaim("role")?.asString()
    if (role != "admin") throw ApiException(
        slug = "forbidden",
        httpStatus = HttpStatusCode.Forbidden,
        title = "Forbidden",
        detail = "Admin role required for this endpoint",
    )
}

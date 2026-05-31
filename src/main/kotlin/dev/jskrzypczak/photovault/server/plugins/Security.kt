package dev.jskrzypczak.photovault.server.plugins

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import dev.jskrzypczak.photovault.server.auth.JwtConfig
import dev.jskrzypczak.photovault.server.dto.ProblemDetails
import dev.jskrzypczak.photovault.server.errors.appJson
import dev.jskrzypczak.photovault.server.errors.problemType
import dev.jskrzypczak.photovault.server.errors.respondProblem
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.auth.authentication
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.auth.jwt.jwt
import io.ktor.server.request.uri
import kotlinx.serialization.encodeToString

/**
 * Installs Ktor's JWT authentication plugin under the name "auth-jwt".
 *
 * - Tokens are verified with HMAC256 using the configured secret.
 * - The `validate` block ensures the token has a non-null `sub` claim.
 * - The `challenge` block returns RFC 7807 `application/problem+json` (slug: unauthenticated)
 *   so that failed auth is consistent with the rest of the error catalog.
 *
 * Routes that require authentication must be wrapped in `authenticate("auth-jwt") { … }`.
 */
fun Application.configureSecurity(jwtConfig: JwtConfig) {
    val algorithm = Algorithm.HMAC256(jwtConfig.secret)
    val verifier = JWT.require(algorithm)
        .withIssuer(jwtConfig.issuer)
        .withAudience(jwtConfig.audience)
        .build()

    authentication {
        jwt("auth-jwt") {
            realm = "PhotoVault"
            verifier(verifier)
            validate { credential ->
                if (credential.payload.subject != null) JWTPrincipal(credential.payload) else null
            }
            challenge { _, _ ->
                val problem = ProblemDetails(
                    type = problemType("unauthenticated"),
                    title = "Unauthenticated",
                    status = HttpStatusCode.Unauthorized.value,
                    detail = "Missing or invalid Authorization header",
                    instance = call.request.uri,
                )
                call.respondProblem(problem)
            }
        }
    }
}

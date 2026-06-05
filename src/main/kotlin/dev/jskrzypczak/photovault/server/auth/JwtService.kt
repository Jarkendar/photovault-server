package dev.jskrzypczak.photovault.server.auth

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.auth0.jwt.interfaces.DecodedJWT
import java.util.Date
import java.util.UUID

/**
 * Creates and verifies JWT tokens for the PhotoVault auth flow.
 *
 * - **Access token** — short-lived; carries `sub` (userId) and `rti` (jti of the paired refresh
 *   token). The `rti` claim lets the logout endpoint revoke exactly the one refresh token that
 *   belongs to the current session.
 * - **Refresh token** — long-lived; carries `jti` (random UUID) and `sub` (userId). The jti is
 *   stored in the `refresh_tokens` table so it can be revoked.
 */
class JwtService(val config: JwtConfig) {

    private val algorithm: Algorithm = Algorithm.HMAC256(config.secret)

    private val accessVerifier = JWT.require(algorithm)
        .withIssuer(config.issuer)
        .withAudience(config.audience)
        .build()

    private val refreshVerifier = JWT.require(algorithm)
        .withIssuer(config.issuer)
        .withAudience(config.audience)
        .build()

    /**
     * Generates a signed access token.
     *
     * @param userId     The `sub` claim (user's DB id, e.g. "user-admin").
     * @param refreshJti The `rti` claim — jti of the paired refresh token.
     * @param role       Optional role claim.  Pass `"admin"` to grant access to `/v1/admin/` routes.
     */
    fun generateAccessToken(userId: String, refreshJti: String, role: String? = null): String {
        val builder = JWT.create()
            .withIssuer(config.issuer)
            .withAudience(config.audience)
            .withSubject(userId)
            .withClaim("rti", refreshJti)
            .withExpiresAt(Date(System.currentTimeMillis() + config.accessTtlMinutes * 60_000L))
        if (role != null) builder.withClaim("role", role)
        return builder.sign(algorithm)
    }

    /**
     * Generates a signed refresh token and returns the token string together with its jti.
     *
     * @param userId The `sub` claim.
     * @return Pair of (signed JWT string, jti UUID string).
     */
    fun generateRefreshToken(userId: String): Pair<String, String> {
        val jti = UUID.randomUUID().toString()
        val token = JWT.create()
            .withIssuer(config.issuer)
            .withAudience(config.audience)
            .withSubject(userId)
            .withJWTId(jti)
            .withExpiresAt(Date(System.currentTimeMillis() + config.refreshTtlDays * 86_400_000L))
            .sign(algorithm)
        return token to jti
    }

    /** Verifies and decodes an access token. Throws on invalid/expired/wrong-signature. */
    fun verifyAccessToken(token: String): DecodedJWT = accessVerifier.verify(token)

    /** Verifies and decodes a refresh token. Throws on invalid/expired/wrong-signature. */
    fun verifyRefreshToken(token: String): DecodedJWT = refreshVerifier.verify(token)
}

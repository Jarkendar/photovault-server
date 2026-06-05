package dev.jskrzypczak.photovault.server.auth

import at.favre.lib.crypto.bcrypt.BCrypt
import dev.jskrzypczak.photovault.server.db.tables.RefreshTokens
import dev.jskrzypczak.photovault.server.db.tables.Users
import dev.jskrzypczak.photovault.server.dto.AuthResponse
import dev.jskrzypczak.photovault.server.dto.UserDto
import dev.jskrzypczak.photovault.server.errors.ApiException
import io.ktor.http.HttpStatusCode
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import java.time.Instant

/**
 * Domain logic for authentication flows: login, refresh, logout, and current-user lookup.
 *
 * All database access is performed inside Exposed [transaction] blocks. Token signing/verification
 * is delegated to [JwtService].
 */
class AuthService(private val jwtService: JwtService) {

    /**
     * Authenticates a user by username and password.
     *
     * @throws ApiException(invalid-credentials, 401) when the username does not exist or the
     *   password does not match the stored BCrypt hash.
     */
    fun login(username: String, password: String): AuthResponse = transaction {
        val row = Users.selectAll()
            .where { Users.username eq username }
            .firstOrNull()
            ?: throw invalidCredentials()

        val verified = BCrypt.verifyer()
            .verify(password.toCharArray(), row[Users.passwordHash])
            .verified

        if (!verified) throw invalidCredentials()

        val userId = row[Users.id]
        val user = UserDto(
            id = userId,
            username = row[Users.username],
            displayName = row[Users.displayName],
        )

        issueTokenPair(userId, user, row[Users.role])
    }

    /**
     * Exchanges a valid, non-revoked refresh token for a new token pair.
     *
     * Rotates the refresh token: marks the old jti as revoked and inserts a new one.
     *
     * @throws ApiException(invalid-token, 401) when the token is malformed, expired, or revoked.
     */
    fun refresh(refreshToken: String): AuthResponse = transaction {
        val decoded = try {
            jwtService.verifyRefreshToken(refreshToken)
        } catch (e: Exception) {
            throw invalidToken()
        }

        val jti = decoded.id
        val userId = decoded.subject

        val row = RefreshTokens.selectAll()
            .where { (RefreshTokens.jti eq jti) and (RefreshTokens.revoked eq false) }
            .firstOrNull()
            ?: throw invalidToken()

        // Check DB-level expiry as a second line of defence (JWT already checked it above)
        if (row[RefreshTokens.expiresAt].isBefore(Instant.now())) throw invalidToken()

        // Revoke the old refresh token before issuing new ones (rotation)
        RefreshTokens.update({ RefreshTokens.jti eq jti }) {
            it[revoked] = true
        }

        val userRow = Users.selectAll()
            .where { Users.id eq userId }
            .firstOrNull()
            ?: throw invalidToken()

        val user = UserDto(
            id = userRow[Users.id],
            username = userRow[Users.username],
            displayName = userRow[Users.displayName],
        )

        issueTokenPair(userId, user, userRow[Users.role])
    }

    /**
     * Invalidates the refresh token associated with [rti] (the `rti` claim from the access token).
     *
     * Idempotent — calling it on an already-revoked jti is a no-op.
     */
    fun logout(rti: String): Unit = transaction {
        RefreshTokens.update({ RefreshTokens.jti eq rti }) {
            it[revoked] = true
        }
    }

    /**
     * Returns the [UserDto] for the given user id.
     *
     * @throws ApiException(unauthenticated, 401) when the user does not exist.
     */
    fun currentUser(userId: String): UserDto = transaction {
        Users.selectAll()
            .where { Users.id eq userId }
            .firstOrNull()
            ?.let { u ->
                UserDto(
                    id = u[Users.id],
                    username = u[Users.username],
                    displayName = u[Users.displayName],
                )
            }
            ?: throw ApiException(
                slug = "unauthenticated",
                httpStatus = HttpStatusCode.Unauthorized,
                title = "Unauthenticated",
                detail = "User not found for the provided token",
            )
    }

    // ─── helpers ───────────────────────────────────────────────────────────────

    /** Issues a fresh token pair and persists the new refresh-token row. Must be called inside a transaction. */
    private fun issueTokenPair(userId: String, user: UserDto, role: String? = null): AuthResponse {
        val (refreshTokenStr, jti) = jwtService.generateRefreshToken(userId)
        val accessTokenStr = jwtService.generateAccessToken(userId, jti, role)

        RefreshTokens.insert {
            it[RefreshTokens.jti] = jti
            it[RefreshTokens.userId] = userId
            it[expiresAt] = Instant.now().plusSeconds(jwtService.config.refreshTtlDays * 86_400L)
            it[revoked] = false
        }

        return AuthResponse(
            accessToken = accessTokenStr,
            refreshToken = refreshTokenStr,
            user = user,
        )
    }

    private fun invalidCredentials() = ApiException(
        slug = "invalid-credentials",
        httpStatus = HttpStatusCode.Unauthorized,
        title = "Invalid Credentials",
        detail = "Username or password is incorrect",
    )

    private fun invalidToken() = ApiException(
        slug = "invalid-token",
        httpStatus = HttpStatusCode.Unauthorized,
        title = "Invalid Token",
        detail = "The refresh token is invalid, expired, or has been revoked",
    )
}

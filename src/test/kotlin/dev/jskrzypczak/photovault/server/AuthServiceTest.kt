package dev.jskrzypczak.photovault.server

import dev.jskrzypczak.photovault.server.auth.AuthService
import dev.jskrzypczak.photovault.server.auth.JwtConfig
import dev.jskrzypczak.photovault.server.auth.JwtService
import dev.jskrzypczak.photovault.server.db.initDatabase
import dev.jskrzypczak.photovault.server.db.tables.RefreshTokens
import dev.jskrzypczak.photovault.server.errors.ApiException
import io.ktor.http.HttpStatusCode
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AuthServiceTest {

    companion object {
        @JvmStatic
        @Container
        val postgres: PostgreSQLContainer<*> = PostgreSQLContainer("postgres:16-alpine")
    }

    private lateinit var authService: AuthService
    private lateinit var jwtService: JwtService

    @BeforeAll
    fun setup() {
        Database.connect(
            url = postgres.jdbcUrl,
            driver = "org.postgresql.Driver",
            user = postgres.username,
            password = postgres.password,
        )
        initDatabase()

        val config = JwtConfig(
            secret = "test-secret",
            issuer = "test",
            audience = "test-audience",
            accessTtlMinutes = 60,
            refreshTtlDays = 30,
        )
        jwtService = JwtService(config)
        authService = AuthService(jwtService)
    }

    // ─── login ─────────────────────────────────────────────────────────────────

    @Test
    fun `login with valid credentials returns AuthResponse with access and refresh tokens`() {
        val response = authService.login("admin", "password123")
        assertTrue(response.accessToken.isNotBlank())
        assertTrue(response.refreshToken.isNotBlank())
        assertEquals("admin", response.user.username)
        assertEquals("user-admin", response.user.id)
    }

    @Test
    fun `login inserts a RefreshToken row in the database`() {
        val countBefore = transaction { RefreshTokens.selectAll().count() }
        authService.login("admin", "password123")
        val countAfter = transaction { RefreshTokens.selectAll().count() }
        assertTrue(countAfter > countBefore, "A new refresh-token row must be inserted on login")
    }

    @Test
    fun `login with wrong password throws 401 invalid-credentials`() {
        val ex = org.junit.jupiter.api.assertThrows<ApiException> {
            authService.login("admin", "wrong-password")
        }
        assertEquals("invalid-credentials", ex.slug)
        assertEquals(HttpStatusCode.Unauthorized, ex.httpStatus)
    }

    @Test
    fun `login with unknown username throws 401 invalid-credentials`() {
        val ex = org.junit.jupiter.api.assertThrows<ApiException> {
            authService.login("no-such-user", "any-password")
        }
        assertEquals("invalid-credentials", ex.slug)
        assertEquals(HttpStatusCode.Unauthorized, ex.httpStatus)
    }

    // ─── refresh ───────────────────────────────────────────────────────────────

    @Test
    fun `refresh returns new token pair and revokes the old refresh token`() {
        val first = authService.login("admin", "password123")
        val second = authService.refresh(first.refreshToken)

        assertTrue(second.accessToken.isNotBlank())
        assertTrue(second.refreshToken.isNotBlank())

        // Old refresh token must now be revoked in DB
        val oldJti = com.auth0.jwt.JWT.decode(first.refreshToken).id
        val row = transaction {
            RefreshTokens.selectAll()
                .where { RefreshTokens.jti eq oldJti }
                .firstOrNull()
        }
        assertNotNull(row, "Old refresh-token row must still exist")
        assertTrue(row[RefreshTokens.revoked], "Old refresh token must be marked revoked")
    }

    @Test
    fun `refresh with an already-revoked token throws 401 invalid-token`() {
        val first = authService.login("admin", "password123")
        authService.refresh(first.refreshToken)   // this revokes first.refreshToken
        val ex = org.junit.jupiter.api.assertThrows<ApiException> {
            authService.refresh(first.refreshToken)
        }
        assertEquals("invalid-token", ex.slug)
        assertEquals(HttpStatusCode.Unauthorized, ex.httpStatus)
    }

    @Test
    fun `refresh with a tampered token throws 401 invalid-token`() {
        val ex = org.junit.jupiter.api.assertThrows<ApiException> {
            authService.refresh("this.is.not.a.valid.jwt")
        }
        assertEquals("invalid-token", ex.slug)
        assertEquals(HttpStatusCode.Unauthorized, ex.httpStatus)
    }

    // ─── logout ────────────────────────────────────────────────────────────────

    @Test
    fun `logout revokes the refresh token identified by rti in the access token`() {
        val authResponse = authService.login("admin", "password123")
        val rti = com.auth0.jwt.JWT.decode(authResponse.accessToken).getClaim("rti").asString()
        assertNotNull(rti)

        authService.logout(rti)

        val row = transaction {
            RefreshTokens.selectAll()
                .where { RefreshTokens.jti eq rti }
                .firstOrNull()
        }
        assertNotNull(row, "Refresh-token row must still exist after logout")
        assertTrue(row[RefreshTokens.revoked], "Refresh token must be revoked after logout")
    }

    @Test
    fun `logout is idempotent when called twice`() {
        val authResponse = authService.login("admin", "password123")
        val rti = com.auth0.jwt.JWT.decode(authResponse.accessToken).getClaim("rti").asString()!!
        authService.logout(rti)
        authService.logout(rti) // second call must not throw
    }

    // ─── currentUser ───────────────────────────────────────────────────────────

    @Test
    fun `currentUser returns UserDto for existing user`() {
        val user = authService.currentUser("user-admin")
        assertEquals("user-admin", user.id)
        assertEquals("admin", user.username)
        assertEquals("Admin", user.displayName)
    }

    @Test
    fun `currentUser throws 401 unauthenticated for unknown userId`() {
        val ex = org.junit.jupiter.api.assertThrows<ApiException> {
            authService.currentUser("user-nobody")
        }
        assertEquals("unauthenticated", ex.slug)
        assertEquals(HttpStatusCode.Unauthorized, ex.httpStatus)
    }

    // ─── refresh does not leak which credential was wrong ──────────────────────

    @Test
    fun `login response user contains id, username, displayName`() {
        val response = authService.login("admin", "password123")
        assertNotNull(response.user.id)
        assertFalse(response.user.id.isBlank())
        assertFalse(response.user.username.isBlank())
        assertFalse(response.user.displayName.isBlank())
    }
}

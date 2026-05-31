package dev.jskrzypczak.photovault.server

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import dev.jskrzypczak.photovault.server.auth.JwtConfig
import dev.jskrzypczak.photovault.server.auth.JwtService
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull

class JwtServiceTest {

    private val config = JwtConfig(
        secret = "test-secret-value",
        issuer = "test-issuer",
        audience = "test-audience",
        accessTtlMinutes = 60,
        refreshTtlDays = 30,
    )
    private val service = JwtService(config)

    // ─── access token ──────────────────────────────────────────────────────────

    @Test
    fun `access token contains sub and rti claims`() {
        val token = service.generateAccessToken(userId = "user-1", refreshJti = "refresh-jti-abc")
        val decoded = JWT.decode(token)
        assertEquals("user-1", decoded.subject)
        assertEquals("refresh-jti-abc", decoded.getClaim("rti").asString())
    }

    @Test
    fun `access token issuer and audience match config`() {
        val token = service.generateAccessToken("user-1", "jti-x")
        val decoded = JWT.decode(token)
        assertEquals(config.issuer, decoded.issuer)
        assertEquals(config.audience, decoded.audience.first())
    }

    @Test
    fun `access token expiry is approximately accessTtlMinutes from now`() {
        val before = System.currentTimeMillis()
        val token = service.generateAccessToken("user-1", "jti-x")
        val after = System.currentTimeMillis()
        val decoded = JWT.decode(token)
        val expMs = decoded.expiresAt.time
        val expectedExpMs = before + config.accessTtlMinutes * 60_000L
        // Allow 5 seconds of clock slack
        assert(expMs in expectedExpMs - 5_000..expectedExpMs + after - before + 5_000) {
            "Expiry $expMs outside expected window [$expectedExpMs ± slack]"
        }
    }

    // ─── refresh token ─────────────────────────────────────────────────────────

    @Test
    fun `refresh token has non-blank jti claim and correct sub`() {
        val (token, jti) = service.generateRefreshToken(userId = "user-2")
        val decoded = JWT.decode(token)
        assertEquals("user-2", decoded.subject)
        assertNotNull(jti)
        assert(jti.isNotBlank()) { "jti must not be blank" }
        assertEquals(jti, decoded.id)
    }

    @Test
    fun `two calls to generateRefreshToken produce different jtis`() {
        val (_, jti1) = service.generateRefreshToken("user-1")
        val (_, jti2) = service.generateRefreshToken("user-1")
        assert(jti1 != jti2) { "Each refresh token must have a unique jti" }
    }

    // ─── verification ──────────────────────────────────────────────────────────

    @Test
    fun `verifyAccessToken returns decoded payload for a valid token`() {
        val token = service.generateAccessToken("user-3", "jti-r")
        val payload = service.verifyAccessToken(token)
        assertEquals("user-3", payload.subject)
        assertEquals("jti-r", payload.getClaim("rti").asString())
    }

    @Test
    fun `verifyAccessToken throws for a token signed with a different secret`() {
        val otherService = JwtService(config.copy(secret = "completely-different-secret"))
        val foreignToken = otherService.generateAccessToken("user-x", "jti-y")
        assertFailsWith<Exception> {
            service.verifyAccessToken(foreignToken)
        }
    }

    @Test
    fun `verifyRefreshToken returns decoded payload for a valid token`() {
        val (token, jti) = service.generateRefreshToken("user-4")
        val payload = service.verifyRefreshToken(token)
        assertEquals("user-4", payload.subject)
        assertEquals(jti, payload.id)
    }

    @Test
    fun `verifyRefreshToken throws for token signed with wrong secret`() {
        val otherService = JwtService(config.copy(secret = "wrong-secret"))
        val (foreignToken, _) = otherService.generateRefreshToken("user-x")
        assertFailsWith<Exception> {
            service.verifyRefreshToken(foreignToken)
        }
    }

    @Test
    fun `verifier uses HMAC256 algorithm (not none)`() {
        val algorithm = Algorithm.HMAC256(config.secret)
        val verifier = JWT.require(algorithm)
            .withIssuer(config.issuer)
            .withAudience(config.audience)
            .build()
        val token = service.generateAccessToken("user-5", "jti-z")
        // If algorithm were "none" this would throw
        val decoded = verifier.verify(token)
        assertEquals("user-5", decoded.subject)
    }
}

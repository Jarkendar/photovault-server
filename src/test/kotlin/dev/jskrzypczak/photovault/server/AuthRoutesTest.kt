package dev.jskrzypczak.photovault.server

import dev.jskrzypczak.photovault.server.auth.AuthService
import dev.jskrzypczak.photovault.server.auth.JwtConfig
import dev.jskrzypczak.photovault.server.auth.JwtService
import dev.jskrzypczak.photovault.server.db.initDatabase
import dev.jskrzypczak.photovault.server.plugins.configureSecurity
import dev.jskrzypczak.photovault.server.plugins.configureSerialization
import dev.jskrzypczak.photovault.server.plugins.configureStatusPages
import dev.jskrzypczak.photovault.server.routes.authRoutes
import dev.jskrzypczak.photovault.server.routes.healthRoutes
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.exposed.sql.Database
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.TestInstance
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AuthRoutesTest {

    companion object {
        @JvmStatic
        @Container
        val postgres: PostgreSQLContainer<*> = PostgreSQLContainer("postgres:16-alpine")
    }

    private lateinit var jwtConfig: JwtConfig
    private lateinit var jwtService: JwtService
    private lateinit var authService: AuthService

    private val lenient = Json { ignoreUnknownKeys = true }

    @BeforeAll
    fun setup() {
        Database.connect(
            url = postgres.jdbcUrl,
            driver = "org.postgresql.Driver",
            user = postgres.username,
            password = postgres.password,
        )
        initDatabase()

        jwtConfig = JwtConfig(
            secret = "route-test-secret",
            issuer = "photovault",
            audience = "photovault-client",
            accessTtlMinutes = 60,
            refreshTtlDays = 30,
        )
        jwtService = JwtService(jwtConfig)
        authService = AuthService(jwtService)
    }

    /** Boots a minimal testApplication with Security + Auth routes. */
    private fun withApp(block: suspend io.ktor.server.testing.ApplicationTestBuilder.() -> Unit) =
        testApplication {
            application {
                configureSerialization()
                configureStatusPages()
                configureSecurity(jwtConfig)
                routing {
                    route("/v1") {
                        healthRoutes("test")
                        authRoutes(authService)
                    }
                }
            }
            block()
        }

    // ─── POST /v1/auth/login ───────────────────────────────────────────────────

    @Test
    fun `POST login with valid credentials returns 200 with token pair and user`() = withApp {
        val response = client.post("/v1/auth/login") {
            contentType(ContentType.Application.Json)
            setBody("""{"username":"admin","password":"password123"}""")
        }
        assertEquals(HttpStatusCode.OK, response.status)
        val body = lenient.parseToJsonElement(response.bodyAsText()).jsonObject
        assertTrue(body["accessToken"]?.jsonPrimitive?.content?.isNotBlank() == true)
        assertTrue(body["refreshToken"]?.jsonPrimitive?.content?.isNotBlank() == true)
        val user = body["user"]?.jsonObject
        assertEquals("admin", user?.get("username")?.jsonPrimitive?.content)
    }

    @Test
    fun `POST login with wrong password returns 401 problem+json invalid-credentials`() = withApp {
        val response = client.post("/v1/auth/login") {
            contentType(ContentType.Application.Json)
            setBody("""{"username":"admin","password":"wrong"}""")
        }
        assertEquals(HttpStatusCode.Unauthorized, response.status)
        val contentType = response.headers[HttpHeaders.ContentType] ?: ""
        assertTrue(contentType.startsWith("application/problem+json"))
        val body = lenient.parseToJsonElement(response.bodyAsText()).jsonObject
        val typeUri = body["type"]?.jsonPrimitive?.content ?: ""
        assertTrue(typeUri.endsWith("invalid-credentials"))
    }

    @Test
    fun `POST login with missing body fields returns 400`() = withApp {
        val response = client.post("/v1/auth/login") {
            contentType(ContentType.Application.Json)
            setBody("""{"username":"admin"}""")
        }
        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    // ─── POST /v1/auth/refresh ─────────────────────────────────────────────────

    @Test
    fun `POST refresh with valid refresh token returns 200 new token pair`() = withApp {
        val loginResp = client.post("/v1/auth/login") {
            contentType(ContentType.Application.Json)
            setBody("""{"username":"admin","password":"password123"}""")
        }
        val loginBody = lenient.parseToJsonElement(loginResp.bodyAsText()).jsonObject
        val refreshToken = loginBody["refreshToken"]!!.jsonPrimitive.content

        val refreshResp = client.post("/v1/auth/refresh") {
            contentType(ContentType.Application.Json)
            setBody("""{"refreshToken":"$refreshToken"}""")
        }
        assertEquals(HttpStatusCode.OK, refreshResp.status)
        val refreshBody = lenient.parseToJsonElement(refreshResp.bodyAsText()).jsonObject
        val newAccessToken = refreshBody["accessToken"]?.jsonPrimitive?.content
        assertTrue(newAccessToken?.isNotBlank() == true)
        // New tokens must differ from old ones
        assertFalse(newAccessToken == loginBody["accessToken"]?.jsonPrimitive?.content)
    }

    @Test
    fun `POST refresh with revoked token returns 401 invalid-token`() = withApp {
        val loginBody = lenient.parseToJsonElement(
            client.post("/v1/auth/login") {
                contentType(ContentType.Application.Json)
                setBody("""{"username":"admin","password":"password123"}""")
            }.bodyAsText()
        ).jsonObject
        val refreshToken = loginBody["refreshToken"]!!.jsonPrimitive.content

        // First refresh revokes the original token
        client.post("/v1/auth/refresh") {
            contentType(ContentType.Application.Json)
            setBody("""{"refreshToken":"$refreshToken"}""")
        }

        // Second refresh with the same (now revoked) token
        val response = client.post("/v1/auth/refresh") {
            contentType(ContentType.Application.Json)
            setBody("""{"refreshToken":"$refreshToken"}""")
        }
        assertEquals(HttpStatusCode.Unauthorized, response.status)
        val body = lenient.parseToJsonElement(response.bodyAsText()).jsonObject
        assertTrue(body["type"]?.jsonPrimitive?.content?.endsWith("invalid-token") == true)
    }

    // ─── GET /v1/auth/me ──────────────────────────────────────────────────────

    @Test
    fun `GET me without auth header returns 401 unauthenticated problem+json`() = withApp {
        val response = client.get("/v1/auth/me")
        assertEquals(HttpStatusCode.Unauthorized, response.status)
        val contentType = response.headers[HttpHeaders.ContentType] ?: ""
        assertTrue(contentType.startsWith("application/problem+json"))
        val body = lenient.parseToJsonElement(response.bodyAsText()).jsonObject
        assertTrue(body["type"]?.jsonPrimitive?.content?.endsWith("unauthenticated") == true)
    }

    @Test
    fun `GET me with valid access token returns 200 UserDto`() = withApp {
        val loginBody = lenient.parseToJsonElement(
            client.post("/v1/auth/login") {
                contentType(ContentType.Application.Json)
                setBody("""{"username":"admin","password":"password123"}""")
            }.bodyAsText()
        ).jsonObject
        val accessToken = loginBody["accessToken"]!!.jsonPrimitive.content

        val meResponse = client.get("/v1/auth/me") {
            header(HttpHeaders.Authorization, "Bearer $accessToken")
        }
        assertEquals(HttpStatusCode.OK, meResponse.status)
        val meBody = lenient.parseToJsonElement(meResponse.bodyAsText()).jsonObject
        assertEquals("user-admin", meBody["id"]?.jsonPrimitive?.content)
        assertEquals("admin", meBody["username"]?.jsonPrimitive?.content)
    }

    // ─── POST /v1/auth/logout ─────────────────────────────────────────────────

    @Test
    fun `POST logout with valid access token returns 204`() = withApp {
        val loginBody = lenient.parseToJsonElement(
            client.post("/v1/auth/login") {
                contentType(ContentType.Application.Json)
                setBody("""{"username":"admin","password":"password123"}""")
            }.bodyAsText()
        ).jsonObject
        val accessToken = loginBody["accessToken"]!!.jsonPrimitive.content

        val logoutResponse = client.post("/v1/auth/logout") {
            header(HttpHeaders.Authorization, "Bearer $accessToken")
        }
        assertEquals(HttpStatusCode.NoContent, logoutResponse.status)
    }

    @Test
    fun `POST logout then refresh original token returns 401`() = withApp {
        val loginBody = lenient.parseToJsonElement(
            client.post("/v1/auth/login") {
                contentType(ContentType.Application.Json)
                setBody("""{"username":"admin","password":"password123"}""")
            }.bodyAsText()
        ).jsonObject
        val accessToken = loginBody["accessToken"]!!.jsonPrimitive.content
        val refreshToken = loginBody["refreshToken"]!!.jsonPrimitive.content

        client.post("/v1/auth/logout") {
            header(HttpHeaders.Authorization, "Bearer $accessToken")
        }

        val refreshResponse = client.post("/v1/auth/refresh") {
            contentType(ContentType.Application.Json)
            setBody("""{"refreshToken":"$refreshToken"}""")
        }
        assertEquals(HttpStatusCode.Unauthorized, refreshResponse.status)
    }

    @Test
    fun `POST logout without auth header returns 401`() = withApp {
        val response = client.post("/v1/auth/logout")
        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    // ─── regression: health still public ─────────────────────────────────────

    @Test
    fun `GET health is still public and returns 200`() = withApp {
        val response = client.get("/v1/health")
        assertEquals(HttpStatusCode.OK, response.status)
    }
}

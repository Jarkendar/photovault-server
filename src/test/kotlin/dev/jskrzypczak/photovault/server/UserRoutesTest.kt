package dev.jskrzypczak.photovault.server

import dev.jskrzypczak.photovault.server.auth.AuthService
import dev.jskrzypczak.photovault.server.auth.JwtConfig
import dev.jskrzypczak.photovault.server.auth.JwtService
import dev.jskrzypczak.photovault.server.db.initDatabase
import dev.jskrzypczak.photovault.server.db.tables.Users
import dev.jskrzypczak.photovault.server.plugins.configureSecurity
import dev.jskrzypczak.photovault.server.plugins.configureSerialization
import dev.jskrzypczak.photovault.server.plugins.configureStatusPages
import dev.jskrzypczak.photovault.server.routes.authRoutes
import dev.jskrzypczak.photovault.server.routes.userRoutes
import dev.jskrzypczak.photovault.server.users.UserService
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
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.TestInstance
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class UserRoutesTest {

    companion object {
        @JvmStatic
        @Container
        val postgres: PostgreSQLContainer<*> = PostgreSQLContainer("postgres:16-alpine")
    }

    private lateinit var jwtConfig: JwtConfig
    private lateinit var jwtService: JwtService
    private lateinit var authService: AuthService
    private lateinit var userService: UserService

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
            secret = "user-route-test-secret",
            issuer = "photovault",
            audience = "photovault-client",
            accessTtlMinutes = 60,
            refreshTtlDays = 30,
        )
        jwtService = JwtService(jwtConfig)
        authService = AuthService(jwtService)
        userService = UserService()
    }

    private fun withApp(block: suspend io.ktor.server.testing.ApplicationTestBuilder.() -> Unit) =
        testApplication {
            application {
                configureSerialization()
                configureStatusPages()
                configureSecurity(jwtConfig)
                routing {
                    route("/v1") {
                        authRoutes(authService)
                        userRoutes(userService)
                    }
                }
            }
            block()
        }

    private suspend fun io.ktor.server.testing.ApplicationTestBuilder.loginToken(): String {
        val body = lenient.parseToJsonElement(
            client.post("/v1/auth/login") {
                contentType(ContentType.Application.Json)
                setBody("""{"username":"admin","password":"password123"}""")
            }.bodyAsText()
        ).jsonObject
        return body["accessToken"]!!.jsonPrimitive.content
    }

    // ── auth guard ────────────────────────────────────────────────────────────

    @Test
    fun `GET users without auth returns 401`() = withApp {
        val r = client.get("/v1/users")
        assertEquals(HttpStatusCode.Unauthorized, r.status)
    }

    // ── GET /v1/users ─────────────────────────────────────────────────────────

    @Test
    fun `GET users returns 200 with seeded admin`() = withApp {
        val token = loginToken()
        val r = client.get("/v1/users") { header(HttpHeaders.Authorization, "Bearer $token") }
        assertEquals(HttpStatusCode.OK, r.status)
        val items = lenient.parseToJsonElement(r.bodyAsText()).jsonObject["items"]!!.jsonArray
        assertTrue(items.isNotEmpty())
        val admin = items.firstOrNull { it.jsonObject["username"]?.jsonPrimitive?.content == "admin" }
        assertNotNull(admin, "admin user should be present in the response")
        assertNotNull(admin.jsonObject["id"])
        assertNotNull(admin.jsonObject["username"])
        assertNotNull(admin.jsonObject["displayName"])
        assertNotNull(admin.jsonObject["createdAt"])
        assertTrue(admin.jsonObject["createdAt"]!!.jsonPrimitive.content.isNotEmpty())
    }

    @Test
    fun `GET users items have all required fields`() = withApp {
        val token = loginToken()
        val r = client.get("/v1/users") { header(HttpHeaders.Authorization, "Bearer $token") }
        assertEquals(HttpStatusCode.OK, r.status)
        val items = lenient.parseToJsonElement(r.bodyAsText()).jsonObject["items"]!!.jsonArray
        items.forEach { item ->
            assertNotNull(item.jsonObject["id"])
            assertNotNull(item.jsonObject["username"])
            assertNotNull(item.jsonObject["displayName"])
            assertNotNull(item.jsonObject["createdAt"])
        }
    }

    @Test
    fun `GET users returns admin before newer users`() = withApp {
        // Insert a second user with a later timestamp than admin (seeded on startup)
        transaction {
            Users.insert {
                it[id] = "user-test-sorting"
                it[username] = "newer-user"
                it[displayName] = "Newer User"
                it[passwordHash] = "irrelevant"
                it[createdAt] = Instant.now()
            }
        }

        val token = loginToken()
        val r = client.get("/v1/users") { header(HttpHeaders.Authorization, "Bearer $token") }
        assertEquals(HttpStatusCode.OK, r.status)
        val items = lenient.parseToJsonElement(r.bodyAsText()).jsonObject["items"]!!.jsonArray
        assertTrue(items.size >= 2)
        assertEquals("admin", items.first().jsonObject["username"]?.jsonPrimitive?.content,
            "admin (seeded first) should be the first item when ordered by createdAt ASC")
    }
}

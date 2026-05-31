package dev.jskrzypczak.photovault.server

import dev.jskrzypczak.photovault.server.auth.AuthService
import dev.jskrzypczak.photovault.server.auth.JwtConfig
import dev.jskrzypczak.photovault.server.auth.JwtService
import dev.jskrzypczak.photovault.server.db.initDatabase
import dev.jskrzypczak.photovault.server.metadata.CategoryService
import dev.jskrzypczak.photovault.server.metadata.LabelService
import dev.jskrzypczak.photovault.server.metadata.TagService
import dev.jskrzypczak.photovault.server.plugins.configureSecurity
import dev.jskrzypczak.photovault.server.plugins.configureSerialization
import dev.jskrzypczak.photovault.server.plugins.configureStatusPages
import dev.jskrzypczak.photovault.server.routes.authRoutes
import dev.jskrzypczak.photovault.server.routes.categoryRoutes
import dev.jskrzypczak.photovault.server.routes.labelRoutes
import dev.jskrzypczak.photovault.server.routes.tagRoutes
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.patch
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
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.TestInstance
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class LabelRoutesTest {

    companion object {
        @JvmStatic
        @Container
        val postgres: PostgreSQLContainer<*> = PostgreSQLContainer("postgres:16-alpine")
    }

    private lateinit var jwtConfig: JwtConfig
    private lateinit var jwtService: JwtService
    private lateinit var authService: AuthService
    private lateinit var tagService: TagService
    private lateinit var categoryService: CategoryService
    private lateinit var labelService: LabelService

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
            secret = "label-route-test-secret",
            issuer = "photovault",
            audience = "photovault-client",
            accessTtlMinutes = 60,
            refreshTtlDays = 30,
        )
        jwtService = JwtService(jwtConfig)
        authService = AuthService(jwtService)
        tagService = TagService()
        categoryService = CategoryService()
        labelService = LabelService()
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
                        tagRoutes(tagService)
                        categoryRoutes(categoryService)
                        labelRoutes(labelService)
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
    fun `GET labels without auth returns 401`() = withApp {
        val r = client.get("/v1/labels")
        assertEquals(HttpStatusCode.Unauthorized, r.status)
    }

    // ── GET /v1/labels ────────────────────────────────────────────────────────

    @Test
    fun `GET labels returns 200 with all 6 seeded labels`() = withApp {
        val token = loginToken()
        val r = client.get("/v1/labels") { header(HttpHeaders.Authorization, "Bearer $token") }
        assertEquals(HttpStatusCode.OK, r.status)
        val items = lenient.parseToJsonElement(r.bodyAsText()).jsonObject["items"]!!.jsonArray
        assertEquals(6, items.size)
        val ids = items.map { it.jsonObject["id"]?.jsonPrimitive?.content }.toSet()
        assertTrue("label-red" in ids)
        assertTrue("label-orange" in ids)
        assertTrue("label-yellow" in ids)
        assertTrue("label-green" in ids)
        assertTrue("label-blue" in ids)
        assertTrue("label-purple" in ids)
    }

    @Test
    fun `GET labels items have colorHex and photoCount`() = withApp {
        val token = loginToken()
        val r = client.get("/v1/labels") { header(HttpHeaders.Authorization, "Bearer $token") }
        val items = lenient.parseToJsonElement(r.bodyAsText()).jsonObject["items"]!!.jsonArray
        items.forEach { item ->
            assertNotNull(item.jsonObject["colorHex"])
            assertNotNull(item.jsonObject["photoCount"])
        }
    }

    // ── write methods are not allowed ─────────────────────────────────────────

    @Test
    fun `POST labels returns 405 Method Not Allowed`() = withApp {
        val token = loginToken()
        val r = client.post("/v1/labels") {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody("""{"name":"purple","colorHex":"#000000"}""")
        }
        assertEquals(HttpStatusCode.MethodNotAllowed, r.status)
    }

    @Test
    fun `PATCH labels collection returns 405 Method Not Allowed`() = withApp {
        val token = loginToken()
        val r = client.patch("/v1/labels") {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody("""{"colorHex":"#000000"}""")
        }
        assertEquals(HttpStatusCode.MethodNotAllowed, r.status)
    }

    @Test
    fun `DELETE labels collection returns 405 Method Not Allowed`() = withApp {
        val token = loginToken()
        val r = client.delete("/v1/labels") {
            header(HttpHeaders.Authorization, "Bearer $token")
        }
        assertEquals(HttpStatusCode.MethodNotAllowed, r.status)
    }
}

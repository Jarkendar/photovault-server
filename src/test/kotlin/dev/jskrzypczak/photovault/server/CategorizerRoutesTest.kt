package dev.jskrzypczak.photovault.server

import dev.jskrzypczak.photovault.server.auth.AuthService
import dev.jskrzypczak.photovault.server.auth.JwtConfig
import dev.jskrzypczak.photovault.server.auth.JwtService
import dev.jskrzypczak.photovault.server.categorizer.CategorizerConfig
import dev.jskrzypczak.photovault.server.categorizer.CategorizerService
import dev.jskrzypczak.photovault.server.db.initDatabase
import dev.jskrzypczak.photovault.server.db.tables.Photos
import dev.jskrzypczak.photovault.server.db.tables.Users
import dev.jskrzypczak.photovault.server.photos.PhotoService
import dev.jskrzypczak.photovault.server.plugins.configureSecurity
import dev.jskrzypczak.photovault.server.plugins.configureSerialization
import dev.jskrzypczak.photovault.server.plugins.configureStatusPages
import dev.jskrzypczak.photovault.server.routes.authRoutes
import dev.jskrzypczak.photovault.server.routes.categorizerRoutes
import dev.jskrzypczak.photovault.server.storage.PhotoAssetStorage
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.TestInstance
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.nio.file.Files
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CategorizerRoutesTest {

    companion object {
        @JvmStatic
        @Container
        val postgres: PostgreSQLContainer<*> = PostgreSQLContainer("postgres:16-alpine")
    }

    private lateinit var jwtConfig: JwtConfig
    private lateinit var jwtService: JwtService
    private lateinit var authService: AuthService
    private lateinit var photoService: PhotoService

    private val runCount = AtomicInteger(0)
    private val fakeRunner = dev.jskrzypczak.photovault.server.categorizer.CommandRunner { _, _ ->
        runCount.incrementAndGet()
        0
    }

    private val configuredConfig = CategorizerConfig(command = "echo categorize", workdir = ".")
    private val unconfiguredConfig = CategorizerConfig(command = "", workdir = ".")

    private val lenient = Json { ignoreUnknownKeys = true }

    private val userId = "user-cattest"
    private val photoId = "photo-cattest-001"

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
            secret = "categorizer-test-secret",
            issuer = "photovault",
            audience = "photovault-client",
            accessTtlMinutes = 60,
            refreshTtlDays = 30,
        )
        jwtService = JwtService(jwtConfig)
        authService = AuthService(jwtService)

        val tmpStorage = PhotoAssetStorage(Files.createTempDirectory("cattest").also { it.toFile().deleteOnExit() })
        photoService = PhotoService(tmpStorage)

        insertFixtures()
    }

    private fun insertFixtures() = transaction {
        if (Users.selectAll().where { Users.id eq userId }.count() == 0L) {
            Users.insert {
                it[id] = userId
                it[username] = "cattest"
                it[displayName] = "CatTest"
                it[passwordHash] = "x"
                it[createdAt] = Instant.now()
            }
        }
        if (Photos.selectAll().where { Photos.id eq photoId }.count() == 0L) {
            Photos.insert {
                it[id] = photoId
                it[name] = "cattest.jpg"
                it[sizeBytes] = 1_000L
                it[mimeType] = "image/jpeg"
                it[width] = 800
                it[height] = 600
                it[uploadedAt] = Instant.now()
                it[uploadedBy] = userId
                it[isFavorite] = false
                it[processingStatus] = "ready"
            }
        }
    }

    private fun adminToken() = jwtService.generateAccessToken(userId, "test-jti", role = "admin")
    private fun regularToken() = jwtService.generateAccessToken(userId, "test-jti", role = null)

    private fun withApp(
        config: CategorizerConfig = configuredConfig,
        block: suspend io.ktor.server.testing.ApplicationTestBuilder.() -> Unit,
    ) = testApplication {
        application {
            configureSerialization()
            configureStatusPages()
            configureSecurity(jwtConfig)
            routing {
                route("/v1") {
                    authRoutes(authService)
                    categorizerRoutes(CategorizerService(config, photoService, fakeRunner))
                }
            }
        }
        block()
    }

    // ── auth guard ────────────────────────────────────────────────────────────

    @Test
    fun `GET status without auth returns 401`() = withApp {
        val r = client.get("/v1/admin/categorizer/status")
        assertEquals(HttpStatusCode.Unauthorized, r.status)
    }

    @Test
    fun `GET status with regular token returns 403`() = withApp {
        val r = client.get("/v1/admin/categorizer/status") {
            header(HttpHeaders.Authorization, "Bearer ${regularToken()}")
        }
        assertEquals(HttpStatusCode.Forbidden, r.status)
    }

    @Test
    fun `POST run without auth returns 401`() = withApp {
        val r = client.post("/v1/admin/categorizer/run")
        assertEquals(HttpStatusCode.Unauthorized, r.status)
    }

    @Test
    fun `POST run with regular token returns 403`() = withApp {
        val r = client.post("/v1/admin/categorizer/run") {
            header(HttpHeaders.Authorization, "Bearer ${regularToken()}")
        }
        assertEquals(HttpStatusCode.Forbidden, r.status)
    }

    // ── status ────────────────────────────────────────────────────────────────

    @Test
    fun `GET status returns photo counts`() = withApp {
        val r = client.get("/v1/admin/categorizer/status") {
            header(HttpHeaders.Authorization, "Bearer ${adminToken()}")
        }
        assertEquals(HttpStatusCode.OK, r.status)
        val body = lenient.parseToJsonElement(r.bodyAsText()).jsonObject
        assertNotNull(body["running"])
        assertNotNull(body["pendingCount"])
        assertNotNull(body["readyCount"])
    }

    // ── run ───────────────────────────────────────────────────────────────────

    @Test
    fun `POST run returns 202 and triggers command`() = withApp {
        val before = runCount.get()
        val r = client.post("/v1/admin/categorizer/run") {
            header(HttpHeaders.Authorization, "Bearer ${adminToken()}")
        }
        assertEquals(HttpStatusCode.Accepted, r.status)
        // Give the coroutine a moment to execute the fake runner
        Thread.sleep(200)
        assert(runCount.get() > before) { "CommandRunner should have been called" }
    }

    @Test
    fun `POST run when unconfigured returns 409`() = withApp(config = unconfiguredConfig) {
        val r = client.post("/v1/admin/categorizer/run") {
            header(HttpHeaders.Authorization, "Bearer ${adminToken()}")
        }
        assertEquals(HttpStatusCode.Conflict, r.status)
        val body = lenient.parseToJsonElement(r.bodyAsText()).jsonObject
        assertEquals("categorizer-not-configured", body["type"]?.jsonPrimitive?.content?.substringAfterLast("/"))
    }

    // ── recategorize ──────────────────────────────────────────────────────────

    @Test
    fun `POST recategorize resets photo to pending_categorization`() = withApp {
        val r = client.post("/v1/admin/categorizer/recategorize/$photoId") {
            header(HttpHeaders.Authorization, "Bearer ${adminToken()}")
        }
        assertEquals(HttpStatusCode.NoContent, r.status)
        val status = transaction {
            Photos.selectAll().where { Photos.id eq photoId }
                .single()[Photos.processingStatus]
        }
        assertEquals("pending_categorization", status)
    }

    @Test
    fun `POST recategorize unknown photo returns 404`() = withApp {
        val r = client.post("/v1/admin/categorizer/recategorize/photo-does-not-exist") {
            header(HttpHeaders.Authorization, "Bearer ${adminToken()}")
        }
        assertEquals(HttpStatusCode.NotFound, r.status)
    }

    @Test
    fun `POST recategorize without auth returns 401`() = withApp {
        val r = client.post("/v1/admin/categorizer/recategorize/$photoId")
        assertEquals(HttpStatusCode.Unauthorized, r.status)
    }
}

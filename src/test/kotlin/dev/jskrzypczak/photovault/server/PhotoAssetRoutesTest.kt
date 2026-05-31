package dev.jskrzypczak.photovault.server

import dev.jskrzypczak.photovault.server.auth.AuthService
import dev.jskrzypczak.photovault.server.auth.JwtConfig
import dev.jskrzypczak.photovault.server.auth.JwtService
import dev.jskrzypczak.photovault.server.db.initDatabase
import dev.jskrzypczak.photovault.server.db.tables.Photos
import dev.jskrzypczak.photovault.server.db.tables.Users
import dev.jskrzypczak.photovault.server.photos.PhotoService
import dev.jskrzypczak.photovault.server.plugins.configureSecurity
import dev.jskrzypczak.photovault.server.plugins.configureSerialization
import dev.jskrzypczak.photovault.server.plugins.configureStatusPages
import dev.jskrzypczak.photovault.server.routes.authRoutes
import dev.jskrzypczak.photovault.server.routes.photoRoutes
import dev.jskrzypczak.photovault.server.storage.PhotoAssetStorage
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsBytes
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
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.TestInstance
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.nio.file.Files
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PhotoAssetRoutesTest {

    companion object {
        @JvmStatic
        @Container
        val postgres: PostgreSQLContainer<*> = PostgreSQLContainer("postgres:16-alpine")
    }

    private lateinit var jwtConfig: JwtConfig
    private lateinit var jwtService: JwtService
    private lateinit var authService: AuthService
    private lateinit var photoService: PhotoService

    private val lenient = Json { ignoreUnknownKeys = true }

    // Fixture content — tiny byte sequences (distinguishable seeds, not real images)
    private val thumbnailBytes = byteArrayOf(0x10.toByte(), 0x20.toByte(), 0x30.toByte())
    private val mediumBytes    = byteArrayOf(0x40.toByte(), 0x50.toByte(), 0x60.toByte())
    private val originalBytes  = byteArrayOf(0x70.toByte(), 0x80.toByte(), 0x90.toByte())

    // IDs
    private val userId          = "user-asset-test"
    private val photoDoneId     = "photo-asset-done"
    private val photoProcessId  = "photo-asset-processing"

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
            secret = "asset-route-test-secret",
            issuer = "photovault",
            audience = "photovault-client",
            accessTtlMinutes = 60,
            refreshTtlDays = 30,
        )
        jwtService = JwtService(jwtConfig)
        authService = AuthService(jwtService)

        // Create temp directory as storage root and write seed files
        val storageRoot = Files.createTempDirectory("photovault-asset-test")
        val photoStorage = PhotoAssetStorage(storageRoot)

        // Seed files for the "done" photo
        storageRoot.resolve("photo-asset-done").toFile().mkdirs()
        storageRoot.resolve("photo-asset-done/thumbnail.jpg").toFile().writeBytes(thumbnailBytes)
        storageRoot.resolve("photo-asset-done/medium.jpg").toFile().writeBytes(mediumBytes)
        storageRoot.resolve("photo-asset-done/original.jpg").toFile().writeBytes(originalBytes)

        // Seed files for the "processing" photo — only original present
        storageRoot.resolve("photo-asset-processing").toFile().mkdirs()
        storageRoot.resolve("photo-asset-processing/original.jpg").toFile().writeBytes(originalBytes)

        photoService = PhotoService(photoStorage)

        insertFixtures()
    }

    private fun insertFixtures() = transaction {
        if (Users.select(Users.id).where { Users.id eq userId }.count() == 0L) {
            Users.insert {
                it[id] = userId
                it[username] = "asset-tester"
                it[displayName] = "Asset Tester"
                it[passwordHash] = "x"
                it[createdAt] = Instant.now()
            }
        }

        fun insertPhoto(pid: String, status: String, thumb: String?, medium: String?, original: String?) {
            if (Photos.select(Photos.id).where { Photos.id eq pid }.count() == 0L) {
                Photos.insert {
                    it[id] = pid
                    it[name] = "asset_$pid.jpg"
                    it[sizeBytes] = 1_000L
                    it[mimeType] = "image/jpeg"
                    it[width] = 100
                    it[height] = 100
                    it[uploadedAt] = Instant.now()
                    it[uploadedBy] = userId
                    it[isFavorite] = false
                    it[processingStatus] = status
                    it[thumbnailPath] = thumb
                    it[mediumPath] = medium
                    it[originalPath] = original
                }
            }
        }

        insertPhoto(
            photoDoneId, "done",
            "photo-asset-done/thumbnail.jpg",
            "photo-asset-done/medium.jpg",
            "photo-asset-done/original.jpg",
        )
        insertPhoto(
            photoProcessId, "processing",
            null,   // not yet generated
            null,
            "photo-asset-processing/original.jpg",
        )
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private fun withApp(block: suspend io.ktor.server.testing.ApplicationTestBuilder.() -> Unit) =
        testApplication {
            application {
                configureSerialization()
                configureStatusPages()
                configureSecurity(jwtConfig)
                routing {
                    route("/v1") {
                        authRoutes(authService)
                        photoRoutes(photoService)
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

    // ── 401 unauthenticated ───────────────────────────────────────────────────

    @Test
    fun `GET thumbnail without auth returns 401 unauthenticated`() = withApp {
        val r = client.get("/v1/photos/$photoDoneId/thumbnail")
        assertEquals(HttpStatusCode.Unauthorized, r.status)
        val body = lenient.parseToJsonElement(r.bodyAsText()).jsonObject
        assertTrue(body["type"]?.jsonPrimitive?.content?.endsWith("unauthenticated") == true)
    }

    @Test
    fun `GET medium without auth returns 401 unauthenticated`() = withApp {
        val r = client.get("/v1/photos/$photoDoneId/medium")
        assertEquals(HttpStatusCode.Unauthorized, r.status)
    }

    @Test
    fun `GET original without auth returns 401 unauthenticated`() = withApp {
        val r = client.get("/v1/photos/$photoDoneId/original")
        assertEquals(HttpStatusCode.Unauthorized, r.status)
    }

    // ── 200 streaming for done photo ──────────────────────────────────────────

    @Test
    fun `GET thumbnail for done photo returns 200 with correct bytes`() = withApp {
        val token = loginToken()
        val r = client.get("/v1/photos/$photoDoneId/thumbnail") {
            header(HttpHeaders.Authorization, "Bearer $token")
        }
        assertEquals(HttpStatusCode.OK, r.status)
        assertTrue(r.headers[HttpHeaders.ContentType]?.startsWith("image/jpeg") == true)
        assertTrue(r.bodyAsBytes().contentEquals(thumbnailBytes))
    }

    @Test
    fun `GET medium for done photo returns 200 with correct bytes`() = withApp {
        val token = loginToken()
        val r = client.get("/v1/photos/$photoDoneId/medium") {
            header(HttpHeaders.Authorization, "Bearer $token")
        }
        assertEquals(HttpStatusCode.OK, r.status)
        assertTrue(r.headers[HttpHeaders.ContentType]?.startsWith("image/jpeg") == true)
        assertTrue(r.bodyAsBytes().contentEquals(mediumBytes))
    }

    @Test
    fun `GET original for done photo returns 200 with correct bytes and mime type`() = withApp {
        val token = loginToken()
        val r = client.get("/v1/photos/$photoDoneId/original") {
            header(HttpHeaders.Authorization, "Bearer $token")
        }
        assertEquals(HttpStatusCode.OK, r.status)
        assertTrue(r.headers[HttpHeaders.ContentType]?.startsWith("image/jpeg") == true)
        assertTrue(r.bodyAsBytes().contentEquals(originalBytes))
    }

    // ── 423 for thumbnail/medium while processing ─────────────────────────────

    @Test
    fun `GET thumbnail for processing photo returns 423 processing-not-ready`() = withApp {
        val token = loginToken()
        val r = client.get("/v1/photos/$photoProcessId/thumbnail") {
            header(HttpHeaders.Authorization, "Bearer $token")
        }
        assertEquals(HttpStatusCode.Locked, r.status)
        val body = lenient.parseToJsonElement(r.bodyAsText()).jsonObject
        assertTrue(body["type"]?.jsonPrimitive?.content?.endsWith("processing-not-ready") == true)
        assertTrue(r.headers[HttpHeaders.ContentType]?.startsWith("application/problem+json") == true)
    }

    @Test
    fun `GET medium for processing photo returns 423 processing-not-ready`() = withApp {
        val token = loginToken()
        val r = client.get("/v1/photos/$photoProcessId/medium") {
            header(HttpHeaders.Authorization, "Bearer $token")
        }
        assertEquals(HttpStatusCode.Locked, r.status)
        val body = lenient.parseToJsonElement(r.bodyAsText()).jsonObject
        assertTrue(body["type"]?.jsonPrimitive?.content?.endsWith("processing-not-ready") == true)
    }

    // ── original always available regardless of processing status ─────────────

    @Test
    fun `GET original for processing photo returns 200`() = withApp {
        val token = loginToken()
        val r = client.get("/v1/photos/$photoProcessId/original") {
            header(HttpHeaders.Authorization, "Bearer $token")
        }
        assertEquals(HttpStatusCode.OK, r.status)
        assertTrue(r.bodyAsBytes().contentEquals(originalBytes))
    }

    // ── 404 for unknown photo ─────────────────────────────────────────────────

    @Test
    fun `GET thumbnail for unknown photo returns 404 photo-not-found`() = withApp {
        val token = loginToken()
        val r = client.get("/v1/photos/photo-does-not-exist/thumbnail") {
            header(HttpHeaders.Authorization, "Bearer $token")
        }
        assertEquals(HttpStatusCode.NotFound, r.status)
        val body = lenient.parseToJsonElement(r.bodyAsText()).jsonObject
        assertTrue(body["type"]?.jsonPrimitive?.content?.endsWith("photo-not-found") == true)
        assertTrue(r.headers[HttpHeaders.ContentType]?.startsWith("application/problem+json") == true)
    }

    @Test
    fun `GET original for unknown photo returns 404 photo-not-found`() = withApp {
        val token = loginToken()
        val r = client.get("/v1/photos/photo-does-not-exist/original") {
            header(HttpHeaders.Authorization, "Bearer $token")
        }
        assertEquals(HttpStatusCode.NotFound, r.status)
    }

    // ── caching headers ───────────────────────────────────────────────────────

    @Test
    fun `GET thumbnail returns ETag and Cache-Control headers`() = withApp {
        val token = loginToken()
        val r = client.get("/v1/photos/$photoDoneId/thumbnail") {
            header(HttpHeaders.Authorization, "Bearer $token")
        }
        assertEquals(HttpStatusCode.OK, r.status)
        assertNotNull(r.headers[HttpHeaders.ETag])
        val cc = r.headers[HttpHeaders.CacheControl]
        assertNotNull(cc)
        assertTrue(cc!!.contains("max-age=31536000"), "Expected max-age=31536000 in: $cc")
        assertTrue(cc.contains("private"), "Expected private in: $cc")
    }

    // ── 304 Not Modified (If-None-Match) ─────────────────────────────────────

    @Test
    fun `GET thumbnail with matching If-None-Match returns 304`() = withApp {
        val token = loginToken()

        // First request to get the ETag
        val r1 = client.get("/v1/photos/$photoDoneId/thumbnail") {
            header(HttpHeaders.Authorization, "Bearer $token")
        }
        assertEquals(HttpStatusCode.OK, r1.status)
        val etag = r1.headers[HttpHeaders.ETag]
        assertNotNull(etag)

        // Second request with matching ETag
        val r2 = client.get("/v1/photos/$photoDoneId/thumbnail") {
            header(HttpHeaders.Authorization, "Bearer $token")
            header(HttpHeaders.IfNoneMatch, etag!!)
        }
        assertEquals(HttpStatusCode.NotModified, r2.status)
    }

    @Test
    fun `GET original with matching If-None-Match returns 304`() = withApp {
        val token = loginToken()
        val r1 = client.get("/v1/photos/$photoDoneId/original") {
            header(HttpHeaders.Authorization, "Bearer $token")
        }
        val etag = r1.headers[HttpHeaders.ETag]!!

        val r2 = client.get("/v1/photos/$photoDoneId/original") {
            header(HttpHeaders.Authorization, "Bearer $token")
            header(HttpHeaders.IfNoneMatch, etag)
        }
        assertEquals(HttpStatusCode.NotModified, r2.status)
    }
}

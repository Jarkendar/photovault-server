package dev.jskrzypczak.photovault.server

import dev.jskrzypczak.photovault.server.auth.AuthService
import dev.jskrzypczak.photovault.server.auth.JwtConfig
import dev.jskrzypczak.photovault.server.auth.JwtService
import dev.jskrzypczak.photovault.server.db.initDatabase
import dev.jskrzypczak.photovault.server.db.tables.FaceClusters
import dev.jskrzypczak.photovault.server.db.tables.Faces
import dev.jskrzypczak.photovault.server.db.tables.PhotoTags
import dev.jskrzypczak.photovault.server.db.tables.Photos
import dev.jskrzypczak.photovault.server.db.tables.Tags
import dev.jskrzypczak.photovault.server.db.tables.Users
import dev.jskrzypczak.photovault.server.faces.FaceService
import dev.jskrzypczak.photovault.server.plugins.configureSecurity
import dev.jskrzypczak.photovault.server.plugins.configureSerialization
import dev.jskrzypczak.photovault.server.plugins.configureStatusPages
import dev.jskrzypczak.photovault.server.routes.authRoutes
import dev.jskrzypczak.photovault.server.routes.faceRoutes
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.TestInstance
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class FaceRoutesTest {

    companion object {
        @JvmStatic
        @Container
        val postgres: PostgreSQLContainer<*> = PostgreSQLContainer("postgres:16-alpine")
    }

    private lateinit var jwtConfig: JwtConfig
    private lateinit var jwtService: JwtService
    private lateinit var authService: AuthService
    private lateinit var faceService: FaceService

    private val lenient = Json { ignoreUnknownKeys = true }

    private val userId = "user-facetest"
    private val photoId = "photo-facetest-001"
    private val faceId = "face-facetest-001"
    private val clusterId = "fcluster-facetest-001"        // stays unlabeled — read-only tests
    private val labelClusterId = "fcluster-facetest-label" // used by label mutation tests
    private val labelFaceId = "face-facetest-label"
    private val tagId = "tag-facetest-001"
    private val tagDeniedId = "tag-facetest-denied"

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
            secret = "face-route-test-secret",
            issuer = "photovault",
            audience = "photovault-client",
            accessTtlMinutes = 60,
            refreshTtlDays = 30,
        )
        jwtService = JwtService(jwtConfig)
        authService = AuthService(jwtService)
        faceService = FaceService()

        insertFixtures()
    }

    private fun insertFixtures() = transaction {
        if (Users.selectAll().where { Users.id eq userId }.count() == 0L) {
            Users.insert {
                it[id] = userId
                it[username] = "facetest"
                it[displayName] = "FaceTest"
                it[passwordHash] = "x"
                it[createdAt] = Instant.now()
            }
        }
        if (Photos.selectAll().where { Photos.id eq photoId }.count() == 0L) {
            Photos.insert {
                it[id] = photoId
                it[name] = "facetest.jpg"
                it[sizeBytes] = 1_000L
                it[mimeType] = "image/jpeg"
                it[width] = 800
                it[height] = 600
                it[uploadedAt] = Instant.now()
                it[uploadedBy] = userId
                it[isFavorite] = false
                it[processingStatus] = "done"
            }
        }
        if (Tags.selectAll().where { Tags.id eq tagId }.count() == 0L) {
            Tags.insert { it[id] = tagId; it[name] = "#babcia" }
        }
        if (Tags.selectAll().where { Tags.id eq tagDeniedId }.count() == 0L) {
            Tags.insert { it[id] = tagDeniedId; it[name] = "#denied-tag" }
        }
        // Insert face cluster before face (FK order)
        if (FaceClusters.selectAll().where { FaceClusters.id eq clusterId }.count() == 0L) {
            FaceClusters.insert {
                it[id] = clusterId
                it[faceCount] = 1
                it[representativeFaceId] = null
                it[createdAt] = Instant.now()
            }
        }
        if (Faces.selectAll().where { Faces.id eq faceId }.count() == 0L) {
            Faces.insert {
                it[id] = faceId
                it[photoId] = this@FaceRoutesTest.photoId
                it[bboxX] = 10; it[bboxY] = 10; it[bboxW] = 80; it[bboxH] = 80
                it[detScore] = 0.95
                it[clusterId] = this@FaceRoutesTest.clusterId
                it[faceModel] = "buffalo_l"
                it[detectedAt] = Instant.now()
            }
        }
        // Point cluster representative at the face we just inserted
        FaceClusters.update({ FaceClusters.id eq clusterId }) {
            it[representativeFaceId] = faceId
        }

        // Separate cluster used exclusively by label mutation tests
        if (FaceClusters.selectAll().where { FaceClusters.id eq labelClusterId }.count() == 0L) {
            FaceClusters.insert {
                it[id] = labelClusterId
                it[faceCount] = 1
                it[representativeFaceId] = null
                it[createdAt] = Instant.now()
            }
        }
        if (Faces.selectAll().where { Faces.id eq labelFaceId }.count() == 0L) {
            Faces.insert {
                it[id] = labelFaceId
                it[photoId] = this@FaceRoutesTest.photoId
                it[bboxX] = 20; it[bboxY] = 20; it[bboxW] = 60; it[bboxH] = 60
                it[detScore] = 0.85
                it[clusterId] = this@FaceRoutesTest.labelClusterId
                it[faceModel] = "buffalo_l"
                it[detectedAt] = Instant.now()
            }
        }
        FaceClusters.update({ FaceClusters.id eq labelClusterId }) {
            it[representativeFaceId] = labelFaceId
        }

        // Pre-insert a denied row so we can verify it's not overwritten
        if (PhotoTags.selectAll()
                .where { (PhotoTags.photoId eq photoId) and (PhotoTags.tagId eq tagDeniedId) }
                .count() == 0L
        ) {
            PhotoTags.insert {
                it[PhotoTags.photoId] = this@FaceRoutesTest.photoId
                it[PhotoTags.tagId] = this@FaceRoutesTest.tagDeniedId
                it[PhotoTags.assignmentSource] = "denied"
            }
        }
    }

    private fun adminToken(): String =
        jwtService.generateAccessToken(userId, "test-jti", role = "admin")

    private fun regularToken(): String =
        jwtService.generateAccessToken(userId, "test-jti", role = null)

    private fun withApp(block: suspend io.ktor.server.testing.ApplicationTestBuilder.() -> Unit) =
        testApplication {
            application {
                configureSerialization()
                configureStatusPages()
                configureSecurity(jwtConfig)
                routing {
                    route("/v1") {
                        authRoutes(authService)
                        faceRoutes(faceService)
                    }
                }
            }
            block()
        }

    // ── auth guard ────────────────────────────────────────────────────────────

    @Test
    fun `GET face-clusters without auth returns 401`() = withApp {
        val r = client.get("/v1/admin/face-clusters")
        assertEquals(HttpStatusCode.Unauthorized, r.status)
    }

    @Test
    fun `GET face-clusters with regular token returns 403`() = withApp {
        val r = client.get("/v1/admin/face-clusters") {
            header(HttpHeaders.Authorization, "Bearer ${regularToken()}")
        }
        assertEquals(HttpStatusCode.Forbidden, r.status)
    }

    // ── GET /v1/admin/face-clusters ───────────────────────────────────────────

    @Test
    fun `GET face-clusters returns 200 with items array`() = withApp {
        val r = client.get("/v1/admin/face-clusters") {
            header(HttpHeaders.Authorization, "Bearer ${adminToken()}")
        }
        assertEquals(HttpStatusCode.OK, r.status)
        val body = lenient.parseToJsonElement(r.bodyAsText()).jsonObject
        val items = body["items"]!!.jsonArray
        assertTrue(items.isNotEmpty())
        val cluster = items.first { it.jsonObject["id"]?.jsonPrimitive?.content == clusterId }
        assertEquals(1, cluster.jsonObject["faceCount"]?.jsonPrimitive?.content?.toInt())
        assertEquals(photoId, cluster.jsonObject["representativePhotoId"]?.jsonPrimitive?.content)
    }

    @Test
    fun `GET face-clusters with unlabeled=true returns only unlabelled clusters`() = withApp {
        val r = client.get("/v1/admin/face-clusters?unlabeled=true") {
            header(HttpHeaders.Authorization, "Bearer ${adminToken()}")
        }
        assertEquals(HttpStatusCode.OK, r.status)
        val items = lenient.parseToJsonElement(r.bodyAsText()).jsonObject["items"]!!.jsonArray
        assertTrue(items.any { it.jsonObject["id"]?.jsonPrimitive?.content == clusterId })
    }

    // ── GET /v1/admin/face-clusters/{id}/faces ────────────────────────────────

    @Test
    fun `GET faces in cluster returns face list`() = withApp {
        val r = client.get("/v1/admin/face-clusters/$clusterId/faces") {
            header(HttpHeaders.Authorization, "Bearer ${adminToken()}")
        }
        assertEquals(HttpStatusCode.OK, r.status)
        val items = lenient.parseToJsonElement(r.bodyAsText()).jsonObject["items"]!!.jsonArray
        assertEquals(1, items.size)
        val face = items.first().jsonObject
        assertEquals(faceId, face["faceId"]?.jsonPrimitive?.content)
        assertEquals(photoId, face["photoId"]?.jsonPrimitive?.content)
        assertEquals(10, face["bboxX"]?.jsonPrimitive?.content?.toInt())
    }

    @Test
    fun `GET faces in nonexistent cluster returns 404`() = withApp {
        val r = client.get("/v1/admin/face-clusters/fcluster-does-not-exist/faces") {
            header(HttpHeaders.Authorization, "Bearer ${adminToken()}")
        }
        assertEquals(HttpStatusCode.NotFound, r.status)
    }

    // ── POST /v1/admin/face-clusters/{id}/label ───────────────────────────────

    @Test
    fun `POST label with tagId writes auto row in photo_tags`() = withApp {
        val r = client.post("/v1/admin/face-clusters/$labelClusterId/label") {
            header(HttpHeaders.Authorization, "Bearer ${adminToken()}")
            header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            setBody("""{"tagId":"$tagId"}""")
        }
        assertEquals(HttpStatusCode.OK, r.status)

        // Verify junction row was written with source='auto'
        val row = transaction {
            PhotoTags.selectAll()
                .where { (PhotoTags.photoId eq photoId) and (PhotoTags.tagId eq tagId) }
                .firstOrNull()
        }
        assertNotNull(row)
        assertEquals("auto", row[PhotoTags.assignmentSource])

        // Cluster now has tagId set
        val body = lenient.parseToJsonElement(r.bodyAsText()).jsonObject
        assertEquals(tagId, body["tagId"]?.jsonPrimitive?.content)
    }

    @Test
    fun `POST label does not overwrite denied row`() = withApp {
        // Tag tagDeniedId already has a 'denied' row for photoId (inserted in fixtures)
        val r = client.post("/v1/admin/face-clusters/$labelClusterId/label") {
            header(HttpHeaders.Authorization, "Bearer ${adminToken()}")
            header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            setBody("""{"tagId":"$tagDeniedId"}""")
        }
        assertEquals(HttpStatusCode.OK, r.status)

        // The denied row must remain — source must NOT be overwritten to 'auto'
        val row = transaction {
            PhotoTags.selectAll()
                .where { (PhotoTags.photoId eq photoId) and (PhotoTags.tagId eq tagDeniedId) }
                .firstOrNull()
        }
        assertNotNull(row)
        assertEquals("denied", row[PhotoTags.assignmentSource])
    }

    @Test
    fun `POST label without tagId and categoryId returns 400`() = withApp {
        val r = client.post("/v1/admin/face-clusters/$labelClusterId/label") {
            header(HttpHeaders.Authorization, "Bearer ${adminToken()}")
            header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            setBody("""{}""")
        }
        assertEquals(HttpStatusCode.BadRequest, r.status)
    }

    @Test
    fun `POST label nonexistent cluster returns 404`() = withApp {
        val r = client.post("/v1/admin/face-clusters/fcluster-none/label") {
            header(HttpHeaders.Authorization, "Bearer ${adminToken()}")
            header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            setBody("""{"tagId":"$tagId"}""")
        }
        assertEquals(HttpStatusCode.NotFound, r.status)
    }

    // ── DELETE /v1/admin/face-clusters/{id} ───────────────────────────────────

    @Test
    fun `DELETE cluster removes row and clears faces cluster_id`() = withApp {
        val deleteClusterId = "fcluster-deleteme"
        val deleteFaceId = "face-deleteme"
        transaction {
            FaceClusters.insert {
                it[id] = deleteClusterId
                it[faceCount] = 1
                it[representativeFaceId] = null
                it[createdAt] = Instant.now()
            }
            Faces.insert {
                it[id] = deleteFaceId
                it[photoId] = this@FaceRoutesTest.photoId
                it[bboxX] = 0; it[bboxY] = 0; it[bboxW] = 50; it[bboxH] = 50
                it[detScore] = 0.8
                it[clusterId] = deleteClusterId
                it[faceModel] = "buffalo_l"
                it[detectedAt] = Instant.now()
            }
        }

        val r = client.delete("/v1/admin/face-clusters/$deleteClusterId") {
            header(HttpHeaders.Authorization, "Bearer ${adminToken()}")
        }
        assertEquals(HttpStatusCode.NoContent, r.status)

        // Cluster row gone; face's cluster_id is SET NULL
        transaction {
            val clusterRow = FaceClusters.selectAll()
                .where { FaceClusters.id eq deleteClusterId }.firstOrNull()
            assertNull(clusterRow)
            val faceRow = Faces.selectAll().where { Faces.id eq deleteFaceId }.firstOrNull()
            assertNotNull(faceRow)
            assertNull(faceRow[Faces.clusterId])
        }
    }

    @Test
    fun `DELETE nonexistent cluster returns 404`() = withApp {
        val r = client.delete("/v1/admin/face-clusters/fcluster-none") {
            header(HttpHeaders.Authorization, "Bearer ${adminToken()}")
        }
        assertEquals(HttpStatusCode.NotFound, r.status)
    }
}

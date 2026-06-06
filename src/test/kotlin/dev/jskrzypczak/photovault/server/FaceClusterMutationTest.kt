package dev.jskrzypczak.photovault.server

import dev.jskrzypczak.photovault.server.auth.AuthService
import dev.jskrzypczak.photovault.server.auth.JwtConfig
import dev.jskrzypczak.photovault.server.auth.JwtService
import dev.jskrzypczak.photovault.server.db.initDatabase
import dev.jskrzypczak.photovault.server.db.tables.Categories
import dev.jskrzypczak.photovault.server.db.tables.FaceClusters
import dev.jskrzypczak.photovault.server.db.tables.Faces
import dev.jskrzypczak.photovault.server.db.tables.PhotoCategories
import dev.jskrzypczak.photovault.server.db.tables.PhotoTags
import dev.jskrzypczak.photovault.server.db.tables.Photos
import dev.jskrzypczak.photovault.server.db.tables.Tags
import dev.jskrzypczak.photovault.server.db.tables.Users
import dev.jskrzypczak.photovault.server.faces.FaceService
import dev.jskrzypczak.photovault.server.plugins.configureSecurity
import dev.jskrzypczak.photovault.server.plugins.configureSerialization
import dev.jskrzypczak.photovault.server.plugins.configureStatusPages
import dev.jskrzypczak.photovault.server.routes.faceRoutes
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
import kotlinx.serialization.json.int
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
class FaceClusterMutationTest {

    companion object {
        @JvmStatic
        @Container
        val postgres: PostgreSQLContainer<*> = PostgreSQLContainer("postgres:16-alpine")
    }

    private lateinit var jwtConfig: JwtConfig
    private lateinit var jwtService: JwtService
    private lateinit var faceService: FaceService

    private val lenient = Json { ignoreUnknownKeys = true }

    // Shared fixture ids
    private val userId   = "user-mut-test"
    private val photoId1 = "photo-mut-001"
    private val photoId2 = "photo-mut-002"
    private val tagId    = "tag-mut-001"
    private val catId    = "cat-mut-001"

    @BeforeAll
    fun setup() {
        Database.connect(
            url      = postgres.jdbcUrl,
            driver   = "org.postgresql.Driver",
            user     = postgres.username,
            password = postgres.password,
        )
        initDatabase()

        jwtConfig = JwtConfig(
            secret            = "mut-test-secret",
            issuer            = "photovault",
            audience          = "photovault-client",
            accessTtlMinutes  = 60,
            refreshTtlDays    = 30,
        )
        jwtService = JwtService(jwtConfig)
        faceService = FaceService()
        // AuthService constructed only to satisfy module wiring in tests that need it
        AuthService(jwtService)

        insertSharedFixtures()
    }

    private fun insertSharedFixtures() = transaction {
        if (Users.selectAll().where { Users.id eq userId }.count() == 0L) {
            Users.insert {
                it[id] = userId; it[username] = "muttest"
                it[displayName] = "MutTest"; it[passwordHash] = "x"
                it[createdAt] = Instant.now()
            }
        }
        for ((pid, name) in listOf(photoId1 to "mut1.jpg", photoId2 to "mut2.jpg")) {
            if (Photos.selectAll().where { Photos.id eq pid }.count() == 0L) {
                Photos.insert {
                    it[id] = pid; it[this.name] = name
                    it[sizeBytes] = 1_000L; it[mimeType] = "image/jpeg"
                    it[width] = 800; it[height] = 600
                    it[uploadedAt] = Instant.now(); it[uploadedBy] = userId
                    it[isFavorite] = false; it[processingStatus] = "done"
                }
            }
        }
        if (Tags.selectAll().where { Tags.id eq tagId }.count() == 0L) {
            Tags.insert { it[id] = tagId; it[name] = "#mutperson" }
        }
        if (Categories.selectAll().where { Categories.id eq catId }.count() == 0L) {
            Categories.insert { it[id] = catId; it[name] = "MutCat"; it[colorHex] = "#aabbcc" }
        }
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private fun adminToken()   = jwtService.generateAccessToken(userId, "jti-mut", role = "admin")
    private fun regularToken() = jwtService.generateAccessToken(userId, "jti-mut", role = null)

    private fun withApp(block: suspend io.ktor.server.testing.ApplicationTestBuilder.() -> Unit) =
        testApplication {
            application {
                configureSerialization()
                configureStatusPages()
                configureSecurity(jwtConfig)
                routing { route("/v1") { faceRoutes(faceService) } }
            }
            block()
        }

    private fun makeCluster(id: String, vararg faces: Triple<String, String, Double>): Unit = transaction {
        if (FaceClusters.selectAll().where { FaceClusters.id eq id }.count() == 0L) {
            FaceClusters.insert {
                it[FaceClusters.id] = id; it[faceCount] = 0
                it[representativeFaceId] = null; it[createdAt] = Instant.now()
            }
        }
        for ((faceId, pid, score) in faces) {
            if (Faces.selectAll().where { Faces.id eq faceId }.count() == 0L) {
                Faces.insert {
                    it[Faces.id] = faceId; it[photoId] = pid
                    it[bboxX] = 0; it[bboxY] = 0; it[bboxW] = 50; it[bboxH] = 50
                    it[detScore] = score; it[clusterId] = id
                    it[faceModel] = "buffalo_l"; it[detectedAt] = Instant.now()
                }
            }
        }
        val repId = faces.maxByOrNull { it.third }?.first
        FaceClusters.update({ FaceClusters.id eq id }) {
            it[faceCount] = faces.size; it[representativeFaceId] = repId
        }
    }

    private fun labelViaService(clusterId: String, tagId: String? = null, categoryId: String? = null) {
        faceService.labelCluster(clusterId, tagId, categoryId)
    }

    // ── unlabel ───────────────────────────────────────────────────────────────

    @Test
    fun `POST unlabel removes auto tag assignment and clears cluster tagId`() = withApp {
        val cid = "fcluster-unlabel-tag"
        val fid = "face-unlabel-tag"
        makeCluster(cid, Triple(fid, photoId1, 0.9))
        labelViaService(cid, tagId = tagId)

        // Verify auto row exists before unlabel
        val beforeRow = transaction {
            PhotoTags.selectAll()
                .where { (PhotoTags.photoId eq photoId1) and (PhotoTags.tagId eq tagId) }
                .firstOrNull()
        }
        assertNotNull(beforeRow)
        assertEquals("auto", beforeRow[PhotoTags.assignmentSource])

        val r = client.post("/v1/admin/face-clusters/$cid/unlabel") {
            header(HttpHeaders.Authorization, "Bearer ${adminToken()}")
        }
        assertEquals(HttpStatusCode.OK, r.status)

        val body = lenient.parseToJsonElement(r.bodyAsText()).jsonObject
        // explicitNulls=false means null fields are omitted; key absent means null
        assertTrue(!body.containsKey("tagId") || body["tagId"].toString() == "null")

        val afterRow = transaction {
            PhotoTags.selectAll()
                .where { (PhotoTags.photoId eq photoId1) and (PhotoTags.tagId eq tagId) }
                .firstOrNull()
        }
        assertNull(afterRow, "auto assignment should have been deleted")
    }

    @Test
    fun `POST unlabel removes auto category assignment`() = withApp {
        val cid = "fcluster-unlabel-cat"
        val fid = "face-unlabel-cat"
        makeCluster(cid, Triple(fid, photoId1, 0.9))
        labelViaService(cid, categoryId = catId)

        val r = client.post("/v1/admin/face-clusters/$cid/unlabel") {
            header(HttpHeaders.Authorization, "Bearer ${adminToken()}")
        }
        assertEquals(HttpStatusCode.OK, r.status)

        val afterRow = transaction {
            PhotoCategories.selectAll()
                .where { (PhotoCategories.photoId eq photoId1) and (PhotoCategories.categoryId eq catId) }
                .firstOrNull()
        }
        assertNull(afterRow)
    }

    @Test
    fun `POST unlabel does not remove manual row`() = withApp {
        val cid     = "fcluster-unlabel-manual"
        val fid     = "face-unlabel-manual"
        val manualTagId = "tag-mut-manual"
        transaction {
            if (Tags.selectAll().where { Tags.id eq manualTagId }.count() == 0L)
                Tags.insert { it[id] = manualTagId; it[name] = "#manual" }
        }
        makeCluster(cid, Triple(fid, photoId1, 0.9))
        labelViaService(cid, tagId = manualTagId)

        // Overwrite the auto row with a manual one
        transaction {
            PhotoTags.update({
                (PhotoTags.photoId eq photoId1) and (PhotoTags.tagId eq manualTagId)
            }) { it[PhotoTags.assignmentSource] = "manual" }
        }

        val r = client.post("/v1/admin/face-clusters/$cid/unlabel") {
            header(HttpHeaders.Authorization, "Bearer ${adminToken()}")
        }
        assertEquals(HttpStatusCode.OK, r.status)

        // Manual row must survive
        val row = transaction {
            PhotoTags.selectAll()
                .where { (PhotoTags.photoId eq photoId1) and (PhotoTags.tagId eq manualTagId) }
                .firstOrNull()
        }
        assertNotNull(row)
        assertEquals("manual", row[PhotoTags.assignmentSource])
    }

    @Test
    fun `POST unlabel on already-unlabeled cluster is a no-op returning 200`() = withApp {
        val cid = "fcluster-unlabel-noop"
        val fid = "face-unlabel-noop"
        makeCluster(cid, Triple(fid, photoId1, 0.9))

        val r = client.post("/v1/admin/face-clusters/$cid/unlabel") {
            header(HttpHeaders.Authorization, "Bearer ${adminToken()}")
        }
        assertEquals(HttpStatusCode.OK, r.status)
    }

    @Test
    fun `POST unlabel with regular token returns 403`() = withApp {
        val r = client.post("/v1/admin/face-clusters/any/unlabel") {
            header(HttpHeaders.Authorization, "Bearer ${regularToken()}")
        }
        assertEquals(HttpStatusCode.Forbidden, r.status)
    }

    @Test
    fun `POST unlabel nonexistent cluster returns 404`() = withApp {
        val r = client.post("/v1/admin/face-clusters/fcluster-ghost/unlabel") {
            header(HttpHeaders.Authorization, "Bearer ${adminToken()}")
        }
        assertEquals(HttpStatusCode.NotFound, r.status)
    }

    // ── merge ─────────────────────────────────────────────────────────────────

    @Test
    fun `POST merge moves faces to target and deletes source clusters`() = withApp {
        val target  = "fcluster-merge-target"
        val source1 = "fcluster-merge-src1"
        val source2 = "fcluster-merge-src2"
        makeCluster(target, Triple("face-mt-1", photoId1, 0.7))
        makeCluster(source1, Triple("face-ms1-1", photoId1, 0.9), Triple("face-ms1-2", photoId2, 0.85))
        makeCluster(source2, Triple("face-ms2-1", photoId2, 0.8))

        val r = client.post("/v1/admin/face-clusters/$target/merge") {
            header(HttpHeaders.Authorization, "Bearer ${adminToken()}")
            header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            setBody("""{"sourceClusterIds":["$source1","$source2"]}""")
        }
        assertEquals(HttpStatusCode.OK, r.status)

        val body = lenient.parseToJsonElement(r.bodyAsText()).jsonObject
        assertEquals(4, body["faceCount"]!!.jsonPrimitive.int)

        transaction {
            // Source clusters gone
            assertNull(FaceClusters.selectAll().where { FaceClusters.id eq source1 }.firstOrNull())
            assertNull(FaceClusters.selectAll().where { FaceClusters.id eq source2 }.firstOrNull())
            // All 4 faces now belong to target
            val faceCount = Faces.selectAll().where { Faces.clusterId eq target }.count()
            assertEquals(4L, faceCount)
        }
    }

    @Test
    fun `POST merge preserves target label`() = withApp {
        val target = "fcluster-merge-labeled"
        val source = "fcluster-merge-unlabeled-src"
        makeCluster(target, Triple("face-mlt", photoId1, 0.9))
        makeCluster(source, Triple("face-mls", photoId2, 0.8))
        labelViaService(target, tagId = tagId)

        val r = client.post("/v1/admin/face-clusters/$target/merge") {
            header(HttpHeaders.Authorization, "Bearer ${adminToken()}")
            header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            setBody("""{"sourceClusterIds":["$source"]}""")
        }
        assertEquals(HttpStatusCode.OK, r.status)

        val body = lenient.parseToJsonElement(r.bodyAsText()).jsonObject
        assertEquals(tagId, body["tagId"]?.jsonPrimitive?.content)
    }

    @Test
    fun `POST merge returns 400 when target appears in sourceClusterIds`() = withApp {
        val cid = "fcluster-merge-self"
        makeCluster(cid, Triple("face-ms-self", photoId1, 0.9))

        val r = client.post("/v1/admin/face-clusters/$cid/merge") {
            header(HttpHeaders.Authorization, "Bearer ${adminToken()}")
            header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            setBody("""{"sourceClusterIds":["$cid"]}""")
        }
        assertEquals(HttpStatusCode.BadRequest, r.status)
    }

    @Test
    fun `POST merge with regular token returns 403`() = withApp {
        val r = client.post("/v1/admin/face-clusters/any/merge") {
            header(HttpHeaders.Authorization, "Bearer ${regularToken()}")
            header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            setBody("""{"sourceClusterIds":[]}""")
        }
        assertEquals(HttpStatusCode.Forbidden, r.status)
    }

    @Test
    fun `POST merge nonexistent target returns 404`() = withApp {
        val r = client.post("/v1/admin/face-clusters/fcluster-ghost/merge") {
            header(HttpHeaders.Authorization, "Bearer ${adminToken()}")
            header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            setBody("""{"sourceClusterIds":["fcluster-also-ghost"]}""")
        }
        assertEquals(HttpStatusCode.NotFound, r.status)
    }

    // ── remove-faces ──────────────────────────────────────────────────────────

    @Test
    fun `POST remove-faces sets cluster_id NULL and updates faceCount`() = withApp {
        val cid  = "fcluster-rm-faces"
        val fid1 = "face-rm-1"
        val fid2 = "face-rm-2"
        makeCluster(cid, Triple(fid1, photoId1, 0.95), Triple(fid2, photoId2, 0.7))

        val r = client.post("/v1/admin/face-clusters/$cid/remove-faces") {
            header(HttpHeaders.Authorization, "Bearer ${adminToken()}")
            header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            setBody("""{"faceIds":["$fid2"]}""")
        }
        assertEquals(HttpStatusCode.OK, r.status)

        val body = lenient.parseToJsonElement(r.bodyAsText()).jsonObject
        assertEquals(1, body["faceCount"]!!.jsonPrimitive.int)

        transaction {
            val removed = Faces.selectAll().where { Faces.id eq fid2 }.first()
            assertNull(removed[Faces.clusterId], "removed face should have cluster_id = NULL")
            val remaining = Faces.selectAll().where { Faces.id eq fid1 }.first()
            assertEquals(cid, remaining[Faces.clusterId])
        }
    }

    @Test
    fun `POST remove-faces updates representative to highest-score remaining face`() = withApp {
        val cid     = "fcluster-rm-rep"
        val highFid = "face-rm-rep-high"
        val lowFid  = "face-rm-rep-low"
        makeCluster(cid, Triple(highFid, photoId1, 0.99), Triple(lowFid, photoId2, 0.5))

        val r = client.post("/v1/admin/face-clusters/$cid/remove-faces") {
            header(HttpHeaders.Authorization, "Bearer ${adminToken()}")
            header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            setBody("""{"faceIds":["$lowFid"]}""")
        }
        assertEquals(HttpStatusCode.OK, r.status)

        val repId = transaction {
            FaceClusters.selectAll().where { FaceClusters.id eq cid }.first()[FaceClusters.representativeFaceId]
        }
        assertEquals(highFid, repId)
    }

    @Test
    fun `POST remove-faces with regular token returns 403`() = withApp {
        val r = client.post("/v1/admin/face-clusters/any/remove-faces") {
            header(HttpHeaders.Authorization, "Bearer ${regularToken()}")
            header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            setBody("""{"faceIds":[]}""")
        }
        assertEquals(HttpStatusCode.Forbidden, r.status)
    }

    @Test
    fun `POST remove-faces nonexistent cluster returns 404`() = withApp {
        val r = client.post("/v1/admin/face-clusters/fcluster-ghost/remove-faces") {
            header(HttpHeaders.Authorization, "Bearer ${adminToken()}")
            header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
            setBody("""{"faceIds":["face-x"]}""")
        }
        assertEquals(HttpStatusCode.NotFound, r.status)
    }
}

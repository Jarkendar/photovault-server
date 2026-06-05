package dev.jskrzypczak.photovault.server

import dev.jskrzypczak.photovault.server.auth.AuthService
import dev.jskrzypczak.photovault.server.auth.JwtConfig
import dev.jskrzypczak.photovault.server.auth.JwtService
import dev.jskrzypczak.photovault.server.db.initDatabase
import dev.jskrzypczak.photovault.server.db.tables.Categories
import dev.jskrzypczak.photovault.server.db.tables.PhotoCategories
import dev.jskrzypczak.photovault.server.db.tables.PhotoTags
import dev.jskrzypczak.photovault.server.db.tables.Photos
import dev.jskrzypczak.photovault.server.db.tables.Tags
import dev.jskrzypczak.photovault.server.photos.PhotoService
import dev.jskrzypczak.photovault.server.plugins.configureSecurity
import dev.jskrzypczak.photovault.server.plugins.configureSerialization
import dev.jskrzypczak.photovault.server.plugins.configureStatusPages
import dev.jskrzypczak.photovault.server.routes.authRoutes
import dev.jskrzypczak.photovault.server.routes.photoRoutes
import dev.jskrzypczak.photovault.server.storage.PhotoAssetStorage
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
import java.nio.file.Files
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Integration tests for the source-aware assignment model introduced in CATEGORIZER Phase 0.
 *
 * Verifies that:
 * - Unlinking a tag/category via PATCH writes a `denied` tombstone instead of deleting the row.
 * - Denied rows are invisible in GET /v1/photos/{id} responses.
 * - Re-linking a previously denied tag/category restores it as `manual`.
 * - An existing `auto` row that is not mentioned in a PATCH becomes `denied`.
 * - An unrelated PATCH (e.g. isFavorite only) leaves `auto` rows untouched.
 * - Denied assignments do not satisfy `tagIds`/`categoryIds` filter queries.
 * - `photoCount` on TagDto/CategoryDto excludes `denied` rows.
 */
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SourceAwareRelationsTest {

    companion object {
        @JvmStatic
        @Container
        val postgres: PostgreSQLContainer<*> = PostgreSQLContainer("postgres:16-alpine")
    }

    private lateinit var jwtConfig: JwtConfig
    private lateinit var authService: AuthService
    private lateinit var photoService: PhotoService

    private val lenient = Json { ignoreUnknownKeys = true }

    // IDs are scoped to this test class to avoid collisions with other test suites.
    private val tagAId    = "tag-sa-a"
    private val tagBId    = "tag-sa-b"
    private val catAId    = "cat-sa-a"
    private val userId    = "user-admin"

    // One photo per scenario to avoid inter-test interference.
    private val photoUnlinkTagId     = "photo-sa-unlink-tag"
    private val photoRelinkTagId     = "photo-sa-relink-tag"
    private val photoAutoOmitId      = "photo-sa-auto-omit"
    private val photoAutoUnrelatedId = "photo-sa-auto-unrel"
    private val photoFilterTagId     = "photo-sa-filter-tag"
    private val photoCountTagId      = "photo-sa-count-tag"
    private val photoUnlinkCatId     = "photo-sa-unlink-cat"
    /** Read-only fixture — tagA is `manual` and is never modified by any other test. */
    private val photoReadOnlyId      = "photo-sa-read-only"

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
            secret = "source-aware-test-secret",
            issuer = "photovault",
            audience = "photovault-client",
            accessTtlMinutes = 60,
            refreshTtlDays = 30,
        )
        val jwtService = JwtService(jwtConfig)
        authService = AuthService(jwtService)
        val storageRoot = Files.createTempDirectory("photovault-sa-test")
        photoService = PhotoService(PhotoAssetStorage(storageRoot))

        insertFixtures()
    }

    private fun insertFixtures() = transaction {
        fun ensureTag(id: String, name: String) {
            if (Tags.selectAll().where { Tags.id eq id }.count() == 0L)
                Tags.insert { it[Tags.id] = id; it[Tags.name] = name }
        }
        fun ensureCat(id: String, name: String) {
            if (Categories.selectAll().where { Categories.id eq id }.count() == 0L)
                Categories.insert { it[Categories.id] = id; it[Categories.name] = name; it[Categories.colorHex] = "#AABBCC" }
        }

        ensureTag(tagAId, "#sa-alpha")
        ensureTag(tagBId, "#sa-beta")
        ensureCat(catAId, "SaAlpha")

        val base = Instant.parse("2026-02-01T00:00:00Z")
        var offset = 1L
        fun ensurePhoto(pid: String) {
            if (Photos.selectAll().where { Photos.id eq pid }.count() == 0L) {
                Photos.insert {
                    it[id] = pid
                    it[name] = "sa_$pid.jpg"
                    it[sizeBytes] = 100_000L
                    it[mimeType] = "image/jpeg"
                    it[width] = 640; it[height] = 480
                    it[uploadedAt] = base.plusSeconds(offset++)
                    it[uploadedBy] = userId
                    it[isFavorite] = false
                    it[processingStatus] = "done"
                }
            }
        }

        ensurePhoto(photoUnlinkTagId)
        ensurePhoto(photoRelinkTagId)
        ensurePhoto(photoAutoOmitId)
        ensurePhoto(photoAutoUnrelatedId)
        ensurePhoto(photoFilterTagId)
        ensurePhoto(photoCountTagId)
        ensurePhoto(photoUnlinkCatId)
        ensurePhoto(photoReadOnlyId)

        fun ensureTagLink(pid: String, tid: String, source: String = "manual") {
            if (PhotoTags.selectAll()
                    .where { (PhotoTags.photoId eq pid) and (PhotoTags.tagId eq tid) }.count() == 0L
            ) {
                PhotoTags.insert {
                    it[photoId] = pid; it[tagId] = tid; it[assignmentSource] = source
                }
            }
        }
        fun ensureCatLink(pid: String, cid: String, source: String = "manual") {
            if (PhotoCategories.selectAll()
                    .where { (PhotoCategories.photoId eq pid) and (PhotoCategories.categoryId eq cid) }.count() == 0L
            ) {
                PhotoCategories.insert {
                    it[photoId] = pid; it[categoryId] = cid; it[assignmentSource] = source
                }
            }
        }

        // photoUnlinkTagId starts with tagA (manual)
        ensureTagLink(photoUnlinkTagId, tagAId)
        // photoRelinkTagId starts with tagA as denied (already tombstoned — simulates prior unlink)
        ensureTagLink(photoRelinkTagId, tagAId, "denied")
        // photoAutoOmitId has tagA as auto
        ensureTagLink(photoAutoOmitId, tagAId, "auto")
        // photoAutoUnrelatedId has tagA as auto — an unrelated PATCH must not change it
        ensureTagLink(photoAutoUnrelatedId, tagAId, "auto")
        // photoFilterTagId has tagA as denied — must not match filter queries
        ensureTagLink(photoFilterTagId, tagAId, "denied")
        // photoCountTagId has tagA as denied — must not contribute to photoCount
        ensureTagLink(photoCountTagId, tagAId, "denied")
        // photoUnlinkCatId starts with catA (manual)
        ensureCatLink(photoUnlinkCatId, catAId)
        // photoReadOnlyId has tagA as manual — never modified by any test; used for read-only assertions
        ensureTagLink(photoReadOnlyId, tagAId)
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

    // ── Unlink creates a denied tombstone ─────────────────────────────────────

    @Test
    fun `PATCH removing a tag writes denied tombstone and hides it from GET`() = withApp {
        val token = loginToken()

        // Remove tagA from the photo by sending an empty list
        val patch = client.patch("/v1/photos/$photoUnlinkTagId") {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody("""{"tagIds":[]}""")
        }
        assertEquals(HttpStatusCode.OK, patch.status)

        // GET must not return the tag
        val tags = lenient.parseToJsonElement(patch.bodyAsText())
            .jsonObject["tags"]!!.jsonArray
        assertTrue(tags.none { it.jsonObject["id"]?.jsonPrimitive?.content == tagAId },
            "denied tag must be absent from GET response")

        // DB row must still exist with source='denied'
        val dbSource = transaction {
            PhotoTags.selectAll()
                .where { (PhotoTags.photoId eq photoUnlinkTagId) and (PhotoTags.tagId eq tagAId) }
                .firstOrNull()
                ?.get(PhotoTags.assignmentSource)
        }
        assertEquals("denied", dbSource, "junction row must be a denied tombstone, not deleted")
    }

    @Test
    fun `PATCH removing a category writes denied tombstone and hides it from GET`() = withApp {
        val token = loginToken()

        val patch = client.patch("/v1/photos/$photoUnlinkCatId") {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody("""{"categoryIds":[]}""")
        }
        assertEquals(HttpStatusCode.OK, patch.status)

        val cats = lenient.parseToJsonElement(patch.bodyAsText())
            .jsonObject["categories"]!!.jsonArray
        assertTrue(cats.none { it.jsonObject["id"]?.jsonPrimitive?.content == catAId },
            "denied category must be absent from GET response")

        val dbSource = transaction {
            PhotoCategories.selectAll()
                .where { (PhotoCategories.photoId eq photoUnlinkCatId) and (PhotoCategories.categoryId eq catAId) }
                .firstOrNull()
                ?.get(PhotoCategories.assignmentSource)
        }
        assertEquals("denied", dbSource, "category junction row must be a denied tombstone, not deleted")
    }

    // ── Re-link overrides denied tombstone ────────────────────────────────────

    @Test
    fun `PATCH re-adding a denied tag makes it visible again with source=manual`() = withApp {
        val token = loginToken()

        // photoRelinkTagId was seeded with tagA as 'denied'
        // GET should not show it before the re-link
        val before = client.get("/v1/photos/$photoRelinkTagId") {
            header(HttpHeaders.Authorization, "Bearer $token")
        }
        val tagsBefore = lenient.parseToJsonElement(before.bodyAsText())
            .jsonObject["tags"]!!.jsonArray
        assertTrue(tagsBefore.none { it.jsonObject["id"]?.jsonPrimitive?.content == tagAId },
            "denied tag must not appear before re-link")

        // Re-link via PATCH
        val patch = client.patch("/v1/photos/$photoRelinkTagId") {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody("""{"tagIds":["$tagAId"]}""")
        }
        assertEquals(HttpStatusCode.OK, patch.status)

        // Tag must now appear in GET response
        val tagsAfter = lenient.parseToJsonElement(patch.bodyAsText())
            .jsonObject["tags"]!!.jsonArray
        assertTrue(tagsAfter.any { it.jsonObject["id"]?.jsonPrimitive?.content == tagAId },
            "re-linked tag must be visible again")

        // DB row must now have source='manual'
        val dbSource = transaction {
            PhotoTags.selectAll()
                .where { (PhotoTags.photoId eq photoRelinkTagId) and (PhotoTags.tagId eq tagAId) }
                .firstOrNull()
                ?.get(PhotoTags.assignmentSource)
        }
        assertEquals("manual", dbSource, "re-linked tag must have source=manual")
    }

    // ── Auto row becomes denied when omitted from PATCH ───────────────────────

    @Test
    fun `PATCH tagIds omitting an auto row turns it into denied`() = withApp {
        val token = loginToken()

        // photoAutoOmitId has tagA as 'auto'; send tagIds=[] to remove it
        val patch = client.patch("/v1/photos/$photoAutoOmitId") {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody("""{"tagIds":[]}""")
        }
        assertEquals(HttpStatusCode.OK, patch.status)

        // Tag must not appear in response
        val tags = lenient.parseToJsonElement(patch.bodyAsText())
            .jsonObject["tags"]!!.jsonArray
        assertTrue(tags.none { it.jsonObject["id"]?.jsonPrimitive?.content == tagAId })

        // DB row must still exist with source='denied'
        val dbSource = transaction {
            PhotoTags.selectAll()
                .where { (PhotoTags.photoId eq photoAutoOmitId) and (PhotoTags.tagId eq tagAId) }
                .firstOrNull()
                ?.get(PhotoTags.assignmentSource)
        }
        assertEquals("denied", dbSource, "auto row must become denied when removed by user")
    }

    // ── Unrelated PATCH leaves auto rows untouched ────────────────────────────

    @Test
    fun `PATCH without tagIds leaves existing auto rows unchanged`() = withApp {
        val token = loginToken()

        // Patch only isFavorite — no tagIds field
        val patch = client.patch("/v1/photos/$photoAutoUnrelatedId") {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody("""{"isFavorite":true}""")
        }
        assertEquals(HttpStatusCode.OK, patch.status)

        // DB row must still have source='auto'
        val dbSource = transaction {
            PhotoTags.selectAll()
                .where { (PhotoTags.photoId eq photoAutoUnrelatedId) and (PhotoTags.tagId eq tagAId) }
                .firstOrNull()
                ?.get(PhotoTags.assignmentSource)
        }
        assertEquals("auto", dbSource, "auto row must be untouched when tagIds is not sent")
    }

    // ── Denied assignments do not satisfy filter queries ─────────────────────

    @Test
    fun `GET photos filtered by denied tagId does not include the photo`() = withApp {
        val token = loginToken()

        // photoFilterTagId has tagA as denied — it must NOT be returned by tagId filter
        val r = client.get("/v1/photos?tagIds=$tagAId") {
            header(HttpHeaders.Authorization, "Bearer $token")
        }
        assertEquals(HttpStatusCode.OK, r.status)

        val items = lenient.parseToJsonElement(r.bodyAsText())
            .jsonObject["items"]!!.jsonArray
        assertTrue(
            items.none { it.jsonObject["id"]?.jsonPrimitive?.content == photoFilterTagId },
            "photo with only a denied tagA link must not appear in tagId filter results",
        )
    }

    // ── photoCount excludes denied rows ───────────────────────────────────────

    @Test
    fun `TagDto photoCount returned in GET photos does not include denied assignments`() = withApp {
        val token = loginToken()

        // photoReadOnlyId has tagA as manual (always visible). We use it to retrieve the tagA DTO
        // which embeds the global photoCount. photoCountTagId has tagA as denied and must not be
        // included in that count.
        val r = client.get("/v1/photos/$photoReadOnlyId") {
            header(HttpHeaders.Authorization, "Bearer $token")
        }
        assertEquals(HttpStatusCode.OK, r.status)

        val tags = lenient.parseToJsonElement(r.bodyAsText())
            .jsonObject["tags"]!!.jsonArray
        val tagADto = tags.firstOrNull { it.jsonObject["id"]?.jsonPrimitive?.content == tagAId }
        assertNotNull(tagADto, "tagA must be visible for photoReadOnlyId (manual link)")

        val reportedCount = tagADto.jsonObject["photoCount"]!!.jsonPrimitive.content.toLong()

        // Verify that the DB count of non-denied rows matches what the API reports.
        val dbCount = transaction {
            PhotoTags.selectAll()
                .where {
                    (PhotoTags.tagId eq tagAId) and (PhotoTags.assignmentSource neq "denied")
                }
                .count()
        }
        assertEquals(dbCount, reportedCount, "photoCount must equal non-denied DB count")
    }

    // ── New fields present in responses ───────────────────────────────────────

    @Test
    fun `TagDto in GET photos response includes autoEnabled and rolledOut fields`() = withApp {
        val token = loginToken()
        // photoReadOnlyId has tagA as manual — tagA is guaranteed visible regardless of test order.
        val r = client.get("/v1/photos/$photoReadOnlyId") {
            header(HttpHeaders.Authorization, "Bearer $token")
        }
        assertEquals(HttpStatusCode.OK, r.status)
        val tags = lenient.parseToJsonElement(r.bodyAsText())
            .jsonObject["tags"]!!.jsonArray
        val tagADto = tags.firstOrNull { it.jsonObject["id"]?.jsonPrimitive?.content == tagAId }
        assertNotNull(tagADto, "tagA must be visible for photoReadOnlyId (manual link)")
        assertNotNull(tagADto.jsonObject["autoEnabled"], "TagDto must include autoEnabled")
        assertNotNull(tagADto.jsonObject["rolledOut"], "TagDto must include rolledOut")
    }
}

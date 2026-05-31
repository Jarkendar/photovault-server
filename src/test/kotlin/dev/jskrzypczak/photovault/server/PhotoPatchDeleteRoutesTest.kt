package dev.jskrzypczak.photovault.server

import dev.jskrzypczak.photovault.server.auth.AuthService
import dev.jskrzypczak.photovault.server.auth.JwtConfig
import dev.jskrzypczak.photovault.server.auth.JwtService
import dev.jskrzypczak.photovault.server.db.initDatabase
import dev.jskrzypczak.photovault.server.db.tables.Categories
import dev.jskrzypczak.photovault.server.db.tables.Labels
import dev.jskrzypczak.photovault.server.db.tables.PhotoCategories
import dev.jskrzypczak.photovault.server.db.tables.PhotoLabels
import dev.jskrzypczak.photovault.server.db.tables.PhotoTags
import dev.jskrzypczak.photovault.server.db.tables.Photos
import dev.jskrzypczak.photovault.server.db.tables.Tags
import dev.jskrzypczak.photovault.server.db.tables.Users
import dev.jskrzypczak.photovault.server.photos.PhotoService
import dev.jskrzypczak.photovault.server.plugins.configureSecurity
import dev.jskrzypczak.photovault.server.plugins.configureSerialization
import dev.jskrzypczak.photovault.server.plugins.configureStatusPages
import dev.jskrzypczak.photovault.server.routes.authRoutes
import dev.jskrzypczak.photovault.server.routes.photoRoutes
import dev.jskrzypczak.photovault.server.storage.PhotoAssetStorage
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
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.select
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
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Integration tests for `PATCH /v1/photos/{id}` and `DELETE /v1/photos/{id}`.
 *
 * Uses a dedicated Testcontainers PostgreSQL instance with isolated fixtures so
 * mutations cannot interfere with the read-only tests in [PhotoRoutesTest].
 *
 * Fixture layout:
 *   - [photoToggleFavId]  — isFavorite=true, no relations → for isFavorite toggle test
 *   - [photoReplaceTagsId] — tags=[tag1,tag2] → for tag replace & clear tests
 *   - [photoOmitFieldId]  — isFavorite=false, tags=[tag1] → for "omit field" test
 *   - [photoReplaceCatId] — cat=[cat1] → for category replace test
 *   - [photoClearLabelsId]— labels=[labelRed] → for label clear test
 *   - [photoCombinedId]   — isFavorite=false, tags=[tag1], cat=[cat1], labels=[labelRed]
 *                           → for combined update test
 *   - [photoDeleteId]     — no relations → for DELETE 204 test
 *   - [photoCascadeId]    — tags=[tag1], cat=[cat1] → for DELETE cascade test
 */
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PhotoPatchDeleteRoutesTest {

    companion object {
        @JvmStatic
        @Container
        val postgres: PostgreSQLContainer<*> = PostgreSQLContainer("postgres:16-alpine")
    }

    private lateinit var jwtConfig: JwtConfig
    private lateinit var authService: AuthService
    private lateinit var photoService: PhotoService

    private val lenient = Json { ignoreUnknownKeys = true }

    // Shared tag / category IDs (seeded-labels use the standard "label-red" id)
    private val tag1Id = "tag-pd-1"
    private val tag2Id = "tag-pd-2"
    private val cat1Id = "cat-pd-1"
    private val labelRedId = "label-red"

    // Mutable fixture photos — one per mutation scenario to avoid inter-test interference
    private val photoToggleFavId   = "photo-pd-fav"
    private val photoReplaceTagsId = "photo-pd-tags"
    private val photoOmitFieldId   = "photo-pd-omit"
    private val photoReplaceCatId  = "photo-pd-cat"
    private val photoClearLabelsId = "photo-pd-labels"
    private val photoCombinedId    = "photo-pd-combined"
    private val photoDeleteId      = "photo-pd-delete"
    private val photoCascadeId     = "photo-pd-cascade"

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
            secret = "patch-delete-test-secret",
            issuer = "photovault",
            audience = "photovault-client",
            accessTtlMinutes = 60,
            refreshTtlDays = 30,
        )
        val jwtService = JwtService(jwtConfig)
        authService = AuthService(jwtService)
        val storageRoot = Files.createTempDirectory("photovault-pd-test")
        photoService = PhotoService(PhotoAssetStorage(storageRoot))

        insertFixtures()
    }

    private fun insertFixtures() = transaction {
        // Tags
        if (Tags.select(Tags.id).where { Tags.id eq tag1Id }.count() == 0L) {
            Tags.insert { it[id] = tag1Id; it[name] = "#pd-one" }
        }
        if (Tags.select(Tags.id).where { Tags.id eq tag2Id }.count() == 0L) {
            Tags.insert { it[id] = tag2Id; it[name] = "#pd-two" }
        }

        // Category
        if (Categories.select(Categories.id).where { Categories.id eq cat1Id }.count() == 0L) {
            Categories.insert { it[id] = cat1Id; it[name] = "PdCat"; it[colorHex] = "#123456" }
        }

        val uploader = "user-admin"
        val base = Instant.parse("2026-01-01T00:00:00Z")

        fun photo(pid: String, offset: Long, fav: Boolean) {
            if (Photos.select(Photos.id).where { Photos.id eq pid }.count() == 0L) {
                Photos.insert {
                    it[id] = pid
                    it[name] = "pd_$pid.jpg"
                    it[sizeBytes] = 500_000L
                    it[mimeType] = "image/jpeg"
                    it[width] = 800; it[height] = 600
                    it[uploadedAt] = base.plusSeconds(offset)
                    it[uploadedBy] = uploader
                    it[isFavorite] = fav
                    it[processingStatus] = "done"
                }
            }
        }

        photo(photoToggleFavId,   1L,  true)
        photo(photoReplaceTagsId, 2L,  false)
        photo(photoOmitFieldId,   3L,  false)
        photo(photoReplaceCatId,  4L,  false)
        photo(photoClearLabelsId, 5L,  false)
        photo(photoCombinedId,    6L,  false)
        photo(photoDeleteId,      7L,  false)
        photo(photoCascadeId,     8L,  false)

        fun safeTag(pid: String, tid: String) {
            if (PhotoTags.select(PhotoTags.photoId)
                    .where { (PhotoTags.photoId eq pid) and (PhotoTags.tagId eq tid) }
                    .count() == 0L
            ) PhotoTags.insert { it[photoId] = pid; it[tagId] = tid }
        }
        fun safeCat(pid: String, cid: String) {
            if (PhotoCategories.select(PhotoCategories.photoId)
                    .where { (PhotoCategories.photoId eq pid) and (PhotoCategories.categoryId eq cid) }
                    .count() == 0L
            ) PhotoCategories.insert { it[photoId] = pid; it[categoryId] = cid }
        }
        fun safeLabel(pid: String, lid: String) {
            if (PhotoLabels.select(PhotoLabels.photoId)
                    .where { (PhotoLabels.photoId eq pid) and (PhotoLabels.labelId eq lid) }
                    .count() == 0L
            ) PhotoLabels.insert { it[photoId] = pid; it[labelId] = lid }
        }

        safeTag(photoReplaceTagsId, tag1Id)
        safeTag(photoReplaceTagsId, tag2Id)
        safeTag(photoOmitFieldId,   tag1Id)
        safeTag(photoCombinedId,    tag1Id)
        safeCat(photoReplaceCatId,  cat1Id)
        safeCat(photoCombinedId,    cat1Id)
        safeLabel(photoClearLabelsId, labelRedId)
        safeLabel(photoCombinedId,    labelRedId)
        safeTag(photoCascadeId, tag1Id)
        safeCat(photoCascadeId, cat1Id)
    }

    // ── helpers ────────────────────────────────────────────────────────────────

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

    // ── PATCH — auth guard ─────────────────────────────────────────────────────

    @Test
    fun `PATCH photo without auth returns 401 unauthenticated`() = withApp {
        val r = client.patch("/v1/photos/$photoToggleFavId") {
            contentType(ContentType.Application.Json)
            setBody("""{"isFavorite":false}""")
        }
        assertEquals(HttpStatusCode.Unauthorized, r.status)
        assertTrue(r.headers[HttpHeaders.ContentType]?.startsWith("application/problem+json") == true)
    }

    // ── PATCH — isFavorite toggle ─────────────────────────────────────────────

    @Test
    fun `PATCH isFavorite flips value and returns 200 with updated PhotoDto`() = withApp {
        val token = loginToken()
        // photoToggleFavId starts as isFavorite=true; flip to false
        val r = client.patch("/v1/photos/$photoToggleFavId") {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody("""{"isFavorite":false}""")
        }
        assertEquals(HttpStatusCode.OK, r.status)
        val body = lenient.parseToJsonElement(r.bodyAsText()).jsonObject
        assertEquals(photoToggleFavId, body["id"]?.jsonPrimitive?.content)
        assertEquals(false, body["isFavorite"]?.jsonPrimitive?.content?.toBoolean())
    }

    // ── PATCH — tagIds set-semantics ──────────────────────────────────────────

    @Test
    fun `PATCH tagIds replaces the tag list (set semantics)`() = withApp {
        val token = loginToken()
        // photoReplaceTagsId starts with tag1+tag2; send only tag1 → tag2 removed
        val r = client.patch("/v1/photos/$photoReplaceTagsId") {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody("""{"tagIds":["$tag1Id"]}""")
        }
        assertEquals(HttpStatusCode.OK, r.status)
        val body = lenient.parseToJsonElement(r.bodyAsText()).jsonObject
        val tagIds = body["tags"]!!.jsonArray.map { it.jsonObject["id"]!!.jsonPrimitive.content }
        assertEquals(listOf(tag1Id), tagIds.sorted())
    }

    @Test
    fun `PATCH tagIds with empty list clears all tags`() = withApp {
        val token = loginToken()
        // photoOmitFieldId starts with tag1; send tagIds=[] → no tags
        val r = client.patch("/v1/photos/$photoOmitFieldId") {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody("""{"tagIds":[]}""")
        }
        assertEquals(HttpStatusCode.OK, r.status)
        val body = lenient.parseToJsonElement(r.bodyAsText()).jsonObject
        assertEquals(0, body["tags"]!!.jsonArray.size)
    }

    @Test
    fun `PATCH omitting tagIds leaves existing tags unchanged`() = withApp {
        val token = loginToken()
        // photoCombinedId has tag1; send only isFavorite — tags must stay
        val r = client.patch("/v1/photos/$photoCombinedId") {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody("""{"isFavorite":true}""")
        }
        assertEquals(HttpStatusCode.OK, r.status)
        val body = lenient.parseToJsonElement(r.bodyAsText()).jsonObject
        val tagIds = body["tags"]!!.jsonArray.map { it.jsonObject["id"]!!.jsonPrimitive.content }
        assertTrue(tag1Id in tagIds, "tag1 should still be present after omitting tagIds")
    }

    // ── PATCH — categoryIds ───────────────────────────────────────────────────

    @Test
    fun `PATCH categoryIds empty list clears all categories`() = withApp {
        val token = loginToken()
        // photoReplaceCatId starts with cat1; clear it
        val r = client.patch("/v1/photos/$photoReplaceCatId") {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody("""{"categoryIds":[]}""")
        }
        assertEquals(HttpStatusCode.OK, r.status)
        val body = lenient.parseToJsonElement(r.bodyAsText()).jsonObject
        assertEquals(0, body["categories"]!!.jsonArray.size)
    }

    // ── PATCH — labelIds ──────────────────────────────────────────────────────

    @Test
    fun `PATCH labelIds empty list clears all labels`() = withApp {
        val token = loginToken()
        // photoClearLabelsId starts with labelRed; clear it
        val r = client.patch("/v1/photos/$photoClearLabelsId") {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody("""{"labelIds":[]}""")
        }
        assertEquals(HttpStatusCode.OK, r.status)
        val body = lenient.parseToJsonElement(r.bodyAsText()).jsonObject
        assertEquals(0, body["labels"]!!.jsonArray.size)
    }

    // ── PATCH — validation errors ─────────────────────────────────────────────

    @Test
    fun `PATCH with unknown tagId returns 400 validation-failed with errors field`() = withApp {
        val token = loginToken()
        val r = client.patch("/v1/photos/$photoToggleFavId") {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody("""{"tagIds":["tag-does-not-exist"]}""")
        }
        assertEquals(HttpStatusCode.BadRequest, r.status)
        assertTrue(r.headers[HttpHeaders.ContentType]?.startsWith("application/problem+json") == true)
        val body = lenient.parseToJsonElement(r.bodyAsText()).jsonObject
        assertTrue(body["type"]?.jsonPrimitive?.content?.endsWith("validation-failed") == true)
        val errors = body["errors"]?.jsonObject
        assertNotNull(errors)
        assertNotNull(errors["tagIds"])
    }

    @Test
    fun `PATCH with unknown categoryId returns 400 validation-failed with errors field`() = withApp {
        val token = loginToken()
        val r = client.patch("/v1/photos/$photoToggleFavId") {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody("""{"categoryIds":["cat-does-not-exist"]}""")
        }
        assertEquals(HttpStatusCode.BadRequest, r.status)
        val body = lenient.parseToJsonElement(r.bodyAsText()).jsonObject
        assertTrue(body["type"]?.jsonPrimitive?.content?.endsWith("validation-failed") == true)
        val errors = body["errors"]?.jsonObject
        assertNotNull(errors)
        assertNotNull(errors["categoryIds"])
    }

    @Test
    fun `PATCH with unknown labelId returns 400 validation-failed with errors field`() = withApp {
        val token = loginToken()
        val r = client.patch("/v1/photos/$photoToggleFavId") {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody("""{"labelIds":["label-does-not-exist"]}""")
        }
        assertEquals(HttpStatusCode.BadRequest, r.status)
        val body = lenient.parseToJsonElement(r.bodyAsText()).jsonObject
        assertTrue(body["type"]?.jsonPrimitive?.content?.endsWith("validation-failed") == true)
        val errors = body["errors"]?.jsonObject
        assertNotNull(errors)
        assertNotNull(errors["labelIds"])
    }

    @Test
    fun `PATCH non-existent photo returns 404 photo-not-found`() = withApp {
        val token = loginToken()
        val r = client.patch("/v1/photos/photo-does-not-exist-xyz") {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody("""{"isFavorite":true}""")
        }
        assertEquals(HttpStatusCode.NotFound, r.status)
        assertTrue(r.headers[HttpHeaders.ContentType]?.startsWith("application/problem+json") == true)
        val body = lenient.parseToJsonElement(r.bodyAsText()).jsonObject
        assertTrue(body["type"]?.jsonPrimitive?.content?.endsWith("photo-not-found") == true)
    }

    // ── PATCH — response shape ────────────────────────────────────────────────

    @Test
    fun `PATCH returns full PhotoDto shape with all required fields`() = withApp {
        val token = loginToken()
        val r = client.patch("/v1/photos/$photoToggleFavId") {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody("""{"isFavorite":true}""")
        }
        assertEquals(HttpStatusCode.OK, r.status)
        val body = lenient.parseToJsonElement(r.bodyAsText()).jsonObject
        assertNotNull(body["id"])
        assertNotNull(body["name"])
        assertNotNull(body["sizeBytes"])
        assertNotNull(body["mimeType"])
        assertNotNull(body["uploadedAt"])
        assertNotNull(body["uploadedBy"])
        assertNotNull(body["isFavorite"])
        assertNotNull(body["processingStatus"])
        assertNotNull(body["thumbnailUrl"])
        assertNotNull(body["mediumUrl"])
        assertNotNull(body["originalUrl"])
        assertNotNull(body["tags"])
        assertNotNull(body["categories"])
        assertNotNull(body["labels"])
    }

    // ── DELETE — auth guard ───────────────────────────────────────────────────

    @Test
    fun `DELETE photo without auth returns 401 unauthenticated`() = withApp {
        val r = client.delete("/v1/photos/$photoDeleteId")
        assertEquals(HttpStatusCode.Unauthorized, r.status)
        assertTrue(r.headers[HttpHeaders.ContentType]?.startsWith("application/problem+json") == true)
    }

    // ── DELETE — happy path ───────────────────────────────────────────────────

    @Test
    fun `DELETE existing photo returns 204 and subsequent GET returns 404`() = withApp {
        val token = loginToken()

        // Delete
        val del = client.delete("/v1/photos/$photoDeleteId") {
            header(HttpHeaders.Authorization, "Bearer $token")
        }
        assertEquals(HttpStatusCode.NoContent, del.status)

        // Confirm gone
        val get = client.get("/v1/photos/$photoDeleteId") {
            header(HttpHeaders.Authorization, "Bearer $token")
        }
        assertEquals(HttpStatusCode.NotFound, get.status)
        val body = lenient.parseToJsonElement(get.bodyAsText()).jsonObject
        assertTrue(body["type"]?.jsonPrimitive?.content?.endsWith("photo-not-found") == true)
    }

    @Test
    fun `DELETE cascades to junction tables`() = withApp {
        val token = loginToken()

        // Verify photoCascadeId has relations before delete
        val tagCountBefore = transaction {
            PhotoTags.select(PhotoTags.photoId).where { PhotoTags.photoId eq photoCascadeId }.count()
        }
        assertTrue(tagCountBefore > 0, "Fixture should have at least one photo_tags row before delete")

        // Delete
        val del = client.delete("/v1/photos/$photoCascadeId") {
            header(HttpHeaders.Authorization, "Bearer $token")
        }
        assertEquals(HttpStatusCode.NoContent, del.status)

        // Junction rows must be gone (CASCADE FK handles this)
        val tagCountAfter = transaction {
            PhotoTags.select(PhotoTags.photoId).where { PhotoTags.photoId eq photoCascadeId }.count()
        }
        assertEquals(0L, tagCountAfter, "photo_tags rows should be deleted via CASCADE")

        val catCountAfter = transaction {
            PhotoCategories.select(PhotoCategories.photoId)
                .where { PhotoCategories.photoId eq photoCascadeId }.count()
        }
        assertEquals(0L, catCountAfter, "photo_categories rows should be deleted via CASCADE")
    }

    // ── DELETE — error cases ──────────────────────────────────────────────────

    @Test
    fun `DELETE non-existent photo returns 404 photo-not-found`() = withApp {
        val token = loginToken()
        val r = client.delete("/v1/photos/photo-xyz-does-not-exist") {
            header(HttpHeaders.Authorization, "Bearer $token")
        }
        assertEquals(HttpStatusCode.NotFound, r.status)
        assertTrue(r.headers[HttpHeaders.ContentType]?.startsWith("application/problem+json") == true)
        val body = lenient.parseToJsonElement(r.bodyAsText()).jsonObject
        assertTrue(body["type"]?.jsonPrimitive?.content?.endsWith("photo-not-found") == true)
    }
}

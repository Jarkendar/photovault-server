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
import dev.jskrzypczak.photovault.server.storage.PhotoAssetStorage
import dev.jskrzypczak.photovault.server.plugins.configureSerialization
import dev.jskrzypczak.photovault.server.plugins.configureStatusPages
import dev.jskrzypczak.photovault.server.routes.authRoutes
import dev.jskrzypczak.photovault.server.routes.healthRoutes
import dev.jskrzypczak.photovault.server.routes.photoRoutes
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
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.batchInsert
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
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PhotoRoutesTest {

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

    // Fixture IDs
    private val user1Id = "user-alice"
    private val user2Id = "user-bob"
    private val tag1Id = "tag-sea"
    private val tag2Id = "tag-sunset"
    private val cat1Id = "cat-nature"
    private val labelRedId = "label-red"

    // Three photos with distinct uploadedAt so pagination is deterministic
    private val photo1Id = "photo-001"  // oldest
    private val photo2Id = "photo-002"  // middle — isFavorite, has tag1+tag2+cat1+label
    private val photo3Id = "photo-003"  // newest — isFavorite, has tag1

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
            secret = "photo-route-test-secret",
            issuer = "photovault",
            audience = "photovault-client",
            accessTtlMinutes = 60,
            refreshTtlDays = 30,
        )
        jwtService = JwtService(jwtConfig)
        authService = AuthService(jwtService)
        val storageRoot = Files.createTempDirectory("photovault-test-storage")
        photoService = PhotoService(PhotoAssetStorage(storageRoot))

        insertFixtures()
    }

    private fun insertFixtures() = transaction {
        // Second user (admin was seeded by initDatabase)
        if (Users.select(Users.id).where { Users.id eq user1Id }.count() == 0L) {
            Users.insert {
                it[id] = user1Id
                it[username] = "alice"
                it[displayName] = "Alice"
                it[passwordHash] = "x" // irrelevant for these tests
                it[createdAt] = Instant.now()
            }
        }
        if (Users.select(Users.id).where { Users.id eq user2Id }.count() == 0L) {
            Users.insert {
                it[id] = user2Id
                it[username] = "bob"
                it[displayName] = "Bob"
                it[passwordHash] = "x"
                it[createdAt] = Instant.now()
            }
        }

        // Tags
        if (Tags.select(Tags.id).where { Tags.id eq tag1Id }.count() == 0L) {
            Tags.insert { it[id] = tag1Id; it[name] = "#sea" }
        }
        if (Tags.select(Tags.id).where { Tags.id eq tag2Id }.count() == 0L) {
            Tags.insert { it[id] = tag2Id; it[name] = "#sunset" }
        }

        // Category
        if (Categories.select(Categories.id).where { Categories.id eq cat1Id }.count() == 0L) {
            Categories.insert {
                it[id] = cat1Id; it[name] = "Nature"; it[colorHex] = "#43A047"
            }
        }

        // Photos
        val now = Instant.parse("2026-05-01T12:00:00Z")
        fun insertPhoto(pid: String, offsetSeconds: Long, favorite: Boolean, uploaderUid: String, withLocation: Boolean) {
            if (Photos.select(Photos.id).where { Photos.id eq pid }.count() == 0L) {
                Photos.insert {
                    it[id] = pid
                    it[name] = "photo_$pid.jpg"
                    it[sizeBytes] = 1_000_000L
                    it[mimeType] = "image/jpeg"
                    it[width] = 1920
                    it[height] = 1080
                    it[uploadedAt] = now.plusSeconds(offsetSeconds)
                    it[uploadedBy] = uploaderUid
                    it[isFavorite] = favorite
                    it[processingStatus] = "done"
                    if (withLocation) {
                        it[lat] = 54.4641
                        it[lng] = 18.5734
                        it[placeName] = "Sopot, PL"
                    }
                }
            }
        }
        insertPhoto(photo1Id, 0L, false, user2Id, false)
        insertPhoto(photo2Id, 60L, true, user1Id, true)
        insertPhoto(photo3Id, 120L, true, user1Id, false)

        // Assign tags
        fun safeInsertPhotoTag(pid: String, tid: String) {
            if (PhotoTags.select(PhotoTags.photoId)
                    .where { (PhotoTags.photoId eq pid).and(PhotoTags.tagId eq tid) }
                    .count() == 0L
            ) {
                PhotoTags.insert { it[photoId] = pid; it[tagId] = tid }
            }
        }
        safeInsertPhotoTag(photo2Id, tag1Id)
        safeInsertPhotoTag(photo2Id, tag2Id)
        safeInsertPhotoTag(photo3Id, tag1Id)

        // Assign category
        if (PhotoCategories.select(PhotoCategories.photoId)
                .where { (PhotoCategories.photoId eq photo2Id).and(PhotoCategories.categoryId eq cat1Id) }
                .count() == 0L
        ) {
            PhotoCategories.insert { it[photoId] = photo2Id; it[categoryId] = cat1Id }
        }

        // Assign label (red) to photo2
        if (PhotoLabels.select(PhotoLabels.photoId)
                .where { (PhotoLabels.photoId eq photo2Id).and(PhotoLabels.labelId eq labelRedId) }
                .count() == 0L
        ) {
            PhotoLabels.insert { it[photoId] = photo2Id; it[labelId] = labelRedId }
        }
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
                        healthRoutes("test")
                        authRoutes(authService)
                        photoRoutes(photoService)
                    }
                }
            }
            block()
        }

    private suspend fun io.ktor.server.testing.ApplicationTestBuilder.loginToken(
        username: String = "admin",
        password: String = "password123",
    ): String {
        val body = lenient.parseToJsonElement(
            client.post("/v1/auth/login") {
                contentType(ContentType.Application.Json)
                setBody("""{"username":"$username","password":"$password"}""")
            }.bodyAsText()
        ).jsonObject
        return body["accessToken"]!!.jsonPrimitive.content
    }

    // ── auth guard ─────────────────────────────────────────────────────────────

    @Test
    fun `GET photos without auth header returns 401 unauthenticated`() = withApp {
        val r = client.get("/v1/photos")
        assertEquals(HttpStatusCode.Unauthorized, r.status)
        val body = lenient.parseToJsonElement(r.bodyAsText()).jsonObject
        assertTrue(body["type"]?.jsonPrimitive?.content?.endsWith("unauthenticated") == true)
    }

    @Test
    fun `GET photo by id without auth header returns 401 unauthenticated`() = withApp {
        val r = client.get("/v1/photos/$photo1Id")
        assertEquals(HttpStatusCode.Unauthorized, r.status)
    }

    // ── GET /v1/photos — basic shape ──────────────────────────────────────────

    @Test
    fun `GET photos returns 200 with items array`() = withApp {
        val token = loginToken()
        val r = client.get("/v1/photos") { header(HttpHeaders.Authorization, "Bearer $token") }
        assertEquals(HttpStatusCode.OK, r.status)
        val body = lenient.parseToJsonElement(r.bodyAsText()).jsonObject
        assertNotNull(body["items"])
        assertNotNull(body["hasMore"])
    }

    @Test
    fun `GET photos returns newest first (uploadedAt DESC)`() = withApp {
        val token = loginToken()
        val r = client.get("/v1/photos") { header(HttpHeaders.Authorization, "Bearer $token") }
        val items = lenient.parseToJsonElement(r.bodyAsText()).jsonObject["items"]!!.jsonArray
        // At least three items; newest (photo3) must appear first
        assertTrue(items.size >= 3)
        assertEquals(photo3Id, items[0].jsonObject["id"]?.jsonPrimitive?.content)
    }

    @Test
    fun `GET photos items contain required PhotoDto fields`() = withApp {
        val token = loginToken()
        val r = client.get("/v1/photos") { header(HttpHeaders.Authorization, "Bearer $token") }
        val item = lenient.parseToJsonElement(r.bodyAsText()).jsonObject["items"]!!
            .jsonArray.first().jsonObject
        // Mandatory fields
        assertNotNull(item["id"])
        assertNotNull(item["name"])
        assertNotNull(item["sizeBytes"])
        assertNotNull(item["mimeType"])
        assertNotNull(item["width"])
        assertNotNull(item["height"])
        assertNotNull(item["uploadedAt"])
        assertNotNull(item["uploadedBy"])
        assertNotNull(item["isFavorite"])
        assertNotNull(item["processingStatus"])
        assertNotNull(item["thumbnailUrl"])
        assertNotNull(item["mediumUrl"])
        assertNotNull(item["originalUrl"])
        assertNotNull(item["tags"])
        assertNotNull(item["categories"])
        assertNotNull(item["labels"])
    }

    @Test
    fun `GET photos item with location contains location object`() = withApp {
        val token = loginToken()
        val r = client.get("/v1/photos") { header(HttpHeaders.Authorization, "Bearer $token") }
        val items = lenient.parseToJsonElement(r.bodyAsText()).jsonObject["items"]!!.jsonArray
        // photo2 has location; it's the second item (photo3 is first, photo2 is second)
        val photo2Item = items.firstOrNull {
            it.jsonObject["id"]?.jsonPrimitive?.content == photo2Id
        }?.jsonObject
        assertNotNull(photo2Item)
        val loc = photo2Item["location"]?.jsonObject
        assertNotNull(loc)
        assertNotNull(loc["latitude"])
        assertNotNull(loc["longitude"])
        assertEquals("Sopot, PL", loc["placeName"]?.jsonPrimitive?.content)
    }

    @Test
    fun `GET photos item uploadedBy contains id and displayName`() = withApp {
        val token = loginToken()
        val r = client.get("/v1/photos") { header(HttpHeaders.Authorization, "Bearer $token") }
        val items = lenient.parseToJsonElement(r.bodyAsText()).jsonObject["items"]!!.jsonArray
        val photo3Item = items.first { it.jsonObject["id"]?.jsonPrimitive?.content == photo3Id }.jsonObject
        val uploadedBy = photo3Item["uploadedBy"]?.jsonObject
        assertNotNull(uploadedBy)
        assertEquals(user1Id, uploadedBy["id"]?.jsonPrimitive?.content)
        assertEquals("Alice", uploadedBy["displayName"]?.jsonPrimitive?.content)
    }

    @Test
    fun `GET photos item tags include photoCount`() = withApp {
        val token = loginToken()
        val r = client.get("/v1/photos") { header(HttpHeaders.Authorization, "Bearer $token") }
        val items = lenient.parseToJsonElement(r.bodyAsText()).jsonObject["items"]!!.jsonArray
        val photo2Item = items.first { it.jsonObject["id"]?.jsonPrimitive?.content == photo2Id }.jsonObject
        val tags = photo2Item["tags"]!!.jsonArray
        assertEquals(2, tags.size)
        val seaTag = tags.first { it.jsonObject["id"]?.jsonPrimitive?.content == tag1Id }.jsonObject
        assertNotNull(seaTag["photoCount"])
    }

    @Test
    fun `GET photos item URL templates are correct`() = withApp {
        val token = loginToken()
        val r = client.get("/v1/photos") { header(HttpHeaders.Authorization, "Bearer $token") }
        val item = lenient.parseToJsonElement(r.bodyAsText()).jsonObject["items"]!!.jsonArray.first().jsonObject
        val id = item["id"]!!.jsonPrimitive.content
        assertEquals("/v1/photos/$id/thumbnail", item["thumbnailUrl"]?.jsonPrimitive?.content)
        assertEquals("/v1/photos/$id/medium", item["mediumUrl"]?.jsonPrimitive?.content)
        assertEquals("/v1/photos/$id/original", item["originalUrl"]?.jsonPrimitive?.content)
    }

    // ── pagination ─────────────────────────────────────────────────────────────

    @Test
    fun `GET photos with limit=1 returns hasMore=true and nextCursor`() = withApp {
        val token = loginToken()
        val r = client.get("/v1/photos?limit=1") { header(HttpHeaders.Authorization, "Bearer $token") }
        val body = lenient.parseToJsonElement(r.bodyAsText()).jsonObject
        val items = body["items"]!!.jsonArray
        assertEquals(1, items.size)
        assertEquals(true, body["hasMore"]?.jsonPrimitive?.content?.toBoolean())
        assertNotNull(body["nextCursor"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() })
    }

    @Test
    fun `GET photos cursor pagination returns next page without overlap`() = withApp {
        val token = loginToken()
        // Page 1
        val r1 = client.get("/v1/photos?limit=1") { header(HttpHeaders.Authorization, "Bearer $token") }
        val body1 = lenient.parseToJsonElement(r1.bodyAsText()).jsonObject
        val cursor = body1["nextCursor"]!!.jsonPrimitive.content
        val id1 = body1["items"]!!.jsonArray.first().jsonObject["id"]!!.jsonPrimitive.content

        // Page 2
        val r2 = client.get("/v1/photos?limit=1&cursor=$cursor") {
            header(HttpHeaders.Authorization, "Bearer $token")
        }
        val body2 = lenient.parseToJsonElement(r2.bodyAsText()).jsonObject
        val id2 = body2["items"]!!.jsonArray.first().jsonObject["id"]!!.jsonPrimitive.content

        // No overlap
        assertTrue(id1 != id2)
    }

    @Test
    fun `GET photos last page has hasMore=false and no nextCursor`() = withApp {
        val token = loginToken()
        // Fetch all items with large limit
        val r = client.get("/v1/photos?limit=100") { header(HttpHeaders.Authorization, "Bearer $token") }
        val body = lenient.parseToJsonElement(r.bodyAsText()).jsonObject
        assertFalse(body["hasMore"]?.jsonPrimitive?.content?.toBoolean() ?: true)
        val nc = body["nextCursor"]
        assertTrue(nc == null || nc.toString() == "null")
    }

    @Test
    fun `GET photos with malformed cursor returns 400 invalid-cursor`() = withApp {
        val token = loginToken()
        val r = client.get("/v1/photos?cursor=###notbase64###") {
            header(HttpHeaders.Authorization, "Bearer $token")
        }
        assertEquals(HttpStatusCode.BadRequest, r.status)
        val body = lenient.parseToJsonElement(r.bodyAsText()).jsonObject
        assertTrue(body["type"]?.jsonPrimitive?.content?.endsWith("invalid-cursor") == true)
        assertTrue(r.headers[HttpHeaders.ContentType]?.startsWith("application/problem+json") == true)
    }

    // ── filters ────────────────────────────────────────────────────────────────

    @Test
    fun `GET photos with favoritesOnly=true returns only favorites`() = withApp {
        val token = loginToken()
        val r = client.get("/v1/photos?favoritesOnly=true") {
            header(HttpHeaders.Authorization, "Bearer $token")
        }
        val items = lenient.parseToJsonElement(r.bodyAsText()).jsonObject["items"]!!.jsonArray
        assertTrue(items.isNotEmpty())
        items.forEach { item ->
            assertTrue(item.jsonObject["isFavorite"]?.jsonPrimitive?.content?.toBoolean() ?: false)
        }
        // Non-favorite photo1 must not appear
        assertFalse(items.any { it.jsonObject["id"]?.jsonPrimitive?.content == photo1Id })
    }

    @Test
    fun `GET photos with uploadedBy=me returns only current user photos`() = withApp {
        // Login as admin (seeded by initDatabase)
        val token = loginToken()
        val r = client.get("/v1/photos?uploadedBy=me") {
            header(HttpHeaders.Authorization, "Bearer $token")
        }
        val items = lenient.parseToJsonElement(r.bodyAsText()).jsonObject["items"]!!.jsonArray
        // admin uploaded nothing in fixtures, so items should be empty
        assertTrue(items.isEmpty())
    }

    @Test
    fun `GET photos with tagIds AND filter returns only photos with all listed tags`() = withApp {
        val token = loginToken()
        // Both tag1 and tag2 are only on photo2
        val r = client.get("/v1/photos?tagIds=$tag1Id,$tag2Id") {
            header(HttpHeaders.Authorization, "Bearer $token")
        }
        val items = lenient.parseToJsonElement(r.bodyAsText()).jsonObject["items"]!!.jsonArray
        assertEquals(1, items.size)
        assertEquals(photo2Id, items.first().jsonObject["id"]?.jsonPrimitive?.content)
    }

    @Test
    fun `GET photos with tagIds single filter returns all photos with that tag`() = withApp {
        val token = loginToken()
        // tag1 is on photo2 and photo3
        val r = client.get("/v1/photos?tagIds=$tag1Id") {
            header(HttpHeaders.Authorization, "Bearer $token")
        }
        val items = lenient.parseToJsonElement(r.bodyAsText()).jsonObject["items"]!!.jsonArray
        assertEquals(2, items.size)
        val ids = items.map { it.jsonObject["id"]?.jsonPrimitive?.content }.toSet()
        assertTrue(photo2Id in ids)
        assertTrue(photo3Id in ids)
    }

    @Test
    fun `GET photos with q matching photo name returns matching photos`() = withApp {
        val token = loginToken()
        // photo1 name contains "photo_photo-001"
        val r = client.get("/v1/photos?q=photo-001") {
            header(HttpHeaders.Authorization, "Bearer $token")
        }
        val items = lenient.parseToJsonElement(r.bodyAsText()).jsonObject["items"]!!.jsonArray
        assertTrue(items.isNotEmpty())
        assertTrue(items.any { it.jsonObject["id"]?.jsonPrimitive?.content == photo1Id })
    }

    @Test
    fun `GET photos with q matching tag name returns photos with that tag`() = withApp {
        val token = loginToken()
        // tag "#sunset" is only on photo2
        val r = client.get("/v1/photos?q=sunset") {
            header(HttpHeaders.Authorization, "Bearer $token")
        }
        val items = lenient.parseToJsonElement(r.bodyAsText()).jsonObject["items"]!!.jsonArray
        assertTrue(items.any { it.jsonObject["id"]?.jsonPrimitive?.content == photo2Id })
    }

    // ── GET /v1/photos/{id} ───────────────────────────────────────────────────

    @Test
    fun `GET photo by id returns 200 with correct PhotoDto`() = withApp {
        val token = loginToken()
        val r = client.get("/v1/photos/$photo2Id") {
            header(HttpHeaders.Authorization, "Bearer $token")
        }
        assertEquals(HttpStatusCode.OK, r.status)
        val body = lenient.parseToJsonElement(r.bodyAsText()).jsonObject
        assertEquals(photo2Id, body["id"]?.jsonPrimitive?.content)
        assertEquals(true, body["isFavorite"]?.jsonPrimitive?.content?.toBoolean())
        // Has location
        assertNotNull(body["location"])
        // Has tags
        assertEquals(2, body["tags"]!!.jsonArray.size)
    }

    @Test
    fun `GET photo by non-existent id returns 404 photo-not-found`() = withApp {
        val token = loginToken()
        val r = client.get("/v1/photos/photo-does-not-exist") {
            header(HttpHeaders.Authorization, "Bearer $token")
        }
        assertEquals(HttpStatusCode.NotFound, r.status)
        val body = lenient.parseToJsonElement(r.bodyAsText()).jsonObject
        assertTrue(body["type"]?.jsonPrimitive?.content?.endsWith("photo-not-found") == true)
        assertTrue(r.headers[HttpHeaders.ContentType]?.startsWith("application/problem+json") == true)
    }

    // ── regression ─────────────────────────────────────────────────────────────

    @Test
    fun `GET health is still public after adding photo routes`() = withApp {
        val r = client.get("/v1/health")
        assertEquals(HttpStatusCode.OK, r.status)
    }
}

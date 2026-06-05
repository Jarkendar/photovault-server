package dev.jskrzypczak.photovault.server

import dev.jskrzypczak.photovault.server.auth.AuthService
import dev.jskrzypczak.photovault.server.auth.JwtConfig
import dev.jskrzypczak.photovault.server.auth.JwtService
import dev.jskrzypczak.photovault.server.db.initDatabase
import dev.jskrzypczak.photovault.server.db.tables.PhotoTags
import dev.jskrzypczak.photovault.server.db.tables.Photos
import dev.jskrzypczak.photovault.server.db.tables.Tags
import dev.jskrzypczak.photovault.server.db.tables.Users
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
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
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
class TagRoutesTest {

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

    private val tagWithPhotoId = "tag-withphoto"
    private val tagWithoutPhotoId = "tag-nophoto"
    private val fixturePhotoId = "photo-tagtest-001"
    private val userId = "user-tagtest"

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
            secret = "tag-route-test-secret",
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

        insertFixtures()
    }

    private fun insertFixtures() = transaction {
        if (Users.selectAll().where { Users.id eq userId }.count() == 0L) {
            Users.insert {
                it[id] = userId
                it[username] = "tagtest"
                it[displayName] = "TagTest"
                it[passwordHash] = "x"
                it[createdAt] = Instant.now()
            }
        }
        if (Tags.selectAll().where { Tags.id eq tagWithPhotoId }.count() == 0L) {
            Tags.insert { it[id] = tagWithPhotoId; it[name] = "#withphoto" }
        }
        if (Tags.selectAll().where { Tags.id eq tagWithoutPhotoId }.count() == 0L) {
            Tags.insert { it[id] = tagWithoutPhotoId; it[name] = "#nophoto" }
        }
        if (Photos.selectAll().where { Photos.id eq fixturePhotoId }.count() == 0L) {
            Photos.insert {
                it[id] = fixturePhotoId
                it[name] = "tagtest.jpg"
                it[sizeBytes] = 1_000L
                it[mimeType] = "image/jpeg"
                it[width] = 100
                it[height] = 100
                it[uploadedAt] = Instant.now()
                it[uploadedBy] = userId
                it[isFavorite] = false
                it[processingStatus] = "done"
            }
        }
        if (PhotoTags.selectAll()
                .where { (PhotoTags.photoId eq fixturePhotoId) and (PhotoTags.tagId eq tagWithPhotoId) }
                .count() == 0L
        ) {
            PhotoTags.insert { it[PhotoTags.photoId] = fixturePhotoId; it[tagId] = tagWithPhotoId }
        }
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
    fun `GET tags without auth returns 401`() = withApp {
        val r = client.get("/v1/tags")
        assertEquals(HttpStatusCode.Unauthorized, r.status)
    }

    // ── GET /v1/tags ──────────────────────────────────────────────────────────

    @Test
    fun `GET tags returns 200 with items array and photoCount`() = withApp {
        val token = loginToken()
        val r = client.get("/v1/tags") { header(HttpHeaders.Authorization, "Bearer $token") }
        assertEquals(HttpStatusCode.OK, r.status)
        val body = lenient.parseToJsonElement(r.bodyAsText()).jsonObject
        assertNotNull(body["items"])
        val items = body["items"]!!.jsonArray
        assertTrue(items.isNotEmpty())
        val withPhoto = items.firstOrNull { it.jsonObject["id"]?.jsonPrimitive?.content == tagWithPhotoId }?.jsonObject
        assertNotNull(withPhoto)
        assertEquals(1L, withPhoto["photoCount"]?.jsonPrimitive?.content?.toLong())
        val noPhoto = items.firstOrNull { it.jsonObject["id"]?.jsonPrimitive?.content == tagWithoutPhotoId }?.jsonObject
        assertNotNull(noPhoto)
        assertEquals(0L, noPhoto["photoCount"]?.jsonPrimitive?.content?.toLong())
    }

    @Test
    fun `GET tags with usedOnly=true excludes zero-count tags`() = withApp {
        val token = loginToken()
        val r = client.get("/v1/tags?usedOnly=true") { header(HttpHeaders.Authorization, "Bearer $token") }
        assertEquals(HttpStatusCode.OK, r.status)
        val items = lenient.parseToJsonElement(r.bodyAsText()).jsonObject["items"]!!.jsonArray
        assertTrue(items.none { it.jsonObject["id"]?.jsonPrimitive?.content == tagWithoutPhotoId })
        assertTrue(items.any { it.jsonObject["id"]?.jsonPrimitive?.content == tagWithPhotoId })
    }

    // ── POST /v1/tags ─────────────────────────────────────────────────────────

    @Test
    fun `POST tags with valid name returns 201 with Location and body`() = withApp {
        val token = loginToken()
        val r = client.post("/v1/tags") {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody("""{"name":"#newtag"}""")
        }
        assertEquals(HttpStatusCode.Created, r.status)
        val location = r.headers[HttpHeaders.Location]
        assertNotNull(location)
        assertTrue(location!!.startsWith("/v1/tags/tag-"))
        val body = lenient.parseToJsonElement(r.bodyAsText()).jsonObject
        assertEquals("#newtag", body["name"]?.jsonPrimitive?.content)
        assertNotNull(body["id"])
    }

    @Test
    fun `POST tags with blank name returns 400 validation-failed with errors map`() = withApp {
        val token = loginToken()
        val r = client.post("/v1/tags") {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody("""{"name":""}""")
        }
        assertEquals(HttpStatusCode.BadRequest, r.status)
        val body = lenient.parseToJsonElement(r.bodyAsText()).jsonObject
        assertTrue(body["type"]?.jsonPrimitive?.content?.endsWith("validation-failed") == true)
        assertNotNull(body["errors"]?.jsonObject?.get("name"))
    }

    @Test
    fun `POST tags with name missing hash returns 400 validation-failed`() = withApp {
        val token = loginToken()
        val r = client.post("/v1/tags") {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody("""{"name":"notag"}""")
        }
        assertEquals(HttpStatusCode.BadRequest, r.status)
        val body = lenient.parseToJsonElement(r.bodyAsText()).jsonObject
        assertTrue(body["type"]?.jsonPrimitive?.content?.endsWith("validation-failed") == true)
        assertNotNull(body["errors"]?.jsonObject?.get("name"))
    }

    @Test
    fun `POST tags with duplicate name (case-insensitive) returns 409 duplicate-tag-name`() = withApp {
        val token = loginToken()
        // #withphoto already exists (inserted as fixture)
        val r = client.post("/v1/tags") {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody("""{"name":"#WITHPHOTO"}""")
        }
        assertEquals(HttpStatusCode.Conflict, r.status)
        val body = lenient.parseToJsonElement(r.bodyAsText()).jsonObject
        assertTrue(body["type"]?.jsonPrimitive?.content?.endsWith("duplicate-tag-name") == true)
    }

    // ── PATCH /v1/tags/{id} ───────────────────────────────────────────────────

    @Test
    fun `PATCH tag renames it and returns 200 with updated body`() = withApp {
        val token = loginToken()
        // First create a tag to rename
        val createR = client.post("/v1/tags") {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody("""{"name":"#torename"}""")
        }
        val id = lenient.parseToJsonElement(createR.bodyAsText()).jsonObject["id"]!!.jsonPrimitive.content

        val r = client.patch("/v1/tags/$id") {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody("""{"name":"#renamed"}""")
        }
        assertEquals(HttpStatusCode.OK, r.status)
        val body = lenient.parseToJsonElement(r.bodyAsText()).jsonObject
        assertEquals("#renamed", body["name"]?.jsonPrimitive?.content)
        assertEquals(id, body["id"]?.jsonPrimitive?.content)
    }

    @Test
    fun `PATCH non-existent tag returns 404 tag-not-found`() = withApp {
        val token = loginToken()
        val r = client.patch("/v1/tags/tag-doesnotexist") {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody("""{"name":"#x"}""")
        }
        assertEquals(HttpStatusCode.NotFound, r.status)
        val body = lenient.parseToJsonElement(r.bodyAsText()).jsonObject
        assertTrue(body["type"]?.jsonPrimitive?.content?.endsWith("tag-not-found") == true)
    }

    // ── DELETE /v1/tags/{id} ──────────────────────────────────────────────────

    @Test
    fun `DELETE tag returns 204 and photo_tags row is removed via cascade`() = withApp {
        val token = loginToken()
        // Create a tag, attach to a photo, then delete
        val createR = client.post("/v1/tags") {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody("""{"name":"#todelete"}""")
        }
        val id = lenient.parseToJsonElement(createR.bodyAsText()).jsonObject["id"]!!.jsonPrimitive.content

        // Insert a photo_tags row manually
        transaction {
            if (PhotoTags.selectAll()
                    .where { (PhotoTags.photoId eq fixturePhotoId) and (PhotoTags.tagId eq id) }
                    .count() == 0L
            ) {
                PhotoTags.insert { it[PhotoTags.photoId] = fixturePhotoId; it[tagId] = id }
            }
        }

        val r = client.delete("/v1/tags/$id") {
            header(HttpHeaders.Authorization, "Bearer $token")
        }
        assertEquals(HttpStatusCode.NoContent, r.status)

        // Tag row and photo_tags row must be gone
        transaction {
            assertEquals(0L, Tags.selectAll().where { Tags.id eq id }.count())
            assertEquals(
                0L,
                PhotoTags.selectAll().where { (PhotoTags.tagId eq id) }.count()
            )
        }
    }

    @Test
    fun `DELETE non-existent tag returns 404 tag-not-found`() = withApp {
        val token = loginToken()
        val r = client.delete("/v1/tags/tag-doesnotexist") {
            header(HttpHeaders.Authorization, "Bearer $token")
        }
        assertEquals(HttpStatusCode.NotFound, r.status)
        val body = lenient.parseToJsonElement(r.bodyAsText()).jsonObject
        assertTrue(body["type"]?.jsonPrimitive?.content?.endsWith("tag-not-found") == true)
    }

    // ── autoEnabled / rolledOut fields ────────────────────────────────────────

    @Test
    fun `GET tags response includes autoEnabled and rolledOut fields with correct defaults`() = withApp {
        val token = loginToken()
        val r = client.get("/v1/tags") { header(HttpHeaders.Authorization, "Bearer $token") }
        assertEquals(HttpStatusCode.OK, r.status)
        val item = lenient.parseToJsonElement(r.bodyAsText())
            .jsonObject["items"]!!.jsonArray
            .firstOrNull { it.jsonObject["id"]?.jsonPrimitive?.content == tagWithPhotoId }
            ?.jsonObject
        assertNotNull(item, "fixture tag must be present")
        assertNotNull(item!!["autoEnabled"], "TagDto must include autoEnabled")
        assertNotNull(item["rolledOut"], "TagDto must include rolledOut")
        // Default for new tags: autoEnabled=false, rolledOut=true
        assertEquals(false, item["autoEnabled"]!!.jsonPrimitive.content.toBoolean())
        assertEquals(true, item["rolledOut"]!!.jsonPrimitive.content.toBoolean())
    }

    @Test
    fun `PATCH tag with only autoEnabled=true succeeds without providing name`() = withApp {
        val token = loginToken()
        val createR = client.post("/v1/tags") {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody("""{"name":"#flagtest"}""")
        }
        val id = lenient.parseToJsonElement(createR.bodyAsText()).jsonObject["id"]!!.jsonPrimitive.content

        val r = client.patch("/v1/tags/$id") {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody("""{"autoEnabled":true}""")
        }
        assertEquals(HttpStatusCode.OK, r.status)
        val body = lenient.parseToJsonElement(r.bodyAsText()).jsonObject
        assertEquals(true, body["autoEnabled"]?.jsonPrimitive?.content?.toBoolean())
        // Name should be unchanged
        assertEquals("#flagtest", body["name"]?.jsonPrimitive?.content)
    }

    @Test
    fun `PATCH tag sets rolledOut to false`() = withApp {
        val token = loginToken()
        val createR = client.post("/v1/tags") {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody("""{"name":"#rollouttest"}""")
        }
        val id = lenient.parseToJsonElement(createR.bodyAsText()).jsonObject["id"]!!.jsonPrimitive.content

        val r = client.patch("/v1/tags/$id") {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody("""{"rolledOut":false}""")
        }
        assertEquals(HttpStatusCode.OK, r.status)
        val body = lenient.parseToJsonElement(r.bodyAsText()).jsonObject
        assertEquals(false, body["rolledOut"]?.jsonPrimitive?.content?.toBoolean())
    }
}

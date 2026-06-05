package dev.jskrzypczak.photovault.server

import dev.jskrzypczak.photovault.server.auth.AuthService
import dev.jskrzypczak.photovault.server.auth.JwtConfig
import dev.jskrzypczak.photovault.server.auth.JwtService
import dev.jskrzypczak.photovault.server.db.initDatabase
import dev.jskrzypczak.photovault.server.db.tables.Categories
import dev.jskrzypczak.photovault.server.db.tables.PhotoCategories
import dev.jskrzypczak.photovault.server.db.tables.Photos
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
class CategoryRoutesTest {

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

    private val catWithPhotoId = "cat-withphoto"
    private val catWithoutPhotoId = "cat-nophoto"
    private val fixturePhotoId = "photo-cattest-001"
    private val userId = "user-cattest"

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
            secret = "cat-route-test-secret",
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
                it[username] = "cattest"
                it[displayName] = "CatTest"
                it[passwordHash] = "x"
                it[createdAt] = Instant.now()
            }
        }
        if (Categories.selectAll().where { Categories.id eq catWithPhotoId }.count() == 0L) {
            Categories.insert {
                it[id] = catWithPhotoId; it[name] = "WithPhoto"; it[colorHex] = "#FF0000"
            }
        }
        if (Categories.selectAll().where { Categories.id eq catWithoutPhotoId }.count() == 0L) {
            Categories.insert {
                it[id] = catWithoutPhotoId; it[name] = "NoPhoto"; it[colorHex] = "#00FF00"
            }
        }
        if (Photos.selectAll().where { Photos.id eq fixturePhotoId }.count() == 0L) {
            Photos.insert {
                it[id] = fixturePhotoId
                it[name] = "cattest.jpg"
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
        if (PhotoCategories.selectAll()
                .where { (PhotoCategories.photoId eq fixturePhotoId) and (PhotoCategories.categoryId eq catWithPhotoId) }
                .count() == 0L
        ) {
            PhotoCategories.insert {
                it[PhotoCategories.photoId] = fixturePhotoId; it[categoryId] = catWithPhotoId
            }
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
    fun `GET categories without auth returns 401`() = withApp {
        val r = client.get("/v1/categories")
        assertEquals(HttpStatusCode.Unauthorized, r.status)
    }

    // ── GET /v1/categories ────────────────────────────────────────────────────

    @Test
    fun `GET categories returns 200 with items array and photoCount`() = withApp {
        val token = loginToken()
        val r = client.get("/v1/categories") { header(HttpHeaders.Authorization, "Bearer $token") }
        assertEquals(HttpStatusCode.OK, r.status)
        val items = lenient.parseToJsonElement(r.bodyAsText()).jsonObject["items"]!!.jsonArray
        assertTrue(items.isNotEmpty())
        val withPhoto = items.firstOrNull { it.jsonObject["id"]?.jsonPrimitive?.content == catWithPhotoId }?.jsonObject
        assertNotNull(withPhoto)
        assertEquals(1L, withPhoto["photoCount"]?.jsonPrimitive?.content?.toLong())
        val noPhoto = items.firstOrNull { it.jsonObject["id"]?.jsonPrimitive?.content == catWithoutPhotoId }?.jsonObject
        assertNotNull(noPhoto)
        assertEquals(0L, noPhoto["photoCount"]?.jsonPrimitive?.content?.toLong())
    }

    @Test
    fun `GET categories with usedOnly=true excludes zero-count categories`() = withApp {
        val token = loginToken()
        val r = client.get("/v1/categories?usedOnly=true") { header(HttpHeaders.Authorization, "Bearer $token") }
        val items = lenient.parseToJsonElement(r.bodyAsText()).jsonObject["items"]!!.jsonArray
        assertTrue(items.none { it.jsonObject["id"]?.jsonPrimitive?.content == catWithoutPhotoId })
        assertTrue(items.any { it.jsonObject["id"]?.jsonPrimitive?.content == catWithPhotoId })
    }

    // ── POST /v1/categories ───────────────────────────────────────────────────

    @Test
    fun `POST categories with valid body returns 201 with Location and body`() = withApp {
        val token = loginToken()
        val r = client.post("/v1/categories") {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody("""{"name":"Newcat","colorHex":"#1A2B3C"}""")
        }
        assertEquals(HttpStatusCode.Created, r.status)
        val location = r.headers[HttpHeaders.Location]
        assertNotNull(location)
        assertTrue(location!!.startsWith("/v1/categories/cat-"))
        val body = lenient.parseToJsonElement(r.bodyAsText()).jsonObject
        assertEquals("Newcat", body["name"]?.jsonPrimitive?.content)
        assertEquals("#1A2B3C", body["colorHex"]?.jsonPrimitive?.content)
    }

    @Test
    fun `POST categories with blank name returns 400 validation-failed with errors map`() = withApp {
        val token = loginToken()
        val r = client.post("/v1/categories") {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody("""{"name":"","colorHex":"#FF0000"}""")
        }
        assertEquals(HttpStatusCode.BadRequest, r.status)
        val body = lenient.parseToJsonElement(r.bodyAsText()).jsonObject
        assertTrue(body["type"]?.jsonPrimitive?.content?.endsWith("validation-failed") == true)
        assertNotNull(body["errors"]?.jsonObject?.get("name"))
    }

    @Test
    fun `POST categories with invalid colorHex returns 400 validation-failed`() = withApp {
        val token = loginToken()
        val r = client.post("/v1/categories") {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody("""{"name":"Good","colorHex":"not-a-color"}""")
        }
        assertEquals(HttpStatusCode.BadRequest, r.status)
        val body = lenient.parseToJsonElement(r.bodyAsText()).jsonObject
        assertTrue(body["type"]?.jsonPrimitive?.content?.endsWith("validation-failed") == true)
        assertNotNull(body["errors"]?.jsonObject?.get("colorHex"))
    }

    @Test
    fun `POST categories with duplicate name returns 409 duplicate-category-name`() = withApp {
        val token = loginToken()
        // "WithPhoto" already exists as a fixture
        val r = client.post("/v1/categories") {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody("""{"name":"WithPhoto","colorHex":"#123456"}""")
        }
        assertEquals(HttpStatusCode.Conflict, r.status)
        val body = lenient.parseToJsonElement(r.bodyAsText()).jsonObject
        assertTrue(body["type"]?.jsonPrimitive?.content?.endsWith("duplicate-category-name") == true)
    }

    // ── PATCH /v1/categories/{id} ─────────────────────────────────────────────

    @Test
    fun `PATCH category with name only updates name and returns 200`() = withApp {
        val token = loginToken()
        val createR = client.post("/v1/categories") {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody("""{"name":"Patchme","colorHex":"#AABBCC"}""")
        }
        val id = lenient.parseToJsonElement(createR.bodyAsText()).jsonObject["id"]!!.jsonPrimitive.content

        val r = client.patch("/v1/categories/$id") {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody("""{"name":"Patched"}""")
        }
        assertEquals(HttpStatusCode.OK, r.status)
        val body = lenient.parseToJsonElement(r.bodyAsText()).jsonObject
        assertEquals("Patched", body["name"]?.jsonPrimitive?.content)
        assertEquals("#AABBCC", body["colorHex"]?.jsonPrimitive?.content)
    }

    @Test
    fun `PATCH category with colorHex only updates color and returns 200`() = withApp {
        val token = loginToken()
        val createR = client.post("/v1/categories") {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody("""{"name":"Colorchange","colorHex":"#111111"}""")
        }
        val id = lenient.parseToJsonElement(createR.bodyAsText()).jsonObject["id"]!!.jsonPrimitive.content

        val r = client.patch("/v1/categories/$id") {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody("""{"colorHex":"#222222"}""")
        }
        assertEquals(HttpStatusCode.OK, r.status)
        val body = lenient.parseToJsonElement(r.bodyAsText()).jsonObject
        assertEquals("Colorchange", body["name"]?.jsonPrimitive?.content)
        assertEquals("#222222", body["colorHex"]?.jsonPrimitive?.content)
    }

    @Test
    fun `PATCH non-existent category returns 404 category-not-found`() = withApp {
        val token = loginToken()
        val r = client.patch("/v1/categories/cat-doesnotexist") {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody("""{"name":"X"}""")
        }
        assertEquals(HttpStatusCode.NotFound, r.status)
        val body = lenient.parseToJsonElement(r.bodyAsText()).jsonObject
        assertTrue(body["type"]?.jsonPrimitive?.content?.endsWith("category-not-found") == true)
    }

    // ── DELETE /v1/categories/{id} ────────────────────────────────────────────

    @Test
    fun `DELETE category returns 204 and photo_categories row is removed via cascade`() = withApp {
        val token = loginToken()
        val createR = client.post("/v1/categories") {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody("""{"name":"Deleteme","colorHex":"#FFFFFF"}""")
        }
        val id = lenient.parseToJsonElement(createR.bodyAsText()).jsonObject["id"]!!.jsonPrimitive.content

        transaction {
            if (PhotoCategories.selectAll()
                    .where { (PhotoCategories.photoId eq fixturePhotoId) and (PhotoCategories.categoryId eq id) }
                    .count() == 0L
            ) {
                PhotoCategories.insert { it[PhotoCategories.photoId] = fixturePhotoId; it[categoryId] = id }
            }
        }

        val r = client.delete("/v1/categories/$id") {
            header(HttpHeaders.Authorization, "Bearer $token")
        }
        assertEquals(HttpStatusCode.NoContent, r.status)

        transaction {
            assertEquals(0L, Categories.selectAll().where { Categories.id eq id }.count())
            assertEquals(0L, PhotoCategories.selectAll().where { PhotoCategories.categoryId eq id }.count())
        }
    }

    @Test
    fun `DELETE non-existent category returns 404 category-not-found`() = withApp {
        val token = loginToken()
        val r = client.delete("/v1/categories/cat-doesnotexist") {
            header(HttpHeaders.Authorization, "Bearer $token")
        }
        assertEquals(HttpStatusCode.NotFound, r.status)
        val body = lenient.parseToJsonElement(r.bodyAsText()).jsonObject
        assertTrue(body["type"]?.jsonPrimitive?.content?.endsWith("category-not-found") == true)
    }

    // ── autoEnabled / rolledOut fields ────────────────────────────────────────

    @Test
    fun `GET categories response includes autoEnabled and rolledOut fields with correct defaults`() = withApp {
        val token = loginToken()
        val r = client.get("/v1/categories") { header(HttpHeaders.Authorization, "Bearer $token") }
        assertEquals(HttpStatusCode.OK, r.status)
        val item = lenient.parseToJsonElement(r.bodyAsText())
            .jsonObject["items"]!!.jsonArray
            .firstOrNull { it.jsonObject["id"]?.jsonPrimitive?.content == catWithPhotoId }
            ?.jsonObject
        assertNotNull(item, "fixture category must be present")
        assertNotNull(item!!["autoEnabled"], "CategoryDto must include autoEnabled")
        assertNotNull(item["rolledOut"], "CategoryDto must include rolledOut")
        // Default for new categories: autoEnabled=false, rolledOut=true
        assertEquals(false, item["autoEnabled"]!!.jsonPrimitive.content.toBoolean())
        assertEquals(true, item["rolledOut"]!!.jsonPrimitive.content.toBoolean())
    }

    @Test
    fun `PATCH category with only autoEnabled=true succeeds without changing name or color`() = withApp {
        val token = loginToken()
        val createR = client.post("/v1/categories") {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody("""{"name":"FlagTestCat","colorHex":"#1A1A1A"}""")
        }
        val id = lenient.parseToJsonElement(createR.bodyAsText()).jsonObject["id"]!!.jsonPrimitive.content

        val r = client.patch("/v1/categories/$id") {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody("""{"autoEnabled":true}""")
        }
        assertEquals(HttpStatusCode.OK, r.status)
        val body = lenient.parseToJsonElement(r.bodyAsText()).jsonObject
        assertEquals(true, body["autoEnabled"]?.jsonPrimitive?.content?.toBoolean())
        // Name and color must be unchanged
        assertEquals("FlagTestCat", body["name"]?.jsonPrimitive?.content)
        assertEquals("#1A1A1A", body["colorHex"]?.jsonPrimitive?.content)
    }

    @Test
    fun `PATCH category sets rolledOut to false`() = withApp {
        val token = loginToken()
        val createR = client.post("/v1/categories") {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody("""{"name":"RolloutTestCat","colorHex":"#2B2B2B"}""")
        }
        val id = lenient.parseToJsonElement(createR.bodyAsText()).jsonObject["id"]!!.jsonPrimitive.content

        val r = client.patch("/v1/categories/$id") {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody("""{"rolledOut":false}""")
        }
        assertEquals(HttpStatusCode.OK, r.status)
        val body = lenient.parseToJsonElement(r.bodyAsText()).jsonObject
        assertEquals(false, body["rolledOut"]?.jsonPrimitive?.content?.toBoolean())
    }
}

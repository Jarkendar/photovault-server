package dev.jskrzypczak.photovault.server

import dev.jskrzypczak.photovault.server.auth.AuthService
import dev.jskrzypczak.photovault.server.auth.JwtConfig
import dev.jskrzypczak.photovault.server.auth.JwtService
import dev.jskrzypczak.photovault.server.db.initDatabase
import dev.jskrzypczak.photovault.server.db.tables.Categories
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
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PhotoCountRoutesTest {

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

    // Fixture IDs — prefixed with "cnt-" to avoid collisions with other test classes
    private val user1Id    = "cnt-user-alice"
    private val tag1Id     = "cnt-tag-sea"
    private val tag2Id     = "cnt-tag-sunset"
    private val cat1Id     = "cnt-cat-nature"
    private val labelRedId = "label-red"    // seeded by initDatabase

    // Four photos with distinct uploadedAt values
    private val photoAId = "cnt-photo-a"  // oldest, no tags/cats/labels, uploaded by admin
    private val photoBId = "cnt-photo-b"  // tag1 + cat1 + label-red, isFavorite, uploaded by alice
    private val photoCId = "cnt-photo-c"  // tag1 + tag2, uploaded by alice
    // photoD has capturedAt set to an earlier date to test COALESCE filtering
    private val photoDId = "cnt-photo-d"  // capturedAt = 2025-01-01, uploaded by alice

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
            secret = "photo-count-test-secret",
            issuer = "photovault",
            audience = "photovault-client",
            accessTtlMinutes = 60,
            refreshTtlDays = 30,
        )
        jwtService = JwtService(jwtConfig)
        authService = AuthService(jwtService)
        val storageRoot = Files.createTempDirectory("photovault-count-test-storage")
        photoService = PhotoService(PhotoAssetStorage(storageRoot))

        insertFixtures()
    }

    private fun insertFixtures() = transaction {
        if (Users.selectAll().where { Users.id eq user1Id }.count() == 0L) {
            Users.insert {
                it[id] = user1Id
                it[username] = "cnt-alice"
                it[displayName] = "Count Alice"
                it[passwordHash] = "x"
                it[createdAt] = Instant.now()
            }
        }

        if (Tags.selectAll().where { Tags.id eq tag1Id }.count() == 0L) {
            Tags.insert { it[id] = tag1Id; it[name] = "#sea-cnt" }
        }
        if (Tags.selectAll().where { Tags.id eq tag2Id }.count() == 0L) {
            Tags.insert { it[id] = tag2Id; it[name] = "#sunset-cnt" }
        }
        if (Categories.selectAll().where { Categories.id eq cat1Id }.count() == 0L) {
            Categories.insert { it[id] = cat1Id; it[name] = "Nature-cnt"; it[colorHex] = "#43A047" }
        }

        val base = Instant.parse("2026-06-01T10:00:00Z")
        fun insertPhoto(pid: String, offsetSec: Long, fav: Boolean, uid: String, capturedAt: Instant? = null) {
            if (Photos.selectAll().where { Photos.id eq pid }.count() == 0L) {
                Photos.insert {
                    it[id] = pid
                    it[name] = "cnt_$pid.jpg"
                    it[sizeBytes] = 500_000L
                    it[mimeType] = "image/jpeg"
                    it[width] = 1280
                    it[height] = 720
                    it[uploadedAt] = base.plusSeconds(offsetSec)
                    it[uploadedBy] = uid
                    it[isFavorite] = fav
                    it[processingStatus] = "done"
                    if (capturedAt != null) it[Photos.capturedAt] = capturedAt
                }
            }
        }
        // "user-admin" is seeded by initDatabase
        insertPhoto(photoAId, 0L,   false, "user-admin")
        insertPhoto(photoBId, 60L,  true,  user1Id)
        insertPhoto(photoCId, 120L, false, user1Id)
        insertPhoto(photoDId, 180L, false, user1Id, capturedAt = Instant.parse("2025-01-01T08:00:00Z"))

        fun safePT(pid: String, tid: String) {
            if (PhotoTags.selectAll()
                    .where { (PhotoTags.photoId eq pid).and(PhotoTags.tagId eq tid) }
                    .count() == 0L
            ) PhotoTags.insert { it[photoId] = pid; it[tagId] = tid }
        }
        safePT(photoBId, tag1Id)
        safePT(photoCId, tag1Id)
        safePT(photoCId, tag2Id)

        if (PhotoCategories.selectAll()
                .where { (PhotoCategories.photoId eq photoBId).and(PhotoCategories.categoryId eq cat1Id) }
                .count() == 0L
        ) PhotoCategories.insert { it[photoId] = photoBId; it[categoryId] = cat1Id }

        if (PhotoLabels.selectAll()
                .where { (PhotoLabels.photoId eq photoBId).and(PhotoLabels.labelId eq labelRedId) }
                .count() == 0L
        ) PhotoLabels.insert { it[photoId] = photoBId; it[labelId] = labelRedId }
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

    /** Calls GET /v1/photos/count?[params] and returns the count value. Asserts 200 OK. */
    private suspend fun io.ktor.server.testing.ApplicationTestBuilder.getCount(
        token: String,
        params: String = "",
    ): Long {
        val r = client.get("/v1/photos/count$params") {
            header(HttpHeaders.Authorization, "Bearer $token")
        }
        assertEquals(HttpStatusCode.OK, r.status)
        return lenient.parseToJsonElement(r.bodyAsText())
            .jsonObject["count"]!!.jsonPrimitive.content.toLong()
    }

    // ── auth guard ────────────────────────────────────────────────────────────

    @Test
    fun `GET photos-count without auth returns 401 unauthenticated`() = withApp {
        val r = client.get("/v1/photos/count")
        assertEquals(HttpStatusCode.Unauthorized, r.status)
        val body = lenient.parseToJsonElement(r.bodyAsText()).jsonObject
        assertTrue(body["type"]?.jsonPrimitive?.content?.endsWith("unauthenticated") == true)
    }

    // ── basic shape ───────────────────────────────────────────────────────────

    @Test
    fun `GET photos-count returns 200 with count field`() = withApp {
        val token = loginToken()
        val r = client.get("/v1/photos/count") {
            header(HttpHeaders.Authorization, "Bearer $token")
        }
        assertEquals(HttpStatusCode.OK, r.status)
        assertTrue(lenient.parseToJsonElement(r.bodyAsText()).jsonObject.containsKey("count"))
    }

    @Test
    fun `GET photos-count unfiltered matches the list item count`() = withApp {
        val token = loginToken()
        // Fetch everything via list (with a large limit) and compare totals
        val listItems = lenient.parseToJsonElement(
            client.get("/v1/photos?limit=100") {
                header(HttpHeaders.Authorization, "Bearer $token")
            }.bodyAsText()
        ).jsonObject["items"]!!.jsonArray.size.toLong()

        val cnt = getCount(token)
        // The count endpoint must agree with the list (same predicate, no cursor)
        assertEquals(listItems, cnt)
    }

    @Test
    fun `GET photos-count returns 0 when no photos match`() = withApp {
        val token = loginToken()
        val cnt = getCount(token, "?dateFrom=2099-01-01T00:00:00Z")
        assertEquals(0L, cnt)
    }

    // ── per-filter param tests ────────────────────────────────────────────────

    @Test
    fun `GET photos-count with favoritesOnly=true counts only favorites`() = withApp {
        val token = loginToken()
        val favCnt = getCount(token, "?favoritesOnly=true")
        val allCnt = getCount(token)
        assertTrue(favCnt >= 1L)
        assertTrue(favCnt < allCnt)
    }

    @Test
    fun `GET photos-count with uploadedBy filter returns fewer than total`() = withApp {
        val token = loginToken()
        // admin uploaded only photoA; alice uploaded photoB/C/D — either way count < total
        val meCnt = getCount(token, "?uploadedBy=me")
        val allCnt = getCount(token)
        assertTrue(meCnt < allCnt)
    }

    @Test
    fun `GET photos-count with tagIds AND (default matchMode) counts photos with all listed tags`() = withApp {
        val token = loginToken()
        // tag1 on photoB+photoC, tag2 on photoC only — AND → only photoC = 1
        val cnt = getCount(token, "?tagIds=$tag1Id,$tag2Id")
        assertEquals(1L, cnt)
    }

    @Test
    fun `GET photos-count with tagIds matchMode=any counts photos with at least one tag`() = withApp {
        val token = loginToken()
        // tag1 on photoB+photoC, tag2 on photoC — OR → photoB+photoC = 2
        val cnt = getCount(token, "?tagIds=$tag1Id,$tag2Id&matchMode=any")
        assertEquals(2L, cnt)
    }

    @Test
    fun `GET photos-count matchMode=any count is GTE matchMode=all count`() = withApp {
        val token = loginToken()
        val cntAny = getCount(token, "?tagIds=$tag1Id,$tag2Id&matchMode=any")
        val cntAll = getCount(token, "?tagIds=$tag1Id,$tag2Id&matchMode=all")
        assertTrue(cntAny >= cntAll)
    }

    @Test
    fun `GET photos-count single tagId matchMode any equals matchMode all`() = withApp {
        val token = loginToken()
        val cntAny = getCount(token, "?tagIds=$tag1Id&matchMode=any")
        val cntAll = getCount(token, "?tagIds=$tag1Id&matchMode=all")
        assertEquals(cntAll, cntAny)
    }

    @Test
    fun `GET photos-count with categoryIds returns correct count`() = withApp {
        val token = loginToken()
        // cat1 assigned only to photoB
        val cnt = getCount(token, "?categoryIds=$cat1Id")
        assertEquals(1L, cnt)
    }

    @Test
    fun `GET photos-count with labelIds returns correct count`() = withApp {
        val token = loginToken()
        // label-red assigned only to photoB
        val cnt = getCount(token, "?labelIds=$labelRedId")
        assertEquals(1L, cnt)
    }

    @Test
    fun `GET photos-count with q matching photo name returns correct count`() = withApp {
        val token = loginToken()
        // photoA name is "cnt_cnt-photo-a.jpg"
        val cnt = getCount(token, "?q=cnt-photo-a")
        assertTrue(cnt >= 1L)
    }

    // ── date range ────────────────────────────────────────────────────────────

    @Test
    fun `GET photos-count with dateFrom excludes photos whose effective date is before the bound`() = withApp {
        val token = loginToken()
        // photoD has capturedAt=2025-01-01; dateFrom=2026-01-01 should exclude it
        val cntWithDateFrom = getCount(token, "?dateFrom=2026-01-01T00:00:00Z")
        val cntAll = getCount(token)
        assertTrue(cntWithDateFrom < cntAll, "dateFrom=2026 should exclude photoD (capturedAt=2025)")
    }

    @Test
    fun `GET photos-count with dateTo window covering only capturedAt returns photoD`() = withApp {
        val token = loginToken()
        // Only photoD has a capturedAt in 2025 — narrow window around it
        val cnt = getCount(token, "?dateFrom=2025-01-01T00:00:00Z&dateTo=2025-12-31T23:59:59Z")
        assertEquals(1L, cnt)
    }

    @Test
    fun `GET photos-count with tight date range around a single photo uploadedAt`() = withApp {
        val token = loginToken()
        // photoB uploadedAt = 2026-06-01T10:01:00Z
        val cnt = getCount(token, "?dateFrom=2026-06-01T10:00:30Z&dateTo=2026-06-01T10:01:30Z")
        assertEquals(1L, cnt)
    }

    // ── ignored pagination params ─────────────────────────────────────────────

    @Test
    fun `GET photos-count is not affected by limit param`() = withApp {
        val token = loginToken()
        val cntNoLimit = getCount(token)
        val cntWithLimit = getCount(token, "?limit=1")
        assertEquals(cntNoLimit, cntWithLimit)
    }

    // ── combined filters ──────────────────────────────────────────────────────

    @Test
    fun `GET photos-count combined tag and favoritesOnly filter`() = withApp {
        val token = loginToken()
        // tag1 on photoB+photoC; favoritesOnly=true → only photoB → intersection = 1
        val cnt = getCount(token, "?tagIds=$tag1Id&favoritesOnly=true")
        assertEquals(1L, cnt)
    }

    // ── validation errors ─────────────────────────────────────────────────────

    @Test
    fun `GET photos-count with invalid matchMode returns 400 validation-failed with errors field`() = withApp {
        val token = loginToken()
        val r = client.get("/v1/photos/count?matchMode=bogus") {
            header(HttpHeaders.Authorization, "Bearer $token")
        }
        assertEquals(HttpStatusCode.BadRequest, r.status)
        val body = lenient.parseToJsonElement(r.bodyAsText()).jsonObject
        assertTrue(body["type"]?.jsonPrimitive?.content?.endsWith("validation-failed") == true)
        assertTrue(body["errors"]?.jsonObject?.containsKey("matchMode") == true)
        assertTrue(r.headers[HttpHeaders.ContentType]?.startsWith("application/problem+json") == true)
    }

    @Test
    fun `GET photos-count with invalid dateFrom returns 400 validation-failed`() = withApp {
        val token = loginToken()
        val r = client.get("/v1/photos/count?dateFrom=not-a-date") {
            header(HttpHeaders.Authorization, "Bearer $token")
        }
        assertEquals(HttpStatusCode.BadRequest, r.status)
        val body = lenient.parseToJsonElement(r.bodyAsText()).jsonObject
        assertTrue(body["type"]?.jsonPrimitive?.content?.endsWith("validation-failed") == true)
        assertTrue(body["errors"]?.jsonObject?.containsKey("dateFrom") == true)
    }

    @Test
    fun `GET photos-count with invalid dateTo returns 400 validation-failed`() = withApp {
        val token = loginToken()
        val r = client.get("/v1/photos/count?dateTo=2026-13-99") {
            header(HttpHeaders.Authorization, "Bearer $token")
        }
        assertEquals(HttpStatusCode.BadRequest, r.status)
        val body = lenient.parseToJsonElement(r.bodyAsText()).jsonObject
        assertTrue(body["type"]?.jsonPrimitive?.content?.endsWith("validation-failed") == true)
        assertTrue(body["errors"]?.jsonObject?.containsKey("dateTo") == true)
    }
}

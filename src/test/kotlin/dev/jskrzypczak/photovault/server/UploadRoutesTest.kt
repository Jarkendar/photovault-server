package dev.jskrzypczak.photovault.server

import dev.jskrzypczak.photovault.server.auth.AuthService
import dev.jskrzypczak.photovault.server.auth.JwtConfig
import dev.jskrzypczak.photovault.server.auth.JwtService
import dev.jskrzypczak.photovault.server.db.initDatabase
import dev.jskrzypczak.photovault.server.db.tables.Categories
import dev.jskrzypczak.photovault.server.db.tables.Tags
import dev.jskrzypczak.photovault.server.db.tables.Uploads
import dev.jskrzypczak.photovault.server.photos.PhotoService
import dev.jskrzypczak.photovault.server.plugins.configureSecurity
import dev.jskrzypczak.photovault.server.plugins.configureSerialization
import dev.jskrzypczak.photovault.server.plugins.configureStatusPages
import dev.jskrzypczak.photovault.server.routes.photoRoutes
import dev.jskrzypczak.photovault.server.storage.PhotoAssetStorage
import dev.jskrzypczak.photovault.server.uploads.UploadService
import dev.jskrzypczak.photovault.server.uploads.uploadRoutes
import io.ktor.client.request.delete
import io.ktor.client.request.forms.formData
import io.ktor.client.request.forms.submitFormWithBinaryData
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
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
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.time.Instant
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class UploadRoutesTest {

    companion object {
        @JvmStatic
        @Container
        val postgres: PostgreSQLContainer<*> = PostgreSQLContainer("postgres:16-alpine")
    }

    private lateinit var jwtConfig: JwtConfig
    private lateinit var jwtService: JwtService
    private lateinit var authService: AuthService
    private lateinit var photoService: PhotoService
    private lateinit var uploadService: UploadService

    private val lenient = Json { ignoreUnknownKeys = true }

    private val adminUserId = "user-admin"
    private val tagId = "tag-upload-1"
    private val catId = "cat-upload-1"
    private val labelId = "label-red" // seeded by initDatabase

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
            secret = "upload-routes-test-secret",
            issuer = "photovault",
            audience = "photovault-client",
            accessTtlMinutes = 60,
            refreshTtlDays = 30,
        )
        jwtService = JwtService(jwtConfig)
        authService = AuthService(jwtService)

        val storageRoot = Files.createTempDirectory("photovault-upload-routes-test")
        val storage = PhotoAssetStorage(storageRoot)
        photoService = PhotoService(storage)
        uploadService = UploadService(storage)

        insertFixtures()
    }

    private fun insertFixtures() = transaction {
        if (Tags.select(Tags.id).where { Tags.id eq tagId }.count() == 0L) {
            Tags.insert { it[id] = tagId; it[name] = "#upload-tag" }
        }
        if (Categories.select(Categories.id).where { Categories.id eq catId }.count() == 0L) {
            Categories.insert { it[id] = catId; it[name] = "UploadCat"; it[colorHex] = "#aabbcc" }
        }
    }

    // ── helpers ────────────────────────────────────────────────────────────────

    /**
     * Mints an access token directly. The security plugin only verifies the JWT signature
     * and a non-null subject — the refreshJti is not cross-checked in test context.
     */
    private fun token() = jwtService.generateAccessToken(adminUserId, "upload-test-session")

    private fun withApp(block: suspend ApplicationTestBuilder.() -> Unit) =
        testApplication {
            application {
                configureSerialization()
                configureStatusPages()
                configureSecurity(jwtConfig)
                routing {
                    route("/v1") {
                        photoRoutes(photoService)
                        uploadRoutes(uploadService)
                    }
                }
            }
            block()
        }

    /** Generates a minimal JPEG in-memory via BufferedImage. No files on disk. */
    private fun makeJpegBytes(width: Int = 100, height: Int = 80): ByteArray {
        val img = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        val out = ByteArrayOutputStream()
        ImageIO.write(img, "jpeg", out)
        return out.toByteArray()
    }

    /** Generates a minimal PNG in-memory. */
    private fun makePngBytes(width: Int = 100, height: Int = 80): ByteArray {
        val img = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
        val out = ByteArrayOutputStream()
        ImageIO.write(img, "png", out)
        return out.toByteArray()
    }

    /** Polls GET /v1/uploads/{id} until status is no longer "processing" or max attempts. */
    private suspend fun ApplicationTestBuilder.pollUntilTerminal(uploadId: String): String {
        repeat(50) {
            delay(200)
            val st = lenient.parseToJsonElement(
                client.get("/v1/uploads/$uploadId") {
                    header(HttpHeaders.Authorization, "Bearer ${token()}")
                }.bodyAsText()
            ).jsonObject["status"]?.jsonPrimitive?.content
            if (st != "processing") return st ?: "unknown"
        }
        return "processing" // timed out
    }

    // ── POST happy path ────────────────────────────────────────────────────────

    @Test
    fun `POST valid JPEG returns 202 with processing status and Location header`() = withApp {
        val response = client.submitFormWithBinaryData(
            url = "/v1/uploads",
            formData = formData {
                append("file", makeJpegBytes(), Headers.build {
                    append(HttpHeaders.ContentType, "image/jpeg")
                    append(HttpHeaders.ContentDisposition, "filename=\"test.jpg\"")
                })
            },
        ) {
            header(HttpHeaders.Authorization, "Bearer ${token()}")
        }

        assertEquals(HttpStatusCode.Accepted, response.status)
        val location = response.headers[HttpHeaders.Location]
        assertNotNull(location)
        assertTrue(location.startsWith("/v1/uploads/upload-"))

        val body = lenient.parseToJsonElement(response.bodyAsText()).jsonObject
        assertEquals("processing", body["status"]?.jsonPrimitive?.content)
        assertTrue(body["photoId"] == null || body["photoId"].toString() == "null")
    }

    @Test
    fun `POST JPEG then poll until done - photo retrievable with all asset variants`() = withApp {
        val resp = client.submitFormWithBinaryData(
            url = "/v1/uploads",
            formData = formData {
                append("file", makeJpegBytes(400, 300), Headers.build {
                    append(HttpHeaders.ContentType, "image/jpeg")
                    append(HttpHeaders.ContentDisposition, "filename=\"poll.jpg\"")
                })
            },
        ) {
            header(HttpHeaders.Authorization, "Bearer ${token()}")
        }
        assertEquals(HttpStatusCode.Accepted, resp.status)

        val uploadId = lenient.parseToJsonElement(resp.bodyAsText()).jsonObject["id"]!!.jsonPrimitive.content
        val finalStatus = pollUntilTerminal(uploadId)
        assertEquals("done", finalStatus, "Upload should reach 'done' within 10 seconds")

        val pollBody = lenient.parseToJsonElement(
            client.get("/v1/uploads/$uploadId") {
                header(HttpHeaders.Authorization, "Bearer ${token()}")
            }.bodyAsText()
        ).jsonObject
        val photoId = pollBody["photoId"]?.jsonPrimitive?.content
        assertNotNull(photoId)

        assertEquals(HttpStatusCode.OK, client.get("/v1/photos/$photoId") {
            header(HttpHeaders.Authorization, "Bearer ${token()}")
        }.status)
        assertEquals(HttpStatusCode.OK, client.get("/v1/photos/$photoId/thumbnail") {
            header(HttpHeaders.Authorization, "Bearer ${token()}")
        }.status)
        assertEquals(HttpStatusCode.OK, client.get("/v1/photos/$photoId/medium") {
            header(HttpHeaders.Authorization, "Bearer ${token()}")
        }.status)
        assertEquals(HttpStatusCode.OK, client.get("/v1/photos/$photoId/original") {
            header(HttpHeaders.Authorization, "Bearer ${token()}")
        }.status)
    }

    @Test
    fun `POST with metadata links tags, categories, labels to the resulting photo`() = withApp {
        val metadata = """{"tagIds":["$tagId"],"categoryIds":["$catId"],"labelIds":["$labelId"]}"""
        val resp = client.submitFormWithBinaryData(
            url = "/v1/uploads",
            formData = formData {
                append("file", makeJpegBytes(), Headers.build {
                    append(HttpHeaders.ContentType, "image/jpeg")
                    append(HttpHeaders.ContentDisposition, "filename=\"meta.jpg\"")
                })
                append("metadata", metadata)
            },
        ) {
            header(HttpHeaders.Authorization, "Bearer ${token()}")
        }

        val uploadId = lenient.parseToJsonElement(resp.bodyAsText()).jsonObject["id"]!!.jsonPrimitive.content
        val finalStatus = pollUntilTerminal(uploadId)
        assertEquals("done", finalStatus)

        val photoId = lenient.parseToJsonElement(
            client.get("/v1/uploads/$uploadId") {
                header(HttpHeaders.Authorization, "Bearer ${token()}")
            }.bodyAsText()
        ).jsonObject["photoId"]!!.jsonPrimitive.content

        val photoBody = lenient.parseToJsonElement(
            client.get("/v1/photos/$photoId") {
                header(HttpHeaders.Authorization, "Bearer ${token()}")
            }.bodyAsText()
        ).jsonObject

        val tagIds = photoBody["tags"]!!.jsonArray.map { it.jsonObject["id"]!!.jsonPrimitive.content }
        assertTrue(tagId in tagIds, "Photo should have tag $tagId")

        val catIds = photoBody["categories"]!!.jsonArray.map { it.jsonObject["id"]!!.jsonPrimitive.content }
        assertTrue(catId in catIds, "Photo should have category $catId")

        val labelIds = photoBody["labels"]!!.jsonArray.map { it.jsonObject["id"]!!.jsonPrimitive.content }
        assertTrue(labelId in labelIds, "Photo should have label $labelId")
    }

    @Test
    fun `POST PNG file is processed correctly`() = withApp {
        val resp = client.submitFormWithBinaryData(
            url = "/v1/uploads",
            formData = formData {
                append("file", makePngBytes(), Headers.build {
                    append(HttpHeaders.ContentType, "image/png")
                    append(HttpHeaders.ContentDisposition, "filename=\"test.png\"")
                })
            },
        ) {
            header(HttpHeaders.Authorization, "Bearer ${token()}")
        }
        assertEquals(HttpStatusCode.Accepted, resp.status)

        val uploadId = lenient.parseToJsonElement(resp.bodyAsText()).jsonObject["id"]!!.jsonPrimitive.content
        assertEquals("done", pollUntilTerminal(uploadId))
    }

    // ── list / filter ──────────────────────────────────────────────────────────

    @Test
    fun `GET uploads lists the upload just created`() = withApp {
        val resp = client.submitFormWithBinaryData(
            url = "/v1/uploads",
            formData = formData {
                append("file", makeJpegBytes(), Headers.build {
                    append(HttpHeaders.ContentType, "image/jpeg")
                    append(HttpHeaders.ContentDisposition, "filename=\"list.jpg\"")
                })
            },
        ) {
            header(HttpHeaders.Authorization, "Bearer ${token()}")
        }
        val uploadId = lenient.parseToJsonElement(resp.bodyAsText()).jsonObject["id"]!!.jsonPrimitive.content

        val listResp = client.get("/v1/uploads") {
            header(HttpHeaders.Authorization, "Bearer ${token()}")
        }
        assertEquals(HttpStatusCode.OK, listResp.status)
        assertTrue(listResp.bodyAsText().contains(uploadId))
    }

    @Test
    fun `GET uploads with status filter returns only matching uploads`() = withApp {
        val resp = client.submitFormWithBinaryData(
            url = "/v1/uploads",
            formData = formData {
                append("file", makeJpegBytes(), Headers.build {
                    append(HttpHeaders.ContentType, "image/jpeg")
                    append(HttpHeaders.ContentDisposition, "filename=\"filter.jpg\"")
                })
            },
        ) {
            header(HttpHeaders.Authorization, "Bearer ${token()}")
        }
        val uploadId = lenient.parseToJsonElement(resp.bodyAsText()).jsonObject["id"]!!.jsonPrimitive.content
        assertEquals("done", pollUntilTerminal(uploadId))

        val doneList = client.get("/v1/uploads?status=done") {
            header(HttpHeaders.Authorization, "Bearer ${token()}")
        }.bodyAsText()
        assertTrue(doneList.contains(uploadId), "done filter should include the upload")

        val processingList = client.get("/v1/uploads?status=processing") {
            header(HttpHeaders.Authorization, "Bearer ${token()}")
        }.bodyAsText()
        assertTrue(!processingList.contains(uploadId), "processing filter must exclude a done upload")
    }

    @Test
    fun `uploaded photo lands in pending_categorization and is visible via processingStatus filter`() = withApp {
        val resp = client.submitFormWithBinaryData(
            url = "/v1/uploads",
            formData = formData {
                append("file", makeJpegBytes(), Headers.build {
                    append(HttpHeaders.ContentType, "image/jpeg")
                    append(HttpHeaders.ContentDisposition, "filename=\"pending.jpg\"")
                })
            },
        ) {
            header(HttpHeaders.Authorization, "Bearer ${token()}")
        }
        val uploadId = lenient.parseToJsonElement(resp.bodyAsText()).jsonObject["id"]!!.jsonPrimitive.content
        assertEquals("done", pollUntilTerminal(uploadId))

        val photoId = lenient.parseToJsonElement(
            client.get("/v1/uploads/$uploadId") {
                header(HttpHeaders.Authorization, "Bearer ${token()}")
            }.bodyAsText()
        ).jsonObject["photoId"]!!.jsonPrimitive.content

        // The photo DTO must report pending_categorization
        val photoBody = lenient.parseToJsonElement(
            client.get("/v1/photos/$photoId") {
                header(HttpHeaders.Authorization, "Bearer ${token()}")
            }.bodyAsText()
        ).jsonObject
        assertEquals("pending_categorization", photoBody["processingStatus"]?.jsonPrimitive?.content,
            "Photo should be in pending_categorization right after upload")

        // GET /v1/photos?processingStatus=pending_categorization must include this photo
        val filterList = lenient.parseToJsonElement(
            client.get("/v1/photos?processingStatus=pending_categorization") {
                header(HttpHeaders.Authorization, "Bearer ${token()}")
            }.bodyAsText()
        ).jsonObject
        val filteredIds = filterList["items"]!!.jsonArray
            .map { it.jsonObject["id"]!!.jsonPrimitive.content }
        assertTrue(photoId in filteredIds,
            "pending_categorization filter should include the newly uploaded photo")

        // GET /v1/photos/count?processingStatus=pending_categorization must be >= 1
        val countBody = lenient.parseToJsonElement(
            client.get("/v1/photos/count?processingStatus=pending_categorization") {
                header(HttpHeaders.Authorization, "Bearer ${token()}")
            }.bodyAsText()
        ).jsonObject
        val count = countBody["count"]!!.jsonPrimitive.content.toLong()
        assertTrue(count >= 1L, "Count of pending_categorization photos should be at least 1")
    }

    // ── error cases ────────────────────────────────────────────────────────────

    @Test
    fun `POST without file part returns 400 validation-failed`() = withApp {
        // Send a request with no body at all — no multipart
        val resp = client.submitFormWithBinaryData(
            url = "/v1/uploads",
            formData = formData {
                append("metadata", """{"tagIds":[]}""")
            },
        ) {
            header(HttpHeaders.Authorization, "Bearer ${token()}")
        }
        assertEquals(HttpStatusCode.BadRequest, resp.status)
        val body = resp.bodyAsText()
        assertTrue(body.contains("validation-failed"))
        assertTrue(body.contains("file"))
    }

    @Test
    fun `POST with text plain file returns 415 unsupported-media-type`() = withApp {
        val resp = client.submitFormWithBinaryData(
            url = "/v1/uploads",
            formData = formData {
                append("file", "hello world".toByteArray(), Headers.build {
                    append(HttpHeaders.ContentType, "text/plain")
                    append(HttpHeaders.ContentDisposition, "filename=\"note.txt\"")
                })
            },
        ) {
            header(HttpHeaders.Authorization, "Bearer ${token()}")
        }
        assertEquals(HttpStatusCode.UnsupportedMediaType, resp.status)
        assertTrue(resp.bodyAsText().contains("unsupported-media-type"))
    }

    @Test
    fun `GET unknown upload returns 404 upload-not-found`() = withApp {
        val resp = client.get("/v1/uploads/upload-does-not-exist") {
            header(HttpHeaders.Authorization, "Bearer ${token()}")
        }
        assertEquals(HttpStatusCode.NotFound, resp.status)
        assertTrue(resp.bodyAsText().contains("upload-not-found"))
    }

    @Test
    fun `DELETE processing upload returns 204`() = withApp {
        // Insert a row directly in processing state to control the timing
        val cancelId = "upload-cancel-${System.nanoTime()}"
        transaction {
            Uploads.insert {
                it[id] = cancelId
                it[fileName] = "cancel.jpg"
                it[sizeBytes] = 1000L
                it[uploadedBytes] = 1000L
                it[status] = "processing"
                it[progress] = 0.0
                it[createdAt] = Instant.now()
                it[owner] = adminUserId
            }
        }

        val resp = client.delete("/v1/uploads/$cancelId") {
            header(HttpHeaders.Authorization, "Bearer ${token()}")
        }
        assertEquals(HttpStatusCode.NoContent, resp.status)
    }

    @Test
    fun `DELETE done upload returns 409 invalid-state-transition`() = withApp {
        val resp = client.submitFormWithBinaryData(
            url = "/v1/uploads",
            formData = formData {
                append("file", makeJpegBytes(), Headers.build {
                    append(HttpHeaders.ContentType, "image/jpeg")
                    append(HttpHeaders.ContentDisposition, "filename=\"del.jpg\"")
                })
            },
        ) {
            header(HttpHeaders.Authorization, "Bearer ${token()}")
        }
        val uploadId = lenient.parseToJsonElement(resp.bodyAsText()).jsonObject["id"]!!.jsonPrimitive.content
        assertEquals("done", pollUntilTerminal(uploadId))

        val deleteResp = client.delete("/v1/uploads/$uploadId") {
            header(HttpHeaders.Authorization, "Bearer ${token()}")
        }
        assertEquals(HttpStatusCode.Conflict, deleteResp.status)
        assertTrue(deleteResp.bodyAsText().contains("invalid-state-transition"))
    }

    @Test
    fun `second DELETE of a cancelled upload returns 409`() = withApp {
        val cancelId = "upload-dblcancel-${System.nanoTime()}"
        transaction {
            Uploads.insert {
                it[id] = cancelId
                it[fileName] = "dblcancel.jpg"
                it[sizeBytes] = 1000L
                it[uploadedBytes] = 1000L
                it[status] = "processing"
                it[progress] = 0.0
                it[createdAt] = Instant.now()
                it[owner] = adminUserId
            }
        }

        val first = client.delete("/v1/uploads/$cancelId") {
            header(HttpHeaders.Authorization, "Bearer ${token()}")
        }
        assertEquals(HttpStatusCode.NoContent, first.status)

        val second = client.delete("/v1/uploads/$cancelId") {
            header(HttpHeaders.Authorization, "Bearer ${token()}")
        }
        assertEquals(HttpStatusCode.Conflict, second.status)
    }

    // ── auth guards ────────────────────────────────────────────────────────────

    @Test
    fun `POST without JWT returns 401`() = withApp {
        val resp = client.submitFormWithBinaryData(
            url = "/v1/uploads",
            formData = formData { append("file", makeJpegBytes()) },
        )
        assertEquals(HttpStatusCode.Unauthorized, resp.status)
    }

    @Test
    fun `GET uploads without JWT returns 401`() = withApp {
        assertEquals(HttpStatusCode.Unauthorized, client.get("/v1/uploads").status)
    }

    @Test
    fun `GET upload by id without JWT returns 401`() = withApp {
        assertEquals(HttpStatusCode.Unauthorized, client.get("/v1/uploads/upload-anything").status)
    }

    @Test
    fun `DELETE upload without JWT returns 401`() = withApp {
        assertEquals(HttpStatusCode.Unauthorized, client.delete("/v1/uploads/upload-anything").status)
    }
}

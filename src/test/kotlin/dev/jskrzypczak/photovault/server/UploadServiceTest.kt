package dev.jskrzypczak.photovault.server

import dev.jskrzypczak.photovault.server.db.initDatabase
import dev.jskrzypczak.photovault.server.db.tables.Photos
import dev.jskrzypczak.photovault.server.db.tables.Uploads
import dev.jskrzypczak.photovault.server.storage.PhotoAssetStorage
import dev.jskrzypczak.photovault.server.uploads.UploadService
import dev.jskrzypczak.photovault.server.uploads.dto.UploadMetadata
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.TestInstance
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class UploadServiceTest {

    companion object {
        @JvmStatic
        @Container
        val postgres: PostgreSQLContainer<*> = PostgreSQLContainer("postgres:16-alpine")
    }

    private lateinit var uploadService: UploadService
    private lateinit var storageRoot: java.nio.file.Path
    private val ownerId = "user-admin"

    @BeforeAll
    fun setup() {
        Database.connect(
            url = postgres.jdbcUrl,
            driver = "org.postgresql.Driver",
            user = postgres.username,
            password = postgres.password,
        )
        initDatabase()
        storageRoot = Files.createTempDirectory("photovault-svc-test")
        uploadService = UploadService(PhotoAssetStorage(storageRoot))
    }

    // ── helpers ────────────────────────────────────────────────────────────────

    private fun makeJpegBytes(width: Int = 400, height: Int = 300): ByteArray {
        val img = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        val out = ByteArrayOutputStream()
        ImageIO.write(img, "jpeg", out)
        return out.toByteArray()
    }

    private fun makePngBytes(width: Int = 200, height: Int = 150): ByteArray {
        val img = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
        val out = ByteArrayOutputStream()
        ImageIO.write(img, "png", out)
        return out.toByteArray()
    }

    /**
     * Polls the DB until the upload reaches [expectedStatus] or [maxWaitMs] elapses.
     * Throws if the deadline is exceeded.
     */
    private fun waitForStatus(uploadId: String, expectedStatus: String, maxWaitMs: Long = 10_000) {
        val deadline = System.currentTimeMillis() + maxWaitMs
        while (System.currentTimeMillis() < deadline) {
            val current = transaction {
                Uploads.selectAll().where { Uploads.id eq uploadId }
                    .firstOrNull()?.get(Uploads.status)
            }
            if (current == expectedStatus) return
            Thread.sleep(100)
        }
        val actual = transaction {
            Uploads.selectAll().where { Uploads.id eq uploadId }
                .firstOrNull()?.get(Uploads.status)
        }
        error("Upload $uploadId did not reach '$expectedStatus' within ${maxWaitMs}ms (actual: $actual)")
    }

    // ── pipeline success ───────────────────────────────────────────────────────

    @Test
    fun `JPEG pipeline produces thumbnail and medium within max dimensions`() {
        val jpeg = makeJpegBytes(400, 300)
        val dto = uploadService.createUpload("test.jpg", jpeg.size.toLong(), "image/jpeg",
            jpeg, UploadMetadata(), ownerId)

        assertEquals("processing", dto.status)
        assertEquals(0.0, dto.progress)
        waitForStatus(dto.id, "done")

        val photoId = transaction {
            Uploads.selectAll().where { Uploads.id eq dto.id }.first()[Uploads.photoId]
        }
        assertNotNull(photoId)

        val photoStatus = transaction {
            Photos.selectAll().where { Photos.id eq photoId!! }.first()[Photos.processingStatus]
        }
        assertEquals("pending_categorization", photoStatus,
            "Photo should be in pending_categorization after a successful upload")

        val thumb = ImageIO.read(storageRoot.resolve("$photoId/thumbnail.jpg").toFile())
        assertNotNull(thumb, "thumbnail.jpg must exist")
        assertTrue(thumb.width  <= 320, "thumbnail width ${thumb.width} must be ≤ 320")
        assertTrue(thumb.height <= 320, "thumbnail height ${thumb.height} must be ≤ 320")

        val medium = ImageIO.read(storageRoot.resolve("$photoId/medium.jpg").toFile())
        assertNotNull(medium, "medium.jpg must exist")
        assertTrue(medium.width  <= 1280, "medium width ${medium.width} must be ≤ 1280")
        assertTrue(medium.height <= 1280, "medium height ${medium.height} must be ≤ 1280")
    }

    @Test
    fun `PNG pipeline writes original as png extension and derivatives as jpeg`() {
        val png = makePngBytes()
        val dto = uploadService.createUpload("test.png", png.size.toLong(), "image/png",
            png, UploadMetadata(), ownerId)

        waitForStatus(dto.id, "done")

        val photoId = transaction {
            Uploads.selectAll().where { Uploads.id eq dto.id }.first()[Uploads.photoId]
        }!!

        assertTrue(storageRoot.resolve("$photoId/original.png").toFile().exists(),
            "original must use .png extension for PNG uploads")
        assertTrue(storageRoot.resolve("$photoId/thumbnail.jpg").toFile().exists())
        assertTrue(storageRoot.resolve("$photoId/medium.jpg").toFile().exists())
    }

    // ── pipeline failure ───────────────────────────────────────────────────────

    @Test
    fun `corrupt bytes set status to failed with non-null error message`() {
        val corrupt = byteArrayOf(0x00, 0x01, 0x02, 0x03, 0x04, 0x05)
        val dto = uploadService.createUpload("corrupt.jpg", corrupt.size.toLong(), "image/jpeg",
            corrupt, UploadMetadata(), ownerId)

        waitForStatus(dto.id, "failed")

        val row = transaction {
            Uploads.selectAll().where { Uploads.id eq dto.id }.first()
        }
        assertEquals("failed", row[Uploads.status])
        assertNotNull(row[Uploads.error], "error column must be set on failure")
        assertTrue(row[Uploads.error]!!.isNotBlank())
    }

    // ── MIME validation ────────────────────────────────────────────────────────

    @Test
    fun `unsupported content type throws before writing to DB`() {
        val thrown = runCatching {
            uploadService.createUpload("note.txt", 5L, "text/plain",
                "hello".toByteArray(), UploadMetadata(), ownerId)
        }
        assertTrue(thrown.isFailure)
        val ex = thrown.exceptionOrNull()
        assertNotNull(ex)
        assertTrue(ex.message?.contains("unsupported") == true || ex.javaClass.simpleName.contains("Api"),
            "Expected ApiException for unsupported mime type, got: ${ex.javaClass.name}")
    }
}

package dev.jskrzypczak.photovault.server.uploads

import dev.jskrzypczak.photovault.server.db.tables.PhotoCategories
import dev.jskrzypczak.photovault.server.db.tables.PhotoLabels
import dev.jskrzypczak.photovault.server.db.tables.PhotoTags
import dev.jskrzypczak.photovault.server.db.tables.Photos
import dev.jskrzypczak.photovault.server.db.tables.Uploads
import dev.jskrzypczak.photovault.server.errors.ApiException
import dev.jskrzypczak.photovault.server.storage.PhotoAssetStorage
import dev.jskrzypczak.photovault.server.uploads.dto.UploadDto
import dev.jskrzypczak.photovault.server.uploads.dto.UploadListDto
import dev.jskrzypczak.photovault.server.uploads.dto.UploadMetadata
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import net.coobird.thumbnailator.Thumbnails
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.batchInsert
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import org.slf4j.LoggerFactory
import java.io.ByteArrayOutputStream
import java.time.Instant
import java.util.UUID
import javax.imageio.ImageIO

private const val STATUS_PROCESSING = "processing"
private const val STATUS_DONE = "done"
private const val STATUS_FAILED = "failed"
private const val STATUS_CANCELLED = "cancelled"

/** Photo processing status set after a successful upload. The categoriser will advance it to `ready`. */
private const val STATUS_PHOTO_PENDING_CATEGORIZATION = "pending_categorization"

private val CANCELLABLE_STATUSES = setOf("created", "uploading", STATUS_PROCESSING)
private val ALLOWED_MIME_TYPES = setOf("image/jpeg", "image/png")

private const val THUMBNAIL_MAX_PX = 320
private const val MEDIUM_MAX_PX = 1280

// ── Processing progress milestones ────────────────────────────────────────────
private const val PROGRESS_DECODED   = 0.2
private const val PROGRESS_ORIGINAL  = 0.5
private const val PROGRESS_THUMBNAIL = 0.75
private const val PROGRESS_MEDIUM    = 0.9

/**
 * Domain service for the upload lifecycle: create, poll, list, cancel, and async processing.
 *
 * All DB access lives in [transaction] blocks. The async pipeline runs on a
 * [CoroutineScope] backed by [Dispatchers.IO] so it never blocks the Netty I/O threads.
 *
 * @param storage resolver and writer for binary asset files; injected so tests can point at a
 *   temp directory.
 */
class UploadService(private val storage: PhotoAssetStorage) {

    private val log = LoggerFactory.getLogger(UploadService::class.java)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Accepts an upload, persists the row at `processing`, and fires the async pipeline.
     *
     * Validates [contentType] before persisting — rejected requests never touch the DB.
     *
     * @throws ApiException(unsupported-media-type, 415) for types outside {image/jpeg, image/png}.
     */
    fun createUpload(
        fileName: String,
        sizeBytes: Long,
        contentType: String,
        bytes: ByteArray,
        metadata: UploadMetadata,
        ownerId: String,
    ): UploadDto {
        if (contentType !in ALLOWED_MIME_TYPES) {
            throw ApiException(
                slug = "unsupported-media-type",
                httpStatus = HttpStatusCode.UnsupportedMediaType,
                title = "Unsupported Media Type",
                detail = "File type '$contentType' is not supported. Accepted types: image/jpeg, image/png." +
                    " HEIC support is planned for a future release.",
            )
        }

        val uploadId = "upload-${UUID.randomUUID()}"
        val now = Instant.now()

        transaction {
            Uploads.insert {
                it[id] = uploadId
                it[Uploads.fileName] = fileName
                it[Uploads.sizeBytes] = sizeBytes
                it[uploadedBytes] = sizeBytes
                it[status] = STATUS_PROCESSING
                it[progress] = 0.0
                it[createdAt] = now
                it[owner] = ownerId
            }
        }

        scope.launch {
            processUpload(uploadId, fileName, sizeBytes, contentType, bytes, metadata, ownerId)
        }

        return UploadDto(
            id = uploadId,
            fileName = fileName,
            sizeBytes = sizeBytes,
            uploadedBytes = sizeBytes,
            status = STATUS_PROCESSING,
            progress = 0.0,
            createdAt = now.toString(),
        )
    }

    /**
     * Returns the upload owned by [ownerId] with the given [uploadId].
     *
     * @throws ApiException(upload-not-found, 404) when missing or not owned.
     */
    fun getUpload(uploadId: String, ownerId: String): UploadDto = transaction {
        Uploads.selectAll()
            .where { (Uploads.id eq uploadId) and (Uploads.owner eq ownerId) }
            .firstOrNull()
            ?.let { rowToDto(it) }
            ?: throw ApiException(
                slug = "upload-not-found",
                httpStatus = HttpStatusCode.NotFound,
                title = "Upload Not Found",
                detail = "No upload with id '$uploadId'",
            )
    }

    /**
     * Lists all uploads owned by [ownerId].
     *
     * When [statusFilter] is non-empty only rows whose `status` is in the list are returned
     * (matches the `?status=done,processing` query parameter).
     */
    fun listUploads(ownerId: String, statusFilter: List<String>): UploadListDto = transaction {
        val rows = Uploads.selectAll()
            .where { Uploads.owner eq ownerId }
            .let { q ->
                if (statusFilter.isEmpty()) q.toList()
                else q.filter { it[Uploads.status] in statusFilter }
            }
        UploadListDto(items = rows.map { rowToDto(it) })
    }

    /**
     * Cancels the upload if it is still in a cancellable state.
     *
     * Cancellation is cooperative: the async pipeline checks the flag before finalising, so a
     * late cancel may still complete — acceptable for M8.
     *
     * @throws ApiException(upload-not-found, 404) when missing or not owned.
     * @throws ApiException(invalid-state-transition, 409) when already in a terminal state.
     */
    fun cancelUpload(uploadId: String, ownerId: String): Unit = transaction {
        val row = Uploads.selectAll()
            .where { (Uploads.id eq uploadId) and (Uploads.owner eq ownerId) }
            .firstOrNull()
            ?: throw ApiException(
                slug = "upload-not-found",
                httpStatus = HttpStatusCode.NotFound,
                title = "Upload Not Found",
                detail = "No upload with id '$uploadId'",
            )

        val currentStatus = row[Uploads.status]
        if (currentStatus !in CANCELLABLE_STATUSES) {
            throw ApiException(
                slug = "invalid-state-transition",
                httpStatus = HttpStatusCode.Conflict,
                title = "Invalid State Transition",
                detail = "Upload '$uploadId' is already in terminal status '$currentStatus' and cannot be cancelled",
            )
        }

        Uploads.update({ Uploads.id eq uploadId }) {
            it[status] = STATUS_CANCELLED
        }
    }

    // ─── async pipeline ───────────────────────────────────────────────────────

    /**
     * Background: decode the image, write assets, create the photo row, and mark done.
     *
     * Any exception (corrupt image, IO error, etc.) is caught and the upload is marked failed.
     */
    private suspend fun processUpload(
        uploadId: String,
        fileName: String,
        sizeBytes: Long,
        contentType: String,
        bytes: ByteArray,
        metadata: UploadMetadata,
        ownerId: String,
    ) {
        // Early-exit if already cancelled
        val currentStatus = transaction {
            Uploads.selectAll()
                .where { Uploads.id eq uploadId }
                .firstOrNull()?.get(Uploads.status)
        }
        if (currentStatus == STATUS_CANCELLED) return

        val photoId = "photo-${UUID.randomUUID()}"

        try {
            val image = ImageIO.read(bytes.inputStream())
                ?: throw IllegalStateException(
                    "Failed to decode image — data is corrupt or the format is not supported by this server"
                )
            updateProgress(uploadId, PROGRESS_DECODED)

            val ext = if (contentType == "image/png") "png" else "jpg"
            val originalPath  = "$photoId/original.$ext"
            val thumbnailPath = "$photoId/thumbnail.jpg"
            val mediumPath    = "$photoId/medium.jpg"

            storage.write(originalPath, bytes)
            updateProgress(uploadId, PROGRESS_ORIGINAL)

            storage.write(thumbnailPath, resizeToJpeg(bytes, THUMBNAIL_MAX_PX))
            updateProgress(uploadId, PROGRESS_THUMBNAIL)

            storage.write(mediumPath, resizeToJpeg(bytes, MEDIUM_MAX_PX))
            updateProgress(uploadId, PROGRESS_MEDIUM)

            val now = Instant.now()

            transaction {
                // Re-check before writing to DB: a cancel might have arrived during asset IO or ramp
                val statusNow = Uploads.selectAll()
                    .where { Uploads.id eq uploadId }
                    .firstOrNull()?.get(Uploads.status)

                if (statusNow == STATUS_CANCELLED) {
                    storage.deleteAll(photoId)
                    return@transaction
                }

                Photos.insert {
                    it[id] = photoId
                    it[name] = fileName
                    it[Photos.sizeBytes] = sizeBytes
                    it[mimeType] = contentType
                    it[width] = image.width
                    it[height] = image.height
                    it[uploadedAt] = now
                    it[uploadedBy] = ownerId
                    it[processingStatus] = STATUS_PHOTO_PENDING_CATEGORIZATION
                    it[Photos.originalPath] = originalPath
                    it[Photos.thumbnailPath] = thumbnailPath
                    it[Photos.mediumPath] = mediumPath
                }

                if (metadata.tagIds.isNotEmpty()) {
                    PhotoTags.batchInsert(metadata.tagIds) { tagId ->
                        this[PhotoTags.photoId] = photoId
                        this[PhotoTags.tagId] = tagId
                    }
                }
                if (metadata.categoryIds.isNotEmpty()) {
                    PhotoCategories.batchInsert(metadata.categoryIds) { catId ->
                        this[PhotoCategories.photoId] = photoId
                        this[PhotoCategories.categoryId] = catId
                    }
                }
                if (metadata.labelIds.isNotEmpty()) {
                    PhotoLabels.batchInsert(metadata.labelIds) { labelId ->
                        this[PhotoLabels.photoId] = photoId
                        this[PhotoLabels.labelId] = labelId
                    }
                }

                Uploads.update({ Uploads.id eq uploadId }) {
                    it[status] = STATUS_DONE
                    it[Uploads.photoId] = photoId
                    it[progress] = 1.0
                }
            }
        } catch (e: Exception) {
            log.error("Upload processing failed [uploadId=$uploadId]: ${e.message}", e)
            transaction {
                Uploads.update({ Uploads.id eq uploadId }) {
                    it[status] = STATUS_FAILED
                    it[error] = e.message ?: "Unknown error"
                }
            }
            try { storage.deleteAll(photoId) } catch (_: Exception) {}
        }
    }

    // ─── helpers ─────────────────────────────────────────────────────────────

    private fun updateProgress(uploadId: String, value: Double) = transaction {
        Uploads.update({ Uploads.id eq uploadId }) { it[progress] = value }
    }

    /**
     * Resizes [input] to fit within a [maxPx] × [maxPx] bounding box, keeping aspect ratio,
     * and encodes as JPEG.
     */
    private fun resizeToJpeg(input: ByteArray, maxPx: Int): ByteArray {
        val out = ByteArrayOutputStream()
        Thumbnails.of(input.inputStream())
            .size(maxPx, maxPx)
            .keepAspectRatio(true)
            .outputFormat("jpeg")
            .toOutputStream(out)
        return out.toByteArray()
    }

    private fun rowToDto(row: ResultRow): UploadDto = UploadDto(
        id = row[Uploads.id],
        fileName = row[Uploads.fileName],
        sizeBytes = row[Uploads.sizeBytes],
        uploadedBytes = row[Uploads.uploadedBytes],
        status = row[Uploads.status],
        progress = row[Uploads.progress],
        photoId = row[Uploads.photoId],
        error = row[Uploads.error],
        createdAt = row[Uploads.createdAt].toString(),
    )
}

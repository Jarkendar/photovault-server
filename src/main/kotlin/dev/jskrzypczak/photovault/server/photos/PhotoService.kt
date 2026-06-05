package dev.jskrzypczak.photovault.server.photos

import dev.jskrzypczak.photovault.server.db.tables.Categories
import dev.jskrzypczak.photovault.server.db.tables.Labels
import dev.jskrzypczak.photovault.server.db.tables.PhotoCategories
import dev.jskrzypczak.photovault.server.db.tables.PhotoLabels
import dev.jskrzypczak.photovault.server.db.tables.PhotoTags
import dev.jskrzypczak.photovault.server.db.tables.Photos
import dev.jskrzypczak.photovault.server.db.tables.Tags
import dev.jskrzypczak.photovault.server.db.tables.Users
import dev.jskrzypczak.photovault.server.dto.CategoryDto
import dev.jskrzypczak.photovault.server.dto.LabelDto
import dev.jskrzypczak.photovault.server.dto.LocationDto
import dev.jskrzypczak.photovault.server.dto.PhotoDto
import dev.jskrzypczak.photovault.server.dto.PhotoPage
import dev.jskrzypczak.photovault.server.dto.PhotoUploaderDto
import dev.jskrzypczak.photovault.server.dto.TagDto
import dev.jskrzypczak.photovault.server.dto.UpdatePhotoRequest
import dev.jskrzypczak.photovault.server.errors.ApiException
import dev.jskrzypczak.photovault.server.storage.AssetVariant
import dev.jskrzypczak.photovault.server.storage.PhotoAssetStorage
import io.ktor.http.HttpStatusCode
import org.jetbrains.exposed.sql.AbstractQuery
import org.jetbrains.exposed.sql.Coalesce
import org.jetbrains.exposed.sql.Column
import org.jetbrains.exposed.sql.InSubQueryOp
import org.jetbrains.exposed.sql.Op
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.greaterEq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.inList
import org.jetbrains.exposed.sql.SqlExpressionBuilder.less
import org.jetbrains.exposed.sql.SqlExpressionBuilder.lessEq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.like
import org.jetbrains.exposed.sql.SqlExpressionBuilder.neq
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.batchInsert
import org.jetbrains.exposed.sql.count
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.innerJoin
import org.jetbrains.exposed.sql.lowerCase
import org.jetbrains.exposed.sql.or
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import org.jetbrains.exposed.sql.upsert
import org.slf4j.LoggerFactory
import java.time.Instant

/**
 * Query parameters for [PhotoService.listPhotos] and [PhotoService.countPhotos].
 *
 * [cursor]      — opaque pagination cursor (null on first page). Ignored by [countPhotos].
 * [limit]       — page size, clamped to [1, 100]. Ignored by [countPhotos].
 * [q]           — free-text search across photo name, tag names, category names.
 * [tagIds], [categoryIds], [labelIds] — id filters; combination logic controlled by [matchMode].
 * [favoritesOnly] — when true, return only favourited photos.
 * [uploadedBy]  — filter by uploader user id; already resolved from "me" at route level.
 * [matchMode]   — raw query-param string validated in the service. `null`/`"all"` = AND, `"any"` = OR.
 * [dateFrom]    — ISO-8601 date-time lower bound on `COALESCE(capturedAt, uploadedAt)`.
 * [dateTo]      — ISO-8601 date-time upper bound on `COALESCE(capturedAt, uploadedAt)`.
 */
data class PhotoQuery(
    val cursor: String? = null,
    val limit: Int = 30,
    val q: String? = null,
    val tagIds: List<String> = emptyList(),
    val categoryIds: List<String> = emptyList(),
    val labelIds: List<String> = emptyList(),
    val favoritesOnly: Boolean = false,
    val uploadedBy: String? = null,
    val matchMode: String? = null,
    val dateFrom: String? = null,
    val dateTo: String? = null,
    /** Filter by exact `processingStatus` value, e.g. `pending_categorization`. */
    val processingStatus: String? = null,
)

/** Combination logic for multi-value id filters ([PhotoQuery.tagIds], [PhotoQuery.categoryIds], [PhotoQuery.labelIds]). */
enum class MatchMode { ALL, ANY }

/**
 * Holds a resolved binary photo asset ready to be streamed to the client.
 *
 * [file]        — the actual file on disk.
 * [contentType] — MIME type string (e.g. "image/jpeg").
 * [etag]        — deterministic ETag for the asset (e.g. `"photo-abc-thumbnail"`).
 */
data class PhotoAsset(
    val file: java.io.File,
    val contentType: String,
    val etag: String,
)

// ── Source constants (photo_tags / photo_categories `source` column) ──────────────────────────────

/** Assignment created or last confirmed by the user via the app. */
private const val SOURCE_MANUAL = "manual"

/**
 * Tombstone — the user explicitly removed this tag/category from the photo.
 * The ML bot must never re-insert a pair whose source is `denied`.
 * Denied rows are invisible in all API responses.
 */
private const val SOURCE_DENIED = "denied"

/**
 * Assignment written by the ML categoriser job.
 * Declared here for documentation; the server never writes this value directly —
 * it is produced by the nightly Python job.
 */
@Suppress("unused")
private const val SOURCE_AUTO = "auto"

// ─────────────────────────────────────────────────────────────────────────────────────────────────

/**
 * Domain service for photo read and write operations.
 *
 * All database access is performed inside Exposed [transaction] blocks.
 * Pagination uses a cursor over `(uploadedAt DESC, id DESC)` so results are
 * stable under concurrent inserts — matches the index `idx_photos_cursor`.
 *
 * @param storage resolver for binary asset files on disk; injected so it can be
 *   backed by a temp directory in tests.
 */
class PhotoService(private val storage: PhotoAssetStorage) {

    private val log = LoggerFactory.getLogger(PhotoService::class.java)

    /**
     * Returns a single [PhotoDto] for the given [id].
     *
     * @throws ApiException(photo-not-found, 404) when no photo with that id exists.
     */
    fun getPhoto(id: String): PhotoDto = transaction {
        val row = (Photos innerJoin Users)
            .selectAll()
            .where { Photos.id eq id }
            .firstOrNull()
            ?: throw ApiException(
                slug = "photo-not-found",
                httpStatus = HttpStatusCode.NotFound,
                title = "Photo Not Found",
                detail = "No photo with id '$id'",
            )

        val photoId = row[Photos.id]
        val tags = fetchTagsForPhotos(listOf(photoId))
        val categories = fetchCategoriesForPhotos(listOf(photoId))
        val labels = fetchLabelsForPhotos(listOf(photoId))

        rowToDto(row, tags[photoId].orEmpty(), categories[photoId].orEmpty(), labels[photoId].orEmpty())
    }

    /**
     * Partially updates a photo's metadata and returns the refreshed [PhotoDto].
     *
     * Each field in [req] is optional (null = no change). List fields use set-replace
     * semantics: a non-null list (including empty) replaces the current relation in full.
     *
     * All referenced tag/category/label ids are validated before any mutation. If any
     * id does not exist, a `validation-failed` 400 is thrown with a field-level `errors` map.
     *
     * @throws ApiException(photo-not-found, 404) when the photo does not exist.
     * @throws ApiException(validation-failed, 400) when any referenced id is unknown.
     */
    fun updatePhoto(id: String, req: UpdatePhotoRequest): PhotoDto = transaction {
        // 1 — verify the photo exists
        Photos.selectAll().where { Photos.id eq id }.firstOrNull()
            ?: throw ApiException(
                slug = "photo-not-found",
                httpStatus = HttpStatusCode.NotFound,
                title = "Photo Not Found",
                detail = "No photo with id '$id'",
            )

        // 2 — validate all referenced ids (collect errors before mutating anything)
        val errors = mutableMapOf<String, List<String>>()

        req.tagIds?.let { ids ->
            if (ids.isNotEmpty()) {
                val found = Tags.select(Tags.id).where { Tags.id inList ids }
                    .map { it[Tags.id] }.toSet()
                val missing = ids.filter { it !in found }
                if (missing.isNotEmpty()) errors["tagIds"] = missing.map { "Tag '$it' does not exist" }
            }
        }
        req.categoryIds?.let { ids ->
            if (ids.isNotEmpty()) {
                val found = Categories.select(Categories.id).where { Categories.id inList ids }
                    .map { it[Categories.id] }.toSet()
                val missing = ids.filter { it !in found }
                if (missing.isNotEmpty()) errors["categoryIds"] = missing.map { "Category '$it' does not exist" }
            }
        }
        req.labelIds?.let { ids ->
            if (ids.isNotEmpty()) {
                val found = Labels.select(Labels.id).where { Labels.id inList ids }
                    .map { it[Labels.id] }.toSet()
                val missing = ids.filter { it !in found }
                if (missing.isNotEmpty()) errors["labelIds"] = missing.map { "Label '$it' does not exist" }
            }
        }

        if (errors.isNotEmpty()) {
            throw ApiException(
                slug = "validation-failed",
                httpStatus = HttpStatusCode.BadRequest,
                title = "Validation Failed",
                detail = "One or more referenced ids do not exist",
                errors = errors,
            )
        }

        // 3 — apply mutations
        req.isFavorite?.let { fav ->
            Photos.update({ Photos.id eq id }) { it[isFavorite] = fav }
        }
        // Tags and categories use source-aware replacement: unlinks become `denied` tombstones
        // instead of physical deletes so the ML bot never re-proposes a pair the user rejected.
        req.tagIds?.let { ids ->
            replaceRelationSourceAware(
                PhotoTags, PhotoTags.photoId, PhotoTags.tagId,
                PhotoTags.assignmentSource, PhotoTags.score, PhotoTags.embeddingRun,
                id, ids,
            )
        }
        req.categoryIds?.let { ids ->
            replaceRelationSourceAware(
                PhotoCategories, PhotoCategories.photoId, PhotoCategories.categoryId,
                PhotoCategories.assignmentSource, PhotoCategories.score, PhotoCategories.embeddingRun,
                id, ids,
            )
        }
        // Labels are system-defined and have no source tracking — use simple replace.
        req.labelIds?.let { ids ->
            replaceRelation(PhotoLabels, PhotoLabels.photoId, PhotoLabels.labelId, id, ids)
        }

        // 4 — return the refreshed photo (nested transaction reuses the current one)
        getPhoto(id)
    }

    /**
     * Deletes the photo with the given [id], its asset files, and all junction-table rows.
     *
     * Junction rows are removed via `ON DELETE CASCADE` FK constraints on [PhotoTags],
     * [PhotoCategories], and [PhotoLabels]. Asset file deletion is best-effort — a missing
     * file on disk is logged but does not fail the request.
     *
     * @throws ApiException(photo-not-found, 404) when the photo does not exist.
     */
    fun deletePhoto(id: String): Unit = transaction {
        // Fetch row to capture asset paths before deleting
        val row = Photos.selectAll().where { Photos.id eq id }.firstOrNull()
            ?: throw ApiException(
                slug = "photo-not-found",
                httpStatus = HttpStatusCode.NotFound,
                title = "Photo Not Found",
                detail = "No photo with id '$id'",
            )

        val originalRelPath  = row[Photos.originalPath]
        val mediumRelPath    = row[Photos.mediumPath]
        val thumbnailRelPath = row[Photos.thumbnailPath]

        // Remove DB row (junction table rows cascade automatically)
        Photos.deleteWhere { Photos.id eq id }

        // Best-effort delete asset files (failures are non-fatal)
        fun tryDelete(relPath: String?) {
            if (relPath == null) return
            try {
                val path = storage.resolve(relPath)
                if (storage.exists(path)) storage.delete(path)
            } catch (e: Exception) {
                log.warn("Could not delete asset file '{}' for photo '{}': {}", relPath, id, e.message)
            }
        }
        tryDelete(originalRelPath)
        tryDelete(mediumRelPath)
        tryDelete(thumbnailRelPath)
    }

    /**
     * Returns the binary [PhotoAsset] for the given [photoId] and [variant].
     *
     * Rules:
     * - [AssetVariant.THUMBNAIL] / [AssetVariant.MEDIUM]: throw 423 when `processingStatus == "processing"`.
     * - [AssetVariant.ORIGINAL]: always available regardless of processing status.
     * - Path not set in DB or file missing on disk: throw 404.
     *
     * @throws ApiException(photo-not-found, 404) when the photo does not exist, the path
     *   column is null, or the file is missing from disk.
     * @throws ApiException(processing-not-ready, 423) for thumbnail/medium while processing.
     */
    fun getAsset(photoId: String, variant: AssetVariant): PhotoAsset = transaction {
        val row = Photos
            .selectAll()
            .where { Photos.id eq photoId }
            .firstOrNull()
            ?: throw ApiException(
                slug = "photo-not-found",
                httpStatus = HttpStatusCode.NotFound,
                title = "Photo Not Found",
                detail = "No photo with id '$photoId'",
            )

        val processingStatus = row[Photos.processingStatus]

        if (variant != AssetVariant.ORIGINAL && processingStatus == "processing") {
            throw ApiException(
                slug = "processing-not-ready",
                httpStatus = HttpStatusCode.Locked,
                title = "Processing Not Ready",
                detail = "Photo '$photoId' is still being processed",
            )
        }

        val relativePath = when (variant) {
            AssetVariant.THUMBNAIL -> row[Photos.thumbnailPath]
            AssetVariant.MEDIUM    -> row[Photos.mediumPath]
            AssetVariant.ORIGINAL  -> row[Photos.originalPath]
        } ?: throw ApiException(
            slug = "photo-not-found",
            httpStatus = HttpStatusCode.NotFound,
            title = "Photo Not Found",
            detail = "Asset '${variant.name.lowercase()}' for photo '$photoId' is not available",
        )

        val path = storage.resolve(relativePath)
        if (!storage.exists(path)) {
            throw ApiException(
                slug = "photo-not-found",
                httpStatus = HttpStatusCode.NotFound,
                title = "Photo Not Found",
                detail = "Asset file for photo '$photoId' was not found on disk",
            )
        }

        val contentType = when (variant) {
            AssetVariant.ORIGINAL  -> row[Photos.mimeType]
            AssetVariant.THUMBNAIL,
            AssetVariant.MEDIUM    -> "image/jpeg"
        }

        PhotoAsset(
            file = path.toFile(),
            contentType = contentType,
            etag = "\"$photoId-${variant.name.lowercase()}\"",
        )
    }

    /**
     * Returns a page of photos matching [query].
     *
     * Photos are sorted `uploadedAt DESC, id DESC`. [PhotoPage.nextCursor] is null
     * and [PhotoPage.hasMore] is false on the last page.
     */
    fun listPhotos(query: PhotoQuery): PhotoPage = transaction {
        val limit = query.limit.coerceIn(1, 100)

        // ── build WHERE predicate ───────────────────────────────────────────────
        var predicate = buildFilterPredicate(query)

        // ── cursor predicate ──────────────────────────────────────────────────
        if (query.cursor != null) {
            val c = decodeCursor(query.cursor)
            val ts = try {
                Instant.parse(c.uploadedAt)
            } catch (e: Exception) {
                throw ApiException(
                    slug = "invalid-cursor",
                    httpStatus = HttpStatusCode.BadRequest,
                    title = "Invalid Cursor",
                    detail = "Cursor contains an invalid timestamp",
                )
            }
            predicate = predicate and (
                (Photos.uploadedAt less ts) or
                    ((Photos.uploadedAt eq ts) and (Photos.id less c.id))
                )
        }

        // ── fetch limit+1 rows to determine hasMore ────────────────────────────
        val rows = (Photos innerJoin Users)
            .selectAll()
            .where { predicate }
            .orderBy(Photos.uploadedAt, SortOrder.DESC)
            .orderBy(Photos.id, SortOrder.DESC)
            .limit(limit + 1)
            .toList()

        val hasMore = rows.size > limit
        val page = if (hasMore) rows.dropLast(1) else rows

        if (page.isEmpty()) {
            return@transaction PhotoPage(items = emptyList(), nextCursor = null, hasMore = false)
        }

        // ── batch-fetch metadata to avoid N+1 ────────────────────────────────
        val pageIds = page.map { it[Photos.id] }
        val tags = fetchTagsForPhotos(pageIds)
        val categories = fetchCategoriesForPhotos(pageIds)
        val labels = fetchLabelsForPhotos(pageIds)

        val items = page.map { row ->
            val pid = row[Photos.id]
            rowToDto(row, tags[pid].orEmpty(), categories[pid].orEmpty(), labels[pid].orEmpty())
        }

        val nextCursor = if (hasMore) {
            val last = page.last()
            encodeCursor(last[Photos.uploadedAt], last[Photos.id])
        } else null

        PhotoPage(items = items, nextCursor = nextCursor, hasMore = hasMore)
    }

    /**
     * Returns the number of photos matching [query].
     *
     * Uses the same filter predicate as [listPhotos] but without any pagination cursor or limit.
     */
    fun countPhotos(query: PhotoQuery): Long = transaction {
        val predicate = buildFilterPredicate(query)
        Photos.selectAll().where { predicate }.count()
    }

    // ─── private helpers ──────────────────────────────────────────────────────

    /**
     * Replaces the entire relation between [photoId] and [newIds] in [table].
     *
     * Deletes all existing rows for [photoId] in [table], then batch-inserts
     * a new row for each id in [newIds]. The junction primary key `(photoId, fkId)`
     * prevents duplicates. Called only after all ids have been validated.
     */
    private fun replaceRelation(
        table: Table,
        photoIdCol: Column<String>,
        fkCol: Column<String>,
        photoId: String,
        newIds: List<String>,
    ) {
        table.deleteWhere { photoIdCol eq photoId }
        if (newIds.isNotEmpty()) {
            table.batchInsert(newIds) { fkId ->
                this[photoIdCol] = photoId
                this[fkCol] = fkId
            }
        }
    }

    /**
     * Source-aware replacement of the relation between [photoId] and [newIds] in [table].
     *
     * Unlike [replaceRelation], this method never physically removes rows:
     * - **Link** (id ∈ [newIds]): upsert `source = [SOURCE_MANUAL]`, clear `score` and
     *   `embeddingRun`. Overrides any existing `denied` or `auto` assignment.
     * - **Unlink** (id currently in DB but absent from [newIds], and not already `denied`):
     *   set `source = [SOURCE_DENIED]` (tombstone). Leaves `score`/`embeddingRun` intact as
     *   free hard-negatives for the classifier.
     * - Ids already `denied` and absent from [newIds]: left unchanged (already tombstoned).
     *
     * Called only after all ids in [newIds] have been validated. Runs inside the caller's
     * transaction.
     */
    private fun replaceRelationSourceAware(
        table: Table,
        photoIdCol: Column<String>,
        fkCol: Column<String>,
        sourceCol: Column<String>,
        scoreCol: Column<Double?>,
        embeddingRunCol: Column<String?>,
        photoId: String,
        newIds: List<String>,
    ) {
        // Load the current (fkId → source) snapshot for this photo.
        val existing: Map<String, String> = table
            .select(fkCol, sourceCol)
            .where { photoIdCol eq photoId }
            .associate { it[fkCol] to it[sourceCol] }

        // (Re-)link: upsert with source=manual, clearing ML-produced fields.
        for (fkId in newIds) {
            table.upsert(photoIdCol, fkCol) {
                it[photoIdCol] = photoId
                it[fkCol] = fkId
                it[sourceCol] = SOURCE_MANUAL
                it[scoreCol] = null
                it[embeddingRunCol] = null
            }
        }

        // Unlink: write a denied tombstone instead of deleting the row.
        val newIdSet = newIds.toSet()
        for ((fkId, source) in existing) {
            if (fkId !in newIdSet && source != SOURCE_DENIED) {
                table.update({ (photoIdCol eq photoId) and (fkCol eq fkId) }) {
                    it[sourceCol] = SOURCE_DENIED
                }
            }
        }
    }

    /**
     * Parses the raw [matchMode] string into a [MatchMode] enum.
     *
     * `null` and `"all"` both map to [MatchMode.ALL] (the default). Any other value throws
     * `validation-failed` 400 with a field-level error on `matchMode`.
     */
    private fun parseMatchMode(raw: String?): MatchMode = when (raw?.lowercase()) {
        null, "all" -> MatchMode.ALL
        "any"       -> MatchMode.ANY
        else -> throw ApiException(
            slug = "validation-failed",
            httpStatus = HttpStatusCode.BadRequest,
            title = "Validation Failed",
            detail = "Invalid matchMode value",
            errors = mapOf("matchMode" to listOf("must be one of: all, any")),
        )
    }

    /**
     * Parses [raw] as an ISO-8601 date-time instant, or returns `null` when [raw] is `null`.
     *
     * Throws `validation-failed` 400 with a field-level error on [field] for malformed input.
     */
    private fun parseInstant(raw: String?, field: String): Instant? {
        raw ?: return null
        return try {
            Instant.parse(raw)
        } catch (e: Exception) {
            throw ApiException(
                slug = "validation-failed",
                httpStatus = HttpStatusCode.BadRequest,
                title = "Validation Failed",
                detail = "Invalid date-time for '$field'",
                errors = mapOf(field to listOf("must be an ISO-8601 date-time")),
            )
        }
    }

    /**
     * Builds a sub-query predicate that keeps only photo ids matching the listed [ids]
     * in the junction [table] via [fkCol], using the given [mode].
     *
     * [MatchMode.ALL]: `GROUP BY photoId HAVING COUNT(*) = ids.size` — photo must have all ids.
     * [MatchMode.ANY]: plain `WHERE fkId IN ids` — photo must have at least one id.
     *
     * When [sourceCol] is non-null, `denied` tombstone rows are excluded so that a denied
     * link cannot satisfy a filter query. Pass `null` for tables without a source column
     * (e.g. [PhotoLabels]).
     *
     * The junction PK `(photoId, fkId)` guarantees no duplicate pairs, so the HAVING count
     * equals the number of distinct matched ids. Uses [InSubQueryOp] directly.
     */
    private fun relationSubquery(
        table: Table,
        photoIdCol: Column<String>,
        fkCol: Column<String>,
        ids: List<String>,
        mode: MatchMode,
        sourceCol: Column<String>? = null,
    ): Op<Boolean> {
        val base = table.select(photoIdCol).where {
            if (sourceCol != null)
                (fkCol inList ids) and (sourceCol neq SOURCE_DENIED)
            else
                fkCol inList ids
        }
        val sub = if (mode == MatchMode.ALL)
            base.groupBy(photoIdCol).having { fkCol.count() eq ids.size.toLong() }
        else base
        return InSubQueryOp(Photos.id, sub)
    }

    /**
     * Builds the shared WHERE predicate used by both [listPhotos] and [countPhotos].
     *
     * Covers: favoritesOnly, uploadedBy, id-filters with [MatchMode], free-text [q],
     * and date-range ([PhotoQuery.dateFrom] / [PhotoQuery.dateTo]) against
     * `COALESCE(capturedAt, uploadedAt)`. Does NOT include the cursor clause.
     */
    private fun buildFilterPredicate(query: PhotoQuery): Op<Boolean> {
        val mode = parseMatchMode(query.matchMode)
        var p: Op<Boolean> = Op.TRUE

        if (query.favoritesOnly) {
            p = p and (Photos.isFavorite eq true)
        }

        if (query.uploadedBy != null) {
            p = p and (Photos.uploadedBy eq query.uploadedBy)
        }

        if (query.tagIds.isNotEmpty()) {
            p = p and relationSubquery(PhotoTags, PhotoTags.photoId, PhotoTags.tagId, query.tagIds, mode, PhotoTags.assignmentSource)
        }

        if (query.categoryIds.isNotEmpty()) {
            p = p and relationSubquery(
                PhotoCategories, PhotoCategories.photoId, PhotoCategories.categoryId,
                query.categoryIds, mode, PhotoCategories.assignmentSource,
            )
        }

        if (query.labelIds.isNotEmpty()) {
            // Labels have no source column — use simple subquery.
            p = p and relationSubquery(PhotoLabels, PhotoLabels.photoId, PhotoLabels.labelId, query.labelIds, mode)
        }

        if (!query.q.isNullOrBlank()) {
            p = p and buildFreeTextPredicate(query.q.trim())
        }

        // Date-range filter: compare COALESCE(capturedAt, uploadedAt) against the bounds.
        val effDate = Coalesce(Photos.capturedAt, Photos.uploadedAt)
        parseInstant(query.dateFrom, "dateFrom")?.let { p = p and (effDate greaterEq it) }
        parseInstant(query.dateTo,   "dateTo"  )?.let { p = p and (effDate lessEq it) }

        if (query.processingStatus != null) {
            p = p and (Photos.processingStatus eq query.processingStatus)
        }

        return p
    }

    /**
     * Free-text predicate: photo name ILIKE, OR photo has a tag whose name ILIKE,
     * OR photo belongs to a category whose name ILIKE.
     *
     * All matching is case-insensitive via [lowerCase] LIKE '%q%'.
     */
    private fun buildFreeTextPredicate(q: String): Op<Boolean> {
        val pattern = "%${q.lowercase()}%"

        val byName = Photos.name.lowerCase() like pattern

        val byTagSub = PhotoTags
            .innerJoin(Tags)
            .select(PhotoTags.photoId)
            .where { Tags.name.lowerCase() like pattern }
        val byTag = InSubQueryOp(Photos.id, byTagSub)

        val byCatSub = PhotoCategories
            .innerJoin(Categories)
            .select(PhotoCategories.photoId)
            .where { Categories.name.lowerCase() like pattern }
        val byCat = InSubQueryOp(Photos.id, byCatSub)

        return byName or byTag or byCat
    }

    /**
     * Batch-fetches tags for the given [photoIds] in a single query.
     *
     * [TagDto.photoCount] is the global count of how many photos carry that tag (denied
     * tombstone rows excluded). Denied assignments are also excluded from the per-photo list
     * so they remain invisible in all API responses.
     *
     * Returns a map of `photoId → List<TagDto>`.
     */
    private fun fetchTagsForPhotos(photoIds: List<String>): Map<String, List<TagDto>> {
        val countByTag = PhotoTags
            .select(PhotoTags.tagId, PhotoTags.tagId.count())
            .where { PhotoTags.assignmentSource neq SOURCE_DENIED }
            .groupBy(PhotoTags.tagId)
            .associate { it[PhotoTags.tagId] to it[PhotoTags.tagId.count()] }

        return PhotoTags
            .innerJoin(Tags)
            .select(PhotoTags.photoId, Tags.id, Tags.name, Tags.autoEnabled, Tags.rolledOut)
            .where { (PhotoTags.photoId inList photoIds) and (PhotoTags.assignmentSource neq SOURCE_DENIED) }
            .groupBy(
                { it[PhotoTags.photoId] },
                {
                    TagDto(
                        it[Tags.id],
                        it[Tags.name],
                        countByTag[it[Tags.id]] ?: 0L,
                        it[Tags.autoEnabled],
                        it[Tags.rolledOut],
                    )
                },
            )
    }

    /**
     * Batch-fetches categories for the given [photoIds] in a single query.
     *
     * [CategoryDto.photoCount] excludes denied tombstone rows, matching the visible
     * assignment list. Denied rows are hidden from the per-photo list as well.
     *
     * Returns a map of `photoId → List<CategoryDto>`.
     */
    private fun fetchCategoriesForPhotos(photoIds: List<String>): Map<String, List<CategoryDto>> {
        val countByCat = PhotoCategories
            .select(PhotoCategories.categoryId, PhotoCategories.categoryId.count())
            .where { PhotoCategories.assignmentSource neq SOURCE_DENIED }
            .groupBy(PhotoCategories.categoryId)
            .associate { it[PhotoCategories.categoryId] to it[PhotoCategories.categoryId.count()] }

        return PhotoCategories
            .innerJoin(Categories)
            .select(
                PhotoCategories.photoId,
                Categories.id,
                Categories.name,
                Categories.colorHex,
                Categories.autoEnabled,
                Categories.rolledOut,
            )
            .where { (PhotoCategories.photoId inList photoIds) and (PhotoCategories.assignmentSource neq SOURCE_DENIED) }
            .groupBy(
                { it[PhotoCategories.photoId] },
                {
                    CategoryDto(
                        it[Categories.id],
                        it[Categories.name],
                        it[Categories.colorHex],
                        countByCat[it[Categories.id]] ?: 0L,
                        it[Categories.autoEnabled],
                        it[Categories.rolledOut],
                    )
                },
            )
    }

    /**
     * Batch-fetches labels for the given [photoIds] in a single query.
     *
     * Returns a map of `photoId → List<LabelDto>`.
     */
    private fun fetchLabelsForPhotos(photoIds: List<String>): Map<String, List<LabelDto>> {
        val countByLabel = PhotoLabels
            .select(PhotoLabels.labelId, PhotoLabels.labelId.count())
            .groupBy(PhotoLabels.labelId)
            .associate { it[PhotoLabels.labelId] to it[PhotoLabels.labelId.count()] }

        return PhotoLabels
            .innerJoin(Labels)
            .select(PhotoLabels.photoId, Labels.id, Labels.name, Labels.colorHex)
            .where { PhotoLabels.photoId inList photoIds }
            .groupBy(
                { it[PhotoLabels.photoId] },
                {
                    LabelDto(
                        it[Labels.id],
                        it[Labels.name],
                        it[Labels.colorHex],
                        countByLabel[it[Labels.id]] ?: 0L,
                    )
                }
            )
    }

    /**
     * Maps a joined `Photos + Users` [ResultRow] to a [PhotoDto].
     *
     * [tags], [categories], [labels] must be pre-fetched and passed in
     * (avoid N+1 in batch scenarios).
     */
    private fun rowToDto(
        row: org.jetbrains.exposed.sql.ResultRow,
        tags: List<TagDto>,
        categories: List<CategoryDto>,
        labels: List<LabelDto>,
    ): PhotoDto {
        val id = row[Photos.id]
        val lat = row[Photos.lat]
        val lng = row[Photos.lng]
        val location = if (lat != null && lng != null) {
            LocationDto(lat, lng, row[Photos.placeName])
        } else null

        return PhotoDto(
            id = id,
            name = row[Photos.name],
            sizeBytes = row[Photos.sizeBytes],
            mimeType = row[Photos.mimeType],
            width = row[Photos.width],
            height = row[Photos.height],
            capturedAt = row[Photos.capturedAt]?.toString(),
            uploadedAt = row[Photos.uploadedAt].toString(),
            camera = row[Photos.camera],
            location = location,
            uploadedBy = PhotoUploaderDto(
                id = row[Users.id],
                displayName = row[Users.displayName],
            ),
            tags = tags,
            categories = categories,
            labels = labels,
            isFavorite = row[Photos.isFavorite],
            processingStatus = row[Photos.processingStatus],
            thumbnailUrl = "/v1/photos/$id/thumbnail",
            mediumUrl = "/v1/photos/$id/medium",
            originalUrl = "/v1/photos/$id/original",
        )
    }
}

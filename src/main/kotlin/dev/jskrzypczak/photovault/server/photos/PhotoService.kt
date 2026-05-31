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
import dev.jskrzypczak.photovault.server.errors.ApiException
import io.ktor.http.HttpStatusCode
import org.jetbrains.exposed.sql.AbstractQuery
import org.jetbrains.exposed.sql.Column
import org.jetbrains.exposed.sql.InSubQueryOp
import org.jetbrains.exposed.sql.Op
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.greater
import org.jetbrains.exposed.sql.SqlExpressionBuilder.inList
import org.jetbrains.exposed.sql.SqlExpressionBuilder.less
import org.jetbrains.exposed.sql.SqlExpressionBuilder.like
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.count
import org.jetbrains.exposed.sql.innerJoin
import org.jetbrains.exposed.sql.lowerCase
import org.jetbrains.exposed.sql.or
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import java.time.Instant

/**
 * Query parameters for [PhotoService.listPhotos].
 *
 * [cursor] — opaque pagination cursor (null on first page).
 * [limit]  — page size, clamped to [1, 100].
 * [q]      — free-text search across photo name, tag names, category names.
 * [tagIds], [categoryIds], [labelIds] — AND filters (photo must have all listed ids).
 * [favoritesOnly] — when true, return only favourited photos.
 * [uploadedBy] — filter by uploader user id; already resolved from "me" at route level.
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
)

/**
 * Domain service for read access to photos.
 *
 * All database access is performed inside Exposed [transaction] blocks.
 * Pagination uses a cursor over `(uploadedAt DESC, id DESC)` so results are
 * stable under concurrent inserts — matches the index `idx_photos_cursor`.
 */
class PhotoService {

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
     * Returns a page of photos matching [query].
     *
     * Photos are sorted `uploadedAt DESC, id DESC`. [PhotoPage.nextCursor] is null
     * and [PhotoPage.hasMore] is false on the last page.
     */
    fun listPhotos(query: PhotoQuery): PhotoPage = transaction {
        val limit = query.limit.coerceIn(1, 100)

        // ── build WHERE predicate ───────────────────────────────────────────────
        var predicate: Op<Boolean> = Op.TRUE

        if (query.favoritesOnly) {
            predicate = predicate and (Photos.isFavorite eq true)
        }

        if (query.uploadedBy != null) {
            predicate = predicate and (Photos.uploadedBy eq query.uploadedBy)
        }

        if (query.tagIds.isNotEmpty()) {
            predicate = predicate and andSubquery(PhotoTags, PhotoTags.photoId, PhotoTags.tagId, query.tagIds)
        }

        if (query.categoryIds.isNotEmpty()) {
            predicate = predicate and andSubquery(PhotoCategories, PhotoCategories.photoId, PhotoCategories.categoryId, query.categoryIds)
        }

        if (query.labelIds.isNotEmpty()) {
            predicate = predicate and andSubquery(PhotoLabels, PhotoLabels.photoId, PhotoLabels.labelId, query.labelIds)
        }

        if (!query.q.isNullOrBlank()) {
            predicate = predicate and buildFreeTextPredicate(query.q.trim())
        }

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

    // ─── private helpers ──────────────────────────────────────────────────────

    /**
     * Builds a sub-query predicate that keeps only photo ids that have **all** listed [ids]
     * in the given junction [table] via [fkCol].
     *
     * Uses `GROUP BY photoId HAVING COUNT(*) = ids.size`. The junction PK `(photoId, fkId)`
     * guarantees each pair appears at most once, so the count equals the number of matched items.
     *
     * Uses [InSubQueryOp] directly to avoid needing the ISqlExpressionBuilder receiver context.
     */
    private fun andSubquery(
        table: Table,
        photoIdCol: Column<String>,
        fkCol: Column<String>,
        ids: List<String>,
    ): Op<Boolean> {
        val sub = table
            .select(photoIdCol)
            .where { fkCol inList ids }
            .groupBy(photoIdCol)
            .having { fkCol.count() eq ids.size.toLong() }
        return InSubQueryOp(Photos.id, sub)
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
     * [TagDto.photoCount] is the global count of how many photos carry that tag,
     * computed via a sub-query on the full [PhotoTags] table.
     *
     * Returns a map of `photoId → List<TagDto>`.
     */
    private fun fetchTagsForPhotos(photoIds: List<String>): Map<String, List<TagDto>> {
        val countByTag = PhotoTags
            .select(PhotoTags.tagId, PhotoTags.tagId.count())
            .groupBy(PhotoTags.tagId)
            .associate { it[PhotoTags.tagId] to it[PhotoTags.tagId.count()] }

        return PhotoTags
            .innerJoin(Tags)
            .select(PhotoTags.photoId, Tags.id, Tags.name)
            .where { PhotoTags.photoId inList photoIds }
            .groupBy(
                { it[PhotoTags.photoId] },
                { TagDto(it[Tags.id], it[Tags.name], countByTag[it[Tags.id]] ?: 0L) }
            )
    }

    /**
     * Batch-fetches categories for the given [photoIds] in a single query.
     *
     * Returns a map of `photoId → List<CategoryDto>`.
     */
    private fun fetchCategoriesForPhotos(photoIds: List<String>): Map<String, List<CategoryDto>> {
        val countByCat = PhotoCategories
            .select(PhotoCategories.categoryId, PhotoCategories.categoryId.count())
            .groupBy(PhotoCategories.categoryId)
            .associate { it[PhotoCategories.categoryId] to it[PhotoCategories.categoryId.count()] }

        return PhotoCategories
            .innerJoin(Categories)
            .select(PhotoCategories.photoId, Categories.id, Categories.name, Categories.colorHex)
            .where { PhotoCategories.photoId inList photoIds }
            .groupBy(
                { it[PhotoCategories.photoId] },
                {
                    CategoryDto(
                        it[Categories.id],
                        it[Categories.name],
                        it[Categories.colorHex],
                        countByCat[it[Categories.id]] ?: 0L,
                    )
                }
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

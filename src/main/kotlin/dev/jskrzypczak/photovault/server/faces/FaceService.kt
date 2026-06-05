package dev.jskrzypczak.photovault.server.faces

import dev.jskrzypczak.photovault.server.db.tables.Categories
import dev.jskrzypczak.photovault.server.db.tables.FaceClusters
import dev.jskrzypczak.photovault.server.db.tables.Faces
import dev.jskrzypczak.photovault.server.db.tables.Tags
import dev.jskrzypczak.photovault.server.dto.FaceClusterDto
import dev.jskrzypczak.photovault.server.dto.FaceDto
import dev.jskrzypczak.photovault.server.errors.ApiException
import io.ktor.http.HttpStatusCode
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.VarCharColumnType
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update

class FaceService {

    /**
     * Lists all face clusters, optionally filtered to labelled or unlabelled ones.
     *
     * Sorted by faceCount descending — largest clusters first.
     */
    fun listClusters(labeledOnly: Boolean = false, unlabeledOnly: Boolean = false): List<FaceClusterDto> =
        transaction {
            FaceClusters.selectAll()
                .map { row ->
                    val repFaceId = row[FaceClusters.representativeFaceId]
                    val repFace = if (repFaceId != null) {
                        Faces.selectAll().where { Faces.id eq repFaceId }.firstOrNull()
                    } else null

                    FaceClusterDto(
                        id = row[FaceClusters.id],
                        faceCount = row[FaceClusters.faceCount],
                        tagId = row[FaceClusters.tagId],
                        categoryId = row[FaceClusters.categoryId],
                        representativePhotoId = repFace?.get(Faces.photoId),
                        representativeBboxX = repFace?.get(Faces.bboxX),
                        representativeBboxY = repFace?.get(Faces.bboxY),
                        representativeBboxW = repFace?.get(Faces.bboxW),
                        representativeBboxH = repFace?.get(Faces.bboxH),
                    )
                }
                .let { list ->
                    when {
                        unlabeledOnly -> list.filter { it.tagId == null && it.categoryId == null }
                        labeledOnly   -> list.filter { it.tagId != null || it.categoryId != null }
                        else          -> list
                    }
                }
                .sortedByDescending { it.faceCount }
        }

    /** Returns all faces that belong to the given cluster, sorted by det_score descending. */
    fun listFacesInCluster(clusterId: String): List<FaceDto> = transaction {
        requireClusterExists(clusterId)
        Faces.selectAll()
            .where { Faces.clusterId eq clusterId }
            .map { row ->
                FaceDto(
                    faceId = row[Faces.id],
                    photoId = row[Faces.photoId],
                    bboxX = row[Faces.bboxX],
                    bboxY = row[Faces.bboxY],
                    bboxW = row[Faces.bboxW],
                    bboxH = row[Faces.bboxH],
                    detScore = row[Faces.detScore],
                )
            }
            .sortedByDescending { it.detScore }
    }

    /**
     * Labels a face cluster as a person by mapping it to an existing tag or category.
     *
     * Effects (all in one transaction):
     * 1. Sets `auto_enabled = true` on the target tag/category.
     * 2. Writes `source='auto'` rows in `photo_tags`/`photo_categories` for every photo that
     *    has at least one face in this cluster — same `ON CONFLICT … WHERE source='auto'`
     *    semantics as Python `write.assign_auto()`, so `manual` and `denied` rows are never
     *    overwritten.
     * 3. Stores the tagId/categoryId on the cluster row.
     *
     * Exactly one of [tagId] or [categoryId] must be provided.
     */
    fun labelCluster(clusterId: String, tagId: String?, categoryId: String?): FaceClusterDto {
        if (tagId == null && categoryId == null)
            throw ApiException(
                slug = "validation-failed",
                httpStatus = HttpStatusCode.BadRequest,
                title = "Validation Failed",
                detail = "Provide either tagId or categoryId",
            )
        if (tagId != null && categoryId != null)
            throw ApiException(
                slug = "validation-failed",
                httpStatus = HttpStatusCode.BadRequest,
                title = "Validation Failed",
                detail = "Provide either tagId or categoryId, not both",
            )

        return transaction {
            requireClusterExists(clusterId)

            val photoIds = Faces.selectAll()
                .where { Faces.clusterId eq clusterId }
                .map { it[Faces.photoId] }
                .distinct()

            if (tagId != null) {
                Tags.selectAll().where { Tags.id eq tagId }.firstOrNull()
                    ?: throw ApiException(
                        slug = "tag-not-found",
                        httpStatus = HttpStatusCode.NotFound,
                        title = "Tag Not Found",
                        detail = "No tag with id '$tagId'",
                    )
                Tags.update({ Tags.id eq tagId }) { it[Tags.autoEnabled] = true }
                assignPhotosToTag(photoIds, tagId, clusterId)
            } else {
                requireNotNull(categoryId)
                Categories.selectAll().where { Categories.id eq categoryId }.firstOrNull()
                    ?: throw ApiException(
                        slug = "category-not-found",
                        httpStatus = HttpStatusCode.NotFound,
                        title = "Category Not Found",
                        detail = "No category with id '$categoryId'",
                    )
                Categories.update({ Categories.id eq categoryId }) { it[Categories.autoEnabled] = true }
                assignPhotosToCategory(photoIds, categoryId, clusterId)
            }

            FaceClusters.update({ FaceClusters.id eq clusterId }) {
                it[FaceClusters.tagId] = tagId
                it[FaceClusters.categoryId] = categoryId
            }

            listClusters().first { it.id == clusterId }
        }
    }

    /**
     * Deletes a cluster.  `SET NULL` FK on `faces.cluster_id` clears the column automatically,
     * leaving the faces unassigned so they can be re-clustered next time.
     */
    fun deleteCluster(clusterId: String) = transaction {
        val deleted = FaceClusters.deleteWhere { FaceClusters.id eq clusterId }
        if (deleted == 0) throw ApiException(
            slug = "face-cluster-not-found",
            httpStatus = HttpStatusCode.NotFound,
            title = "Face Cluster Not Found",
            detail = "No face cluster with id '$clusterId'",
        )
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private fun requireClusterExists(clusterId: String) {
        FaceClusters.selectAll().where { FaceClusters.id eq clusterId }.firstOrNull()
            ?: throw ApiException(
                slug = "face-cluster-not-found",
                httpStatus = HttpStatusCode.NotFound,
                title = "Face Cluster Not Found",
                detail = "No face cluster with id '$clusterId'",
            )
    }

    /**
     * Inserts `source='auto'` rows for each photo, respecting manual/denied precedence.
     *
     * Uses parameterised `exec()` — same ON CONFLICT semantics as Python `write.assign_auto()`.
     * IDs are bound as parameters so there is no SQL injection risk.
     */
    private fun org.jetbrains.exposed.sql.Transaction.assignPhotosToTag(photoIds: List<String>, tagId: String, clusterId: String) {
        if (photoIds.isEmpty()) return
        val runId = "label-cluster/$clusterId"
        val varcharType = VarCharColumnType(128)
        for (photoId in photoIds) {
            exec(
                stmt = """
                    INSERT INTO photo_tags (photo_id, tag_id, score, source, embedding_run)
                    VALUES (?, ?, NULL, 'auto', ?)
                    ON CONFLICT (photo_id, tag_id) DO UPDATE
                        SET embedding_run = EXCLUDED.embedding_run
                        WHERE photo_tags.source = 'auto'
                """.trimIndent(),
                args = listOf(
                    varcharType to photoId,
                    varcharType to tagId,
                    varcharType to runId,
                ),
            )
        }
    }

    private fun org.jetbrains.exposed.sql.Transaction.assignPhotosToCategory(photoIds: List<String>, categoryId: String, clusterId: String) {
        if (photoIds.isEmpty()) return
        val runId = "label-cluster/$clusterId"
        val varcharType = VarCharColumnType(128)
        for (photoId in photoIds) {
            exec(
                stmt = """
                    INSERT INTO photo_categories (photo_id, category_id, score, source, embedding_run)
                    VALUES (?, ?, NULL, 'auto', ?)
                    ON CONFLICT (photo_id, category_id) DO UPDATE
                        SET embedding_run = EXCLUDED.embedding_run
                        WHERE photo_categories.source = 'auto'
                """.trimIndent(),
                args = listOf(
                    varcharType to photoId,
                    varcharType to categoryId,
                    varcharType to runId,
                ),
            )
        }
    }
}

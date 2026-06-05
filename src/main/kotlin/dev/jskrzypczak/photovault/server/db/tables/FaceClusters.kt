package dev.jskrzypczak.photovault.server.db.tables

import org.jetbrains.exposed.sql.ReferenceOption
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.javatime.timestamp

/**
 * One row per identified face cluster produced by `cluster_faces.py`.
 *
 * A cluster is a proposed identity — a group of face vectors that the DBSCAN algorithm
 * decided belong to the same person.  The human labels it by mapping it to an existing
 * tag or category via `POST /v1/admin/face-clusters/{id}/label`.
 *
 * tagId / categoryId are nullable until the cluster is labelled.
 * representativeFaceId points to the face with the highest det_score in the cluster
 * and is used as the preview image in the admin UI / web tool.
 */
object FaceClusters : Table("face_clusters") {
    val id = varchar("id", 64)

    /** Tag this cluster has been mapped to, or null if not yet labelled. */
    val tagId = varchar("tag_id", 64)
        .references(Tags.id, onDelete = ReferenceOption.SET_NULL)
        .nullable()

    /** Category this cluster has been mapped to, or null if not yet labelled. */
    val categoryId = varchar("category_id", 64)
        .references(Categories.id, onDelete = ReferenceOption.SET_NULL)
        .nullable()

    /**
     * Face with the highest detection confidence in the cluster — used as preview.
     * SET NULL on cascade so the cluster survives individual face row deletions.
     */
    val representativeFaceId = varchar("representative_face_id", 64)
        .references(Faces.id, onDelete = ReferenceOption.SET_NULL)
        .nullable()

    /** Number of faces in this cluster at the time of the last clustering run. */
    val faceCount = integer("face_count")

    val createdAt = timestamp("created_at")

    override val primaryKey = PrimaryKey(id)
}

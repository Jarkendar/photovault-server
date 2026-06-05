package dev.jskrzypczak.photovault.server.db.tables

import org.jetbrains.exposed.sql.ReferenceOption
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.javatime.timestamp

/**
 * One row per detected face in a photo.
 *
 * Faces are detected by the categorizer (InsightFace buffalo_l) and stored here alongside
 * their bounding box in `medium.jpg` pixel coordinates.  The embedding vector itself lives
 * in `faces-<model>.npz` keyed by `id`.
 *
 * clusterId is nullable until `cluster_faces.py` (Phase 2 Iter 2) groups unidentified faces.
 * FK to FaceClusters is added in Iter 2 via a separate additive migration;
 * the column is created nullable here so Iter 1 works without FaceClusters existing.
 */
object Faces : Table("faces") {
    val id = varchar("id", 64)

    val photoId = varchar("photo_id", 64)
        .references(Photos.id, onDelete = ReferenceOption.CASCADE)

    /** Bounding box in pixels of medium.jpg (x=left, y=top). */
    val bboxX = integer("bbox_x")
    val bboxY = integer("bbox_y")
    val bboxW = integer("bbox_w")
    val bboxH = integer("bbox_h")

    /** Detection confidence score from the face detector (0–1). */
    val detScore = double("det_score")

    /**
     * Cluster this face belongs to; null until cluster_faces.py assigns it.
     * FK to FaceClusters is wired in Phase 2 Iter 2.
     */
    val clusterId = varchar("cluster_id", 64).nullable()

    /** Model used to detect and embed this face (e.g. "buffalo_l"). */
    val faceModel = varchar("face_model", 128)

    /** Embedding run identifier for auditability. */
    val embeddingRun = varchar("embedding_run", 128).nullable()

    val detectedAt = timestamp("detected_at")

    override val primaryKey = PrimaryKey(id)
}

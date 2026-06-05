package dev.jskrzypczak.photovault.server.db.tables

import org.jetbrains.exposed.sql.ReferenceOption
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.javatime.timestamp

object Photos : Table("photos") {
    val id = varchar("id", 64)
    val name = varchar("name", 500)
    val sizeBytes = long("size_bytes")
    val mimeType = varchar("mime_type", 100)
    val width = integer("width")
    val height = integer("height")
    val capturedAt = timestamp("captured_at").nullable()
    val uploadedAt = timestamp("uploaded_at")
    val camera = varchar("camera", 200).nullable()
    val lat = double("lat").nullable()
    val lng = double("lng").nullable()
    val placeName = varchar("place_name", 500).nullable()
    val uploadedBy = varchar("uploaded_by", 64).references(Users.id, onDelete = ReferenceOption.RESTRICT)
    val isFavorite = bool("is_favorite").default(false)
    val processingStatus = varchar("processing_status", 32)
    val originalPath = varchar("original_path", 1024).nullable()
    val mediumPath = varchar("medium_path", 1024).nullable()
    val thumbnailPath = varchar("thumbnail_path", 1024).nullable()

    /** When this photo was last embedded by the categoriser (null = not yet embedded). */
    val embeddedAt = timestamp("embedded_at").nullable()

    /** Model id used for the last embedding run (e.g. `"mobileclip-s2-onnx"`). */
    val embeddingModel = varchar("embedding_model", 128).nullable()

    /** When faces were last detected in this photo by the categoriser (null = not yet detected). */
    val facesDetectedAt = timestamp("faces_detected_at").nullable()

    /** Face model id used for the last face detection run (e.g. `"buffalo_l"`). */
    val faceDetectionModel = varchar("face_detection_model", 128).nullable()

    override val primaryKey = PrimaryKey(id)
}

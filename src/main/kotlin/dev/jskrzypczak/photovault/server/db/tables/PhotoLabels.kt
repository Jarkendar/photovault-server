package dev.jskrzypczak.photovault.server.db.tables

import org.jetbrains.exposed.sql.ReferenceOption
import org.jetbrains.exposed.sql.Table

object PhotoLabels : Table("photo_labels") {
    val photoId = varchar("photo_id", 64).references(Photos.id, onDelete = ReferenceOption.CASCADE)
    val labelId = varchar("label_id", 64).references(Labels.id, onDelete = ReferenceOption.CASCADE)

    override val primaryKey = PrimaryKey(photoId, labelId)
}

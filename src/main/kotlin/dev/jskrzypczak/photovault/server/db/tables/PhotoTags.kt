package dev.jskrzypczak.photovault.server.db.tables

import org.jetbrains.exposed.sql.ReferenceOption
import org.jetbrains.exposed.sql.Table

object PhotoTags : Table("photo_tags") {
    val photoId = varchar("photo_id", 64).references(Photos.id, onDelete = ReferenceOption.CASCADE)
    val tagId = varchar("tag_id", 64).references(Tags.id, onDelete = ReferenceOption.CASCADE)

    override val primaryKey = PrimaryKey(photoId, tagId)
}

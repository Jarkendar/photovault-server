package dev.jskrzypczak.photovault.server.db.tables

import org.jetbrains.exposed.sql.ReferenceOption
import org.jetbrains.exposed.sql.Table

object PhotoCategories : Table("photo_categories") {
    val photoId = varchar("photo_id", 64).references(Photos.id, onDelete = ReferenceOption.CASCADE)
    val categoryId = varchar("category_id", 64).references(Categories.id, onDelete = ReferenceOption.CASCADE)

    override val primaryKey = PrimaryKey(photoId, categoryId)
}

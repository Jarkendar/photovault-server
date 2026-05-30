package dev.jskrzypczak.photovault.server.db.tables

import org.jetbrains.exposed.sql.ReferenceOption
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.javatime.timestamp

object Uploads : Table("uploads") {
    val id = varchar("id", 64)
    val fileName = varchar("file_name", 500)
    val sizeBytes = long("size_bytes")
    val uploadedBytes = long("uploaded_bytes").default(0L)
    val status = varchar("status", 20)
    val progress = double("progress").default(0.0)
    val photoId = varchar("photo_id", 64).references(Photos.id, onDelete = ReferenceOption.SET_NULL).nullable()
    val error = text("error").nullable()
    val createdAt = timestamp("created_at")
    val owner = varchar("owner", 64).references(Users.id, onDelete = ReferenceOption.CASCADE)

    override val primaryKey = PrimaryKey(id)
}

package dev.jskrzypczak.photovault.server.db.tables

import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.javatime.timestamp

object Users : Table("users") {
    val id = varchar("id", 64)
    val username = varchar("username", 100).uniqueIndex()
    val displayName = varchar("display_name", 200)
    val passwordHash = varchar("password_hash", 256)
    val role = varchar("role", 32).nullable()
    val createdAt = timestamp("created_at")

    override val primaryKey = PrimaryKey(id)
}

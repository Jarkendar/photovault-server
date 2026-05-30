package dev.jskrzypczak.photovault.server.db.tables

import org.jetbrains.exposed.sql.Table

object Tags : Table("tags") {
    val id = varchar("id", 64)
    val name = varchar("name", 200).uniqueIndex()

    override val primaryKey = PrimaryKey(id)
}

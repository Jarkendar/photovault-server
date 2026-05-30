package dev.jskrzypczak.photovault.server.db.tables

import org.jetbrains.exposed.sql.Table

object Categories : Table("categories") {
    val id = varchar("id", 64)
    val name = varchar("name", 200)
    val colorHex = varchar("color_hex", 9)

    override val primaryKey = PrimaryKey(id)
}

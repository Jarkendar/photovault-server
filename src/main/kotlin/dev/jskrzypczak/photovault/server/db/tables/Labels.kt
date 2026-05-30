package dev.jskrzypczak.photovault.server.db.tables

import org.jetbrains.exposed.sql.Table

object Labels : Table("labels") {
    val id = varchar("id", 64)
    val name = varchar("name", 50)
    val colorHex = varchar("color_hex", 9)

    override val primaryKey = PrimaryKey(id)
}

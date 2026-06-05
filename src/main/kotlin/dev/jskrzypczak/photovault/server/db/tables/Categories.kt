package dev.jskrzypczak.photovault.server.db.tables

import org.jetbrains.exposed.sql.Table

object Categories : Table("categories") {
    val id = varchar("id", 64)
    val name = varchar("name", 200)
    val colorHex = varchar("color_hex", 9)

    /**
     * Human switch — allows the categoriser bot to assign this category automatically.
     * Nightly job works only on categories where `auto_enabled = true`.
     */
    val autoEnabled = bool("auto_enabled").default(false)

    /**
     * `false` while a new category is awaiting a full library backfill pass (RTX job).
     * Flipped to `true` by the backfill script after a complete scoring run.
     */
    val rolledOut = bool("rolled_out").default(true)

    override val primaryKey = PrimaryKey(id)
}

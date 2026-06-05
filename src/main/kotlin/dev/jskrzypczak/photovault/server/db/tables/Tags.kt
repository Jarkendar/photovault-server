package dev.jskrzypczak.photovault.server.db.tables

import org.jetbrains.exposed.sql.Table

object Tags : Table("tags") {
    val id = varchar("id", 64)
    val name = varchar("name", 200).uniqueIndex()

    /**
     * Human switch — allows the categoriser bot to assign this tag automatically.
     * Nightly job works only on tags where `auto_enabled = true`.
     */
    val autoEnabled = bool("auto_enabled").default(false)

    /**
     * `false` while a new tag is awaiting a full library backfill pass (RTX job).
     * Flipped to `true` by the backfill script after a complete scoring run.
     */
    val rolledOut = bool("rolled_out").default(true)

    override val primaryKey = PrimaryKey(id)
}

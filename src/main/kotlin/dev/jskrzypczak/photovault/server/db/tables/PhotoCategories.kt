package dev.jskrzypczak.photovault.server.db.tables

import org.jetbrains.exposed.sql.ReferenceOption
import org.jetbrains.exposed.sql.Table

object PhotoCategories : Table("photo_categories") {
    val photoId = varchar("photo_id", 64).references(Photos.id, onDelete = ReferenceOption.CASCADE)
    val categoryId = varchar("category_id", 64).references(Categories.id, onDelete = ReferenceOption.CASCADE)

    /** ML classifier confidence score; null for manually assigned or not-yet-scored links. */
    val score = double("score").nullable()

    /**
     * Assignment origin: `manual` (user), `auto` (ML), `denied` (tombstone — user removed it
     * and the bot must never re-insert this pair).
     *
     * Precedence: manual > denied > auto.
     *
     * Kotlin property is `assignmentSource` because `source` is reserved by Exposed's `ColumnSet`.
     */
    val assignmentSource = varchar("source", 16).default("manual")

    /** Identifier of the embedding run that produced this assignment; null for manual links. */
    val embeddingRun = varchar("embedding_run", 128).nullable()

    override val primaryKey = PrimaryKey(photoId, categoryId)
}

package dev.jskrzypczak.photovault.server.metadata

import dev.jskrzypczak.photovault.server.db.tables.Labels
import dev.jskrzypczak.photovault.server.db.tables.PhotoLabels
import dev.jskrzypczak.photovault.server.dto.LabelDto
import org.jetbrains.exposed.sql.count
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction

class LabelService {

    fun listLabels(): List<LabelDto> = transaction {
        val countByLabel = PhotoLabels
            .select(PhotoLabels.labelId, PhotoLabels.labelId.count())
            .groupBy(PhotoLabels.labelId)
            .associate { it[PhotoLabels.labelId] to it[PhotoLabels.labelId.count()] }

        Labels.selectAll().map { row ->
            LabelDto(
                id = row[Labels.id],
                name = row[Labels.name],
                colorHex = row[Labels.colorHex],
                photoCount = countByLabel[row[Labels.id]] ?: 0L,
            )
        }
    }
}

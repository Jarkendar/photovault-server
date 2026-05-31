package dev.jskrzypczak.photovault.server.metadata

import dev.jskrzypczak.photovault.server.db.tables.PhotoTags
import dev.jskrzypczak.photovault.server.db.tables.Tags
import dev.jskrzypczak.photovault.server.dto.TagDto
import dev.jskrzypczak.photovault.server.errors.ApiException
import io.ktor.http.HttpStatusCode
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.count
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.lowerCase
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import java.util.UUID

private val HEX_COLOR_RE = Regex("^#[0-9a-fA-F]{6}$")

class TagService {

    fun listTags(usedOnly: Boolean): List<TagDto> = transaction {
        val countByTag = PhotoTags
            .select(PhotoTags.tagId, PhotoTags.tagId.count())
            .groupBy(PhotoTags.tagId)
            .associate { it[PhotoTags.tagId] to it[PhotoTags.tagId.count()] }

        Tags.selectAll()
            .map { row ->
                TagDto(
                    id = row[Tags.id],
                    name = row[Tags.name],
                    photoCount = countByTag[row[Tags.id]] ?: 0L,
                )
            }
            .let { if (usedOnly) it.filter { tag -> tag.photoCount > 0 } else it }
            .sortedBy { it.name }
    }

    fun createTag(name: String): TagDto = transaction {
        val errors = mutableMapOf<String, List<String>>()
        if (name.isBlank()) errors["name"] = listOf("must not be blank")
        else if (!name.startsWith("#")) errors["name"] = listOf("must start with #")

        if (errors.isNotEmpty()) throw ApiException(
            slug = "validation-failed",
            httpStatus = HttpStatusCode.BadRequest,
            title = "Validation Failed",
            detail = "Request body contains invalid fields",
            errors = errors,
        )

        val existing = Tags.selectAll()
            .where { Tags.name.lowerCase() eq name.lowercase() }
            .count()
        if (existing > 0) throw ApiException(
            slug = "duplicate-tag-name",
            httpStatus = HttpStatusCode.Conflict,
            title = "Duplicate Tag Name",
            detail = "A tag with name '$name' already exists",
        )

        val id = "tag-${UUID.randomUUID()}"
        Tags.insert { it[Tags.id] = id; it[Tags.name] = name }
        TagDto(id = id, name = name, photoCount = 0L)
    }

    fun renameTag(id: String, name: String): TagDto = transaction {
        val errors = mutableMapOf<String, List<String>>()
        if (name.isBlank()) errors["name"] = listOf("must not be blank")
        else if (!name.startsWith("#")) errors["name"] = listOf("must start with #")

        if (errors.isNotEmpty()) throw ApiException(
            slug = "validation-failed",
            httpStatus = HttpStatusCode.BadRequest,
            title = "Validation Failed",
            detail = "Request body contains invalid fields",
            errors = errors,
        )

        val row = Tags.selectAll().where { Tags.id eq id }.firstOrNull()
            ?: throw ApiException(
                slug = "tag-not-found",
                httpStatus = HttpStatusCode.NotFound,
                title = "Tag Not Found",
                detail = "No tag with id '$id'",
            )

        val conflict = Tags.selectAll()
            .where { Tags.name.lowerCase() eq name.lowercase() }
            .firstOrNull()
        if (conflict != null && conflict[Tags.id] != id) throw ApiException(
            slug = "duplicate-tag-name",
            httpStatus = HttpStatusCode.Conflict,
            title = "Duplicate Tag Name",
            detail = "A tag with name '$name' already exists",
        )

        Tags.update({ Tags.id eq id }) { it[Tags.name] = name }

        val photoCount = PhotoTags.selectAll().where { PhotoTags.tagId eq id }.count()
        TagDto(id = id, name = name, photoCount = photoCount)
    }

    fun deleteTag(id: String) = transaction {
        val deleted = Tags.deleteWhere { Tags.id eq id }
        if (deleted == 0) throw ApiException(
            slug = "tag-not-found",
            httpStatus = HttpStatusCode.NotFound,
            title = "Tag Not Found",
            detail = "No tag with id '$id'",
        )
    }
}

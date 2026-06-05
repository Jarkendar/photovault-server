package dev.jskrzypczak.photovault.server.metadata

import dev.jskrzypczak.photovault.server.db.tables.PhotoTags
import dev.jskrzypczak.photovault.server.db.tables.Tags
import dev.jskrzypczak.photovault.server.dto.TagDto
import dev.jskrzypczak.photovault.server.errors.ApiException
import io.ktor.http.HttpStatusCode
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.neq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.count
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.lowerCase
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import java.util.UUID

/** Source value used to tombstone user-removed assignments; kept in sync with PhotoService. */
private const val SOURCE_DENIED = "denied"

class TagService {

    fun listTags(usedOnly: Boolean): List<TagDto> = transaction {
        // Exclude denied tombstones from the photo count so it reflects visible assignments only.
        val countByTag = PhotoTags
            .select(PhotoTags.tagId, PhotoTags.tagId.count())
            .where { PhotoTags.assignmentSource neq SOURCE_DENIED }
            .groupBy(PhotoTags.tagId)
            .associate { it[PhotoTags.tagId] to it[PhotoTags.tagId.count()] }

        Tags.selectAll()
            .map { row ->
                TagDto(
                    id = row[Tags.id],
                    name = row[Tags.name],
                    photoCount = countByTag[row[Tags.id]] ?: 0L,
                    autoEnabled = row[Tags.autoEnabled],
                    rolledOut = row[Tags.rolledOut],
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
        // New tags use column defaults: autoEnabled = false, rolledOut = true.
        TagDto(id = id, name = name, photoCount = 0L, autoEnabled = false, rolledOut = true)
    }

    /**
     * Partially updates a tag and returns the refreshed [TagDto].
     *
     * All parameters are optional — pass only the ones you want to change.
     * [name] must start with `#` and be unique (case-insensitive) if provided.
     *
     * @throws ApiException(tag-not-found, 404) when no tag with [id] exists.
     * @throws ApiException(validation-failed, 400) when [name] is blank or missing the `#` prefix.
     * @throws ApiException(duplicate-tag-name, 409) when [name] already belongs to another tag.
     */
    fun updateTag(id: String, name: String?, autoEnabled: Boolean?, rolledOut: Boolean?): TagDto = transaction {
        // Validate name if provided
        if (name != null) {
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
        }

        val row = Tags.selectAll().where { Tags.id eq id }.firstOrNull()
            ?: throw ApiException(
                slug = "tag-not-found",
                httpStatus = HttpStatusCode.NotFound,
                title = "Tag Not Found",
                detail = "No tag with id '$id'",
            )

        val newName = name ?: row[Tags.name]
        val newAutoEnabled = autoEnabled ?: row[Tags.autoEnabled]
        val newRolledOut = rolledOut ?: row[Tags.rolledOut]

        // Check for duplicate name only when name is actually changing
        if (name != null && name != row[Tags.name]) {
            val conflict = Tags.selectAll()
                .where { Tags.name.lowerCase() eq name.lowercase() }
                .firstOrNull()
            if (conflict != null && conflict[Tags.id] != id) throw ApiException(
                slug = "duplicate-tag-name",
                httpStatus = HttpStatusCode.Conflict,
                title = "Duplicate Tag Name",
                detail = "A tag with name '$name' already exists",
            )
        }

        Tags.update({ Tags.id eq id }) {
            it[Tags.name] = newName
            it[Tags.autoEnabled] = newAutoEnabled
            it[Tags.rolledOut] = newRolledOut
        }

        // Recount visible (non-denied) assignments for the returned photoCount
        val photoCount = PhotoTags
            .selectAll()
            .where { (PhotoTags.tagId eq id) and (PhotoTags.assignmentSource neq SOURCE_DENIED) }
            .count()
        TagDto(id = id, name = newName, photoCount = photoCount, autoEnabled = newAutoEnabled, rolledOut = newRolledOut)
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

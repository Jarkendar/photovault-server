package dev.jskrzypczak.photovault.server.metadata

import dev.jskrzypczak.photovault.server.db.tables.Categories
import dev.jskrzypczak.photovault.server.db.tables.PhotoCategories
import dev.jskrzypczak.photovault.server.dto.CategoryDto
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

private val HEX_COLOR_RE = Regex("^#[0-9a-fA-F]{6}$")

/** Source value used to tombstone user-removed assignments; kept in sync with PhotoService. */
private const val SOURCE_DENIED = "denied"

class CategoryService {

    fun listCategories(usedOnly: Boolean): List<CategoryDto> = transaction {
        // Exclude denied tombstones from the photo count so it reflects visible assignments only.
        val countByCat = PhotoCategories
            .select(PhotoCategories.categoryId, PhotoCategories.categoryId.count())
            .where { PhotoCategories.assignmentSource neq SOURCE_DENIED }
            .groupBy(PhotoCategories.categoryId)
            .associate { it[PhotoCategories.categoryId] to it[PhotoCategories.categoryId.count()] }

        Categories.selectAll()
            .map { row ->
                CategoryDto(
                    id = row[Categories.id],
                    name = row[Categories.name],
                    colorHex = row[Categories.colorHex],
                    photoCount = countByCat[row[Categories.id]] ?: 0L,
                    autoEnabled = row[Categories.autoEnabled],
                    rolledOut = row[Categories.rolledOut],
                )
            }
            .let { if (usedOnly) it.filter { cat -> cat.photoCount > 0 } else it }
            .sortedBy { it.name }
    }

    fun createCategory(name: String, colorHex: String): CategoryDto = transaction {
        val errors = validateFields(name, colorHex)
        if (errors.isNotEmpty()) throw ApiException(
            slug = "validation-failed",
            httpStatus = HttpStatusCode.BadRequest,
            title = "Validation Failed",
            detail = "Request body contains invalid fields",
            errors = errors,
        )

        val existing = Categories.selectAll()
            .where { Categories.name.lowerCase() eq name.lowercase() }
            .count()
        if (existing > 0) throw ApiException(
            slug = "duplicate-category-name",
            httpStatus = HttpStatusCode.Conflict,
            title = "Duplicate Category Name",
            detail = "A category with name '$name' already exists",
        )

        val id = "cat-${UUID.randomUUID()}"
        Categories.insert {
            it[Categories.id] = id
            it[Categories.name] = name
            it[Categories.colorHex] = colorHex
        }
        // New categories use column defaults: autoEnabled = false, rolledOut = true.
        CategoryDto(id = id, name = name, colorHex = colorHex, photoCount = 0L, autoEnabled = false, rolledOut = true)
    }

    /**
     * Partially updates a category and returns the refreshed [CategoryDto].
     *
     * All parameters are optional — pass only the ones you want to change.
     * If [name] is provided it must not be blank and must be unique (case-insensitive).
     * If [colorHex] is provided it must match `#RRGGBB`.
     *
     * @throws ApiException(category-not-found, 404) when no category with [id] exists.
     * @throws ApiException(validation-failed, 400) on bad name/colorHex values.
     * @throws ApiException(duplicate-category-name, 409) when [name] already belongs to another category.
     */
    fun updateCategory(
        id: String,
        name: String?,
        colorHex: String?,
        autoEnabled: Boolean?,
        rolledOut: Boolean?,
    ): CategoryDto = transaction {
        val row = Categories.selectAll().where { Categories.id eq id }.firstOrNull()
            ?: throw ApiException(
                slug = "category-not-found",
                httpStatus = HttpStatusCode.NotFound,
                title = "Category Not Found",
                detail = "No category with id '$id'",
            )

        val newName = name ?: row[Categories.name]
        val newColor = colorHex ?: row[Categories.colorHex]
        val newAutoEnabled = autoEnabled ?: row[Categories.autoEnabled]
        val newRolledOut = rolledOut ?: row[Categories.rolledOut]

        val errors = mutableMapOf<String, List<String>>()
        if (newName.isBlank()) errors["name"] = listOf("must not be blank")
        if (!HEX_COLOR_RE.matches(newColor)) errors["colorHex"] = listOf("must be a valid hex color (#RRGGBB)")
        if (errors.isNotEmpty()) throw ApiException(
            slug = "validation-failed",
            httpStatus = HttpStatusCode.BadRequest,
            title = "Validation Failed",
            detail = "Request body contains invalid fields",
            errors = errors,
        )

        if (name != null && name != row[Categories.name]) {
            val conflict = Categories.selectAll()
                .where { Categories.name.lowerCase() eq newName.lowercase() }
                .firstOrNull()
            if (conflict != null && conflict[Categories.id] != id) throw ApiException(
                slug = "duplicate-category-name",
                httpStatus = HttpStatusCode.Conflict,
                title = "Duplicate Category Name",
                detail = "A category with name '$newName' already exists",
            )
        }

        Categories.update({ Categories.id eq id }) {
            it[Categories.name] = newName
            it[Categories.colorHex] = newColor
            it[Categories.autoEnabled] = newAutoEnabled
            it[Categories.rolledOut] = newRolledOut
        }

        // Recount visible (non-denied) assignments for the returned photoCount
        val photoCount = PhotoCategories
            .selectAll()
            .where { (PhotoCategories.categoryId eq id) and (PhotoCategories.assignmentSource neq SOURCE_DENIED) }
            .count()
        CategoryDto(
            id = id,
            name = newName,
            colorHex = newColor,
            photoCount = photoCount,
            autoEnabled = newAutoEnabled,
            rolledOut = newRolledOut,
        )
    }

    fun deleteCategory(id: String) = transaction {
        val deleted = Categories.deleteWhere { Categories.id eq id }
        if (deleted == 0) throw ApiException(
            slug = "category-not-found",
            httpStatus = HttpStatusCode.NotFound,
            title = "Category Not Found",
            detail = "No category with id '$id'",
        )
    }

    private fun validateFields(name: String, colorHex: String): Map<String, List<String>> {
        val errors = mutableMapOf<String, List<String>>()
        if (name.isBlank()) errors["name"] = listOf("must not be blank")
        if (!HEX_COLOR_RE.matches(colorHex)) errors["colorHex"] = listOf("must be a valid hex color (#RRGGBB)")
        return errors
    }
}

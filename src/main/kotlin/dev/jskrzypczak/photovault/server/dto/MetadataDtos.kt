package dev.jskrzypczak.photovault.server.dto

import kotlinx.serialization.Serializable

/**
 * Tag as returned in photo metadata and `GET /v1/tags` responses.
 *
 * [photoCount] is the number of photos that currently carry this tag,
 * scoped to the query context (e.g. the current page's ids in photo lists).
 */
@Serializable
data class TagDto(
    val id: String,
    val name: String,
    val photoCount: Long,
)

/**
 * Category as returned in photo metadata and `GET /v1/categories` responses.
 *
 * [colorHex] is a 7-character hex string like `#FF8B45`.
 */
@Serializable
data class CategoryDto(
    val id: String,
    val name: String,
    val colorHex: String,
    val photoCount: Long,
)

/**
 * System-defined color label as returned in photo metadata and `GET /v1/labels` responses.
 *
 * [colorHex] is a 7-character hex string. The fixed label set is seeded at startup.
 */
@Serializable
data class LabelDto(
    val id: String,
    val name: String,
    val colorHex: String,
    val photoCount: Long,
)

@Serializable data class TagListResponse(val items: List<TagDto>)
@Serializable data class CategoryListResponse(val items: List<CategoryDto>)
@Serializable data class LabelListResponse(val items: List<LabelDto>)

@Serializable data class CreateTagRequest(val name: String)
@Serializable data class UpdateTagRequest(val name: String)
@Serializable data class CreateCategoryRequest(val name: String, val colorHex: String)
@Serializable data class UpdateCategoryRequest(val name: String? = null, val colorHex: String? = null)

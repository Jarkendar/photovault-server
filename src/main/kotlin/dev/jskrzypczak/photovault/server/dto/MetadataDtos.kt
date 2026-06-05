package dev.jskrzypczak.photovault.server.dto

import kotlinx.serialization.Serializable

/**
 * Tag as returned in photo metadata and `GET /v1/tags` responses.
 *
 * [photoCount] is the number of photos that currently carry this tag (denied assignments excluded).
 * [autoEnabled] — human switch that allows the ML bot to assign this tag automatically.
 * [rolledOut]   — `false` while awaiting a full library backfill pass; `true` otherwise.
 */
@Serializable
data class TagDto(
    val id: String,
    val name: String,
    val photoCount: Long,
    val autoEnabled: Boolean,
    val rolledOut: Boolean,
)

/**
 * Category as returned in photo metadata and `GET /v1/categories` responses.
 *
 * [colorHex]    is a 7-character hex string like `#FF8B45`.
 * [photoCount]  is the number of photos that carry this category (denied assignments excluded).
 * [autoEnabled] — human switch that allows the ML bot to assign this category automatically.
 * [rolledOut]   — `false` while awaiting a full library backfill pass; `true` otherwise.
 */
@Serializable
data class CategoryDto(
    val id: String,
    val name: String,
    val colorHex: String,
    val photoCount: Long,
    val autoEnabled: Boolean,
    val rolledOut: Boolean,
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

/**
 * PATCH body for `PATCH /v1/tags/{id}`.
 *
 * All fields are optional — send only the ones you want to change.
 * [name] must start with `#` and be unique if provided.
 */
@Serializable
data class UpdateTagRequest(
    val name: String? = null,
    val autoEnabled: Boolean? = null,
    val rolledOut: Boolean? = null,
)

@Serializable data class CreateCategoryRequest(val name: String, val colorHex: String)

/**
 * PATCH body for `PATCH /v1/categories/{id}`.
 *
 * All fields are optional — send only the ones you want to change.
 */
@Serializable
data class UpdateCategoryRequest(
    val name: String? = null,
    val colorHex: String? = null,
    val autoEnabled: Boolean? = null,
    val rolledOut: Boolean? = null,
)

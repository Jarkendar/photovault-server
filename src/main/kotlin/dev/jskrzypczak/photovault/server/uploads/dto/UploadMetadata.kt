package dev.jskrzypczak.photovault.server.uploads.dto

import kotlinx.serialization.Serializable

/**
 * Parsed from the optional `metadata` multipart text part.
 *
 * All fields default to empty lists so the struct can be used as a safe
 * fallback when the part is absent or its JSON is malformed.
 */
@Serializable
data class UploadMetadata(
    val tagIds: List<String> = emptyList(),
    val categoryIds: List<String> = emptyList(),
    val labelIds: List<String> = emptyList(),
)

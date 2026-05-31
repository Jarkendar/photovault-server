package dev.jskrzypczak.photovault.server.uploads.dto

import kotlinx.serialization.Serializable

/**
 * Represents a single upload resource as returned by `POST /v1/uploads`,
 * `GET /v1/uploads/{id}`, and `GET /v1/uploads`.
 *
 * [photoId] is non-null only when [status] == `"done"`.
 * [error]   is non-null only when [status] == `"failed"`.
 */
@Serializable
data class UploadDto(
    val id: String,
    val fileName: String,
    val sizeBytes: Long,
    val uploadedBytes: Long,
    val status: String,
    val progress: Double,
    val photoId: String? = null,
    val error: String? = null,
    val createdAt: String,
)

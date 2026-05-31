package dev.jskrzypczak.photovault.server.uploads.dto

import kotlinx.serialization.Serializable

/** Response body for `GET /v1/uploads`. */
@Serializable
data class UploadListDto(
    val items: List<UploadDto>,
)

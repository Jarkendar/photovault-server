package dev.jskrzypczak.photovault.server.dto

import kotlinx.serialization.Serializable

/** Response body for `GET /v1/health`. */
@Serializable
data class HealthDto(
    val status: String,
    val version: String,
)

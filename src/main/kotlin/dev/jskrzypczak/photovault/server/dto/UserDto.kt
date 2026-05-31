package dev.jskrzypczak.photovault.server.dto

import kotlinx.serialization.Serializable

/** Represents a user account as returned in API responses. */
@Serializable
data class UserDto(
    val id: String,
    val username: String,
    val displayName: String,
)

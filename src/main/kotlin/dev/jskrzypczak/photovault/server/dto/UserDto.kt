package dev.jskrzypczak.photovault.server.dto

import kotlinx.serialization.Serializable

/** Represents a user account as returned in API responses. */
@Serializable
data class UserDto(
    val id: String,
    val username: String,
    val displayName: String,
    /** ISO-8601 timestamp of account creation. Nullable so existing mappings compile without changes. */
    val createdAt: String? = null,
)

/** Response wrapper for `GET /v1/users`. */
@Serializable
data class UserListResponse(val items: List<UserDto>)

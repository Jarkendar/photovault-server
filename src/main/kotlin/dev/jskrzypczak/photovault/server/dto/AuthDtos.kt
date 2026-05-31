package dev.jskrzypczak.photovault.server.dto

import kotlinx.serialization.Serializable

/** Request body for POST /v1/auth/login. */
@Serializable
data class LoginRequest(
    val username: String,
    val password: String,
)

/** Request body for POST /v1/auth/refresh. */
@Serializable
data class RefreshRequest(
    val refreshToken: String,
)

/** Successful authentication response carrying a JWT token pair and the user profile. */
@Serializable
data class AuthResponse(
    val accessToken: String,
    val refreshToken: String,
    val user: UserDto,
)

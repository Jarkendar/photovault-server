package dev.jskrzypczak.photovault.server.auth

import io.ktor.server.config.ApplicationConfig

/**
 * Holds all JWT parameters read from application.yaml / environment variables.
 *
 * Corresponds to the `jwt.*` section in application.yaml.
 */
data class JwtConfig(
    val secret: String,
    val issuer: String,
    val audience: String,
    /** Lifetime of an access token in minutes. */
    val accessTtlMinutes: Long,
    /** Lifetime of a refresh token in days. */
    val refreshTtlDays: Long,
) {
    companion object {
        fun from(config: ApplicationConfig): JwtConfig = JwtConfig(
            secret = config.property("jwt.secret").getString(),
            issuer = config.property("jwt.issuer").getString(),
            audience = config.property("jwt.audience").getString(),
            accessTtlMinutes = config.property("jwt.accessTtlMinutes").getString().toLong(),
            refreshTtlDays = config.property("jwt.refreshTtlDays").getString().toLong(),
        )
    }
}

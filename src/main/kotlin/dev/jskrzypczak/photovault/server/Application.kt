package dev.jskrzypczak.photovault.server

import dev.jskrzypczak.photovault.server.plugins.configureDatabase
import dev.jskrzypczak.photovault.server.plugins.configureMonitoring
import dev.jskrzypczak.photovault.server.plugins.configureRouting
import dev.jskrzypczak.photovault.server.plugins.configureSerialization
import dev.jskrzypczak.photovault.server.plugins.configureStatusPages
import io.ktor.server.application.Application

/**
 * Main Ktor module. Entry point is [io.ktor.server.netty.EngineMain], which reads
 * `application.yaml` and calls this function automatically.
 *
 * Plugin installation order matters:
 *   1. Serialization — registers the JSON content type converter
 *   2. Monitoring — logs requests before they are dispatched
 *   3. StatusPages — intercepts thrown exceptions (must be before Routing)
 *   4. Database — HikariCP pool, schema creation, seed data
 *   5. Routing — dispatches requests to handler functions
 */
fun Application.module() {
    val version = environment.config.propertyOrNull("photovault.version")?.getString() ?: "unknown"

    configureSerialization()
    configureMonitoring()
    configureStatusPages()
    configureDatabase()
    configureRouting(version)
}

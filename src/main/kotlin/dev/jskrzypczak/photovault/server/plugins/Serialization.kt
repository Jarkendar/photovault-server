package dev.jskrzypczak.photovault.server.plugins

import dev.jskrzypczak.photovault.server.errors.appJson
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation

/** Installs [ContentNegotiation] with the shared [appJson] configuration (camelCase, no explicit nulls). */
fun Application.configureSerialization() {
    install(ContentNegotiation) {
        json(appJson)
    }
}

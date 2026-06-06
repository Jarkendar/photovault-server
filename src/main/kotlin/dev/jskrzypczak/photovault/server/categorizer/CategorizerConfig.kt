package dev.jskrzypczak.photovault.server.categorizer

import io.ktor.server.config.ApplicationConfig

data class CategorizerConfig(
    /** Shell command executed by the server to trigger a categorizer run (e.g. `docker run --rm ...`). Empty = not configured. */
    val command: String,
    /** Working directory for the shell command. */
    val workdir: String,
) {
    val isConfigured: Boolean get() = command.isNotBlank()

    companion object {
        fun from(config: ApplicationConfig) = CategorizerConfig(
            command = config.propertyOrNull("photovault.categorizer.command")?.getString() ?: "",
            workdir = config.propertyOrNull("photovault.categorizer.workdir")?.getString() ?: ".",
        )
    }
}

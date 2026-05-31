package dev.jskrzypczak.photovault.server.storage

import io.ktor.server.config.ApplicationConfig

/**
 * Holds storage configuration read from application.yaml / environment variables.
 *
 * Corresponds to the `photovault.storage.*` section in application.yaml.
 *
 * [root] — absolute or relative path to the directory containing photo asset files.
 * Relative paths are resolved from the JVM working directory.
 */
data class StorageConfig(val root: String) {
    companion object {
        fun from(config: ApplicationConfig): StorageConfig = StorageConfig(
            root = config.property("photovault.storage.root").getString(),
        )
    }
}

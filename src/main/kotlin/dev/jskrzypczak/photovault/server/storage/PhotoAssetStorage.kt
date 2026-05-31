package dev.jskrzypczak.photovault.server.storage

import java.nio.file.Path

/** Identifies which binary variant of a photo asset is requested. */
enum class AssetVariant { THUMBNAIL, MEDIUM, ORIGINAL }

/**
 * Resolves relative asset paths to absolute filesystem [Path] values.
 *
 * Paths stored in the database are relative to [root] (e.g. `photo-abc/thumbnail.jpg`).
 * This class joins them with the configured root and guards against path-traversal attacks.
 *
 * This class has no domain logic — callers are responsible for 404 / 423 decisions.
 */
class PhotoAssetStorage(private val root: Path) {

    /**
     * Returns the absolute [Path] for the given [relativePath].
     *
     * @throws IllegalArgumentException if the resolved path escapes [root]
     *   (path-traversal attempt).
     */
    fun resolve(relativePath: String): Path {
        val resolved = root.resolve(relativePath).normalize()
        require(resolved.startsWith(root.normalize())) {
            "Resolved path '$resolved' escapes storage root '$root'"
        }
        return resolved
    }

    /** Returns `true` when the file at [path] exists on the filesystem. */
    fun exists(path: Path): Boolean = path.toFile().exists()

    /**
     * Deletes the file at [path] if it exists.
     *
     * Silently ignores a failed delete — callers treat missing asset files
     * as a non-fatal condition.
     */
    fun delete(path: Path) {
        val file = path.toFile()
        if (file.exists()) file.delete()
    }
}

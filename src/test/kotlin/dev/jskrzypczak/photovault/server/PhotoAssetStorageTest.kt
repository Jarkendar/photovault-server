package dev.jskrzypczak.photovault.server

import dev.jskrzypczak.photovault.server.storage.PhotoAssetStorage
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PhotoAssetStorageTest {

    @TempDir
    lateinit var tempDir: Path

    private val storage by lazy { PhotoAssetStorage(tempDir) }

    @Test
    fun `resolve returns path within root for simple relative path`() {
        val resolved = storage.resolve("photo-abc/thumbnail.jpg")
        assertEquals(tempDir.resolve("photo-abc/thumbnail.jpg").normalize(), resolved)
    }

    @Test
    fun `resolve returns path within root for flat relative path`() {
        val resolved = storage.resolve("thumb123.jpg")
        assertEquals(tempDir.resolve("thumb123.jpg").normalize(), resolved)
    }

    @Test
    fun `resolve throws IllegalArgumentException for path traversal with double dot`() {
        assertFailsWith<IllegalArgumentException> {
            storage.resolve("../outside/secret.txt")
        }
    }

    @Test
    fun `resolve throws IllegalArgumentException for deeply nested traversal`() {
        assertFailsWith<IllegalArgumentException> {
            storage.resolve("subdir/../../outside.jpg")
        }
    }

    @Test
    fun `exists returns false when file does not exist`() {
        val path = tempDir.resolve("missing.jpg")
        assertFalse(storage.exists(path))
    }

    @Test
    fun `exists returns true when file exists`() {
        val file = tempDir.resolve("present.jpg").toFile()
        file.writeBytes(byteArrayOf(1, 2, 3))
        assertTrue(storage.exists(tempDir.resolve("present.jpg")))
    }
}

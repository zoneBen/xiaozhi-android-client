package me.xiaozhi.androidclient.camera

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PhotoStoreTest {

    private fun tempDir(): File =
        File(System.getProperty("java.io.tmpdir"), "photostore-${System.nanoTime()}").apply { mkdirs() }

    /** 建一个 jpg，并把 mtime 设成指定值，好让"谁更新"可控。 */
    private fun photo(dir: File, name: String, modifiedAt: Long): File =
        File(dir, name).apply {
            writeBytes(byteArrayOf(1, 2, 3))
            setLastModified(modifiedAt)
        }

    @Test
    fun `photo file name carries the timestamp and the jpg suffix`() {
        assertEquals("photo_1700000000000.jpg", PhotoStore.photoFileName(1_700_000_000_000L))
    }

    @Test
    fun `two photos taken in the same millisecond still get different files only by timestamp`() {
        // 名字只靠时间戳区分，所以调用方必须用真实时钟；这里锁住这个前提，
        // 免得有人"优化"成固定名字把历史照片全盖掉。
        assertFalse(PhotoStore.photoFileName(1L) == PhotoStore.photoFileName(2L))
    }

    @Test
    fun `prune keeps the newest photos and deletes the rest`() {
        val dir = tempDir()
        photo(dir, "photo_1.jpg", 1_000L)
        photo(dir, "photo_2.jpg", 2_000L)
        photo(dir, "photo_3.jpg", 3_000L)

        assertEquals(1, PhotoStore.pruneOldPhotos(dir, keep = 2))

        assertFalse(File(dir, "photo_1.jpg").exists())
        assertTrue(File(dir, "photo_2.jpg").exists())
        assertTrue(File(dir, "photo_3.jpg").exists())
    }

    @Test
    fun `prune deletes nothing when the count is within the limit`() {
        val dir = tempDir()
        photo(dir, "photo_1.jpg", 1_000L)
        photo(dir, "photo_2.jpg", 2_000L)

        assertEquals(0, PhotoStore.pruneOldPhotos(dir, keep = 2))
        assertEquals(2, dir.listFiles().orEmpty().size)
    }

    @Test
    fun `prune ignores files that are not photos`() {
        val dir = tempDir()
        photo(dir, "photo_1.jpg", 1_000L)
        photo(dir, "photo_2.jpg", 2_000L)
        val note = File(dir, "notes.txt").apply { writeBytes(byteArrayOf(1)) }

        assertEquals(0, PhotoStore.pruneOldPhotos(dir, keep = 2))
        assertTrue("非 .jpg 的文件不该被当成照片裁掉", note.exists())
    }

    @Test
    fun `prune does not descend into subdirectories`() {
        val dir = tempDir()
        val nested = File(dir, "sub").apply { mkdirs() }
        photo(dir, "photo_1.jpg", 1_000L)
        photo(dir, "photo_2.jpg", 2_000L)
        File(nested, "photo_0.jpg").writeBytes(byteArrayOf(1))

        assertEquals(0, PhotoStore.pruneOldPhotos(dir, keep = 2))
        assertTrue(File(nested, "photo_0.jpg").exists())
    }

    @Test
    fun `prune on a directory that does not exist is a no-op`() {
        assertEquals(0, PhotoStore.pruneOldPhotos(File(tempDir(), "nope"), keep = 2))
    }

    @Test
    fun `keep zero deletes every photo`() {
        val dir = tempDir()
        photo(dir, "photo_1.jpg", 1_000L)
        photo(dir, "photo_2.jpg", 2_000L)

        assertEquals(2, PhotoStore.pruneOldPhotos(dir, keep = 0))
        assertEquals(0, dir.listFiles().orEmpty().size)
    }
}

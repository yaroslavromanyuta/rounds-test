package com.rounds.imageloader.cache

import com.rounds.imageloader.testing.FakeClock
import java.io.File
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Exercises the real file format against a temporary directory — no Android cache directory and no
 * mocking of the filesystem.
 */
class DiskImageCacheTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val clock = FakeClock(START_MILLIS)
    private val directory: File get() = temporaryFolder.root
    private val cache: DiskImageCache get() = DiskImageCache(directory, clock)

    @Test
    fun `stores and returns the original bytes and timestamp`() {
        cache.write(URL_A, BYTES_A, START_MILLIS)

        clock.advanceBy(ONE_HOUR_MILLIS)
        val entry = cache.read(URL_A)

        assertArrayEquals(BYTES_A, entry?.bytes)
        assertEquals(START_MILLIS, entry?.cachedAtMillis)
    }

    @Test
    fun `entry is still valid one millisecond before four hours`() {
        cache.write(URL_A, BYTES_A, START_MILLIS)

        clock.advanceBy(CACHE_TTL_MILLIS - 1)

        assertTrue(cache.read(URL_A) != null)
    }

    @Test
    fun `entry is expired at exactly four hours`() {
        cache.write(URL_A, BYTES_A, START_MILLIS)

        clock.advanceBy(CACHE_TTL_MILLIS)

        assertNull(cache.read(URL_A))
    }

    @Test
    fun `entry is expired one millisecond after four hours`() {
        cache.write(URL_A, BYTES_A, START_MILLIS)

        clock.advanceBy(CACHE_TTL_MILLIS + 1)

        assertNull(cache.read(URL_A))
    }

    @Test
    fun `an expired entry is deleted when encountered`() {
        cache.write(URL_A, BYTES_A, START_MILLIS)

        clock.advanceBy(CACHE_TTL_MILLIS)
        cache.read(URL_A)

        assertEquals(0, directory.listFiles()?.size)
    }

    @Test
    fun `an entry stamped in the future is a miss and is deleted`() {
        // A clock rollback between the write and the read: the file must not outlive the TTL just
        // because its age looks negative.
        cache.write(URL_A, BYTES_A, START_MILLIS + ONE_HOUR_MILLIS)

        assertNull(cache.read(URL_A))
        assertEquals(0, directory.listFiles()?.size)
    }

    @Test
    fun `an entry stamped one millisecond in the future is a miss`() {
        cache.write(URL_A, BYTES_A, START_MILLIS + 1)

        assertNull(cache.read(URL_A))
    }

    @Test
    fun `reading does not extend the time to live`() {
        cache.write(URL_A, BYTES_A, START_MILLIS)

        clock.advanceBy(ONE_HOUR_MILLIS)
        assertTrue(cache.read(URL_A) != null)
        clock.advanceBy(ONE_HOUR_MILLIS)
        assertTrue(cache.read(URL_A) != null)

        clock.currentMillis = START_MILLIS + CACHE_TTL_MILLIS
        assertNull(cache.read(URL_A))
    }

    @Test
    fun `a truncated entry is treated as a miss and removed`() {
        cache.write(URL_A, BYTES_A, START_MILLIS)
        val file = requireNotNull(directory.listFiles()).single()
        // Shorter than the 8-byte timestamp header - what a crash mid-write would leave behind if
        // the write were not atomic.
        file.writeBytes(byteArrayOf(1, 2, 3))

        assertNull(cache.read(URL_A))
        assertFalse(file.exists())
    }

    @Test
    fun `an entry with a header but no image bytes is treated as a miss`() {
        cache.write(URL_A, ByteArray(0), START_MILLIS)

        assertNull(cache.read(URL_A))
    }

    @Test
    fun `file names are digests, never the url`() {
        cache.write(URL_A, BYTES_A, START_MILLIS)

        val name = requireNotNull(directory.listFiles()).single().name

        assertTrue("unexpected cache file name: $name", name.matches(Regex("[0-9a-f]{64}")))
        assertFalse(name.contains("example"))
        assertEquals(CacheKey.of(URL_A), name)
    }

    @Test
    fun `different urls do not share an entry`() {
        cache.write(URL_A, BYTES_A, START_MILLIS)
        cache.write(URL_B, BYTES_B, START_MILLIS)

        assertNotEquals(CacheKey.of(URL_A), CacheKey.of(URL_B))
        assertArrayEquals(BYTES_A, cache.read(URL_A)?.bytes)
        assertArrayEquals(BYTES_B, cache.read(URL_B)?.bytes)
    }

    @Test
    fun `remove drops only the requested url`() {
        cache.write(URL_A, BYTES_A, START_MILLIS)
        cache.write(URL_B, BYTES_B, START_MILLIS)

        cache.remove(URL_A)

        assertNull(cache.read(URL_A))
        assertTrue(cache.read(URL_B) != null)
    }

    @Test
    fun `clear empties the directory`() {
        cache.write(URL_A, BYTES_A, START_MILLIS)
        cache.write(URL_B, BYTES_B, START_MILLIS)

        cache.clear()

        assertEquals(0, directory.listFiles()?.size)
        assertNull(cache.read(URL_A))
        assertNull(cache.read(URL_B))
    }

    @Test
    fun `a directory that cannot be used fails silently instead of throwing`() {
        // A plain file where a directory is expected: every operation must degrade to a miss.
        val notADirectory = temporaryFolder.newFile("blocked")
        val brokenCache = DiskImageCache(notADirectory, clock)

        brokenCache.write(URL_A, BYTES_A, START_MILLIS)

        assertNull(brokenCache.read(URL_A))
        brokenCache.remove(URL_A)
        brokenCache.clear()
    }

    private companion object {
        private const val START_MILLIS = 1_000_000L
        private const val ONE_HOUR_MILLIS = 60L * 60L * 1000L
        private const val URL_A = "https://example.test/a.png"
        private const val URL_B = "https://example.test/b.png"
        private val BYTES_A = "image-a".toByteArray()
        private val BYTES_B = "image-b-longer".toByteArray()
    }
}

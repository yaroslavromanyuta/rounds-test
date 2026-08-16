package com.rounds.imageloader.cache

import com.rounds.imageloader.testing.FakeClock
import java.io.File
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
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

    @Test
    fun `the default budget is exactly 128 MiB`() {
        assertEquals(134_217_728L, DiskImageCache.DEFAULT_MAX_SIZE_BYTES)
    }

    @Test
    fun `entries that exactly fill the budget are all kept`() {
        val cache = cacheWithBudget(2 * ENTRY_BYTES)

        cache.write(URL_A, payload(), START_MILLIS)
        clock.advanceBy(ONE_MINUTE_MILLIS)
        cache.write(URL_B, payload(), START_MILLIS)

        assertEquals(2 * ENTRY_BYTES, canonicalBytes())
        assertNotNull(cache.read(URL_A))
        assertNotNull(cache.read(URL_B))
    }

    @Test
    fun `a write past the budget evicts the least recently used entry`() {
        val cache = cacheWithBudget(2 * ENTRY_BYTES)
        cache.write(URL_A, payload(), START_MILLIS)
        clock.advanceBy(ONE_MINUTE_MILLIS)
        cache.write(URL_B, payload(), START_MILLIS)

        clock.advanceBy(ONE_MINUTE_MILLIS)
        cache.write(URL_C, payload(), START_MILLIS)

        assertTrue("cache grew past its budget: ${canonicalBytes()}", canonicalBytes() <= 2 * ENTRY_BYTES)
        assertNull(cache.read(URL_A))
        assertNotNull(cache.read(URL_B))
        assertNotNull(cache.read(URL_C))
    }

    @Test
    fun `a read makes an entry the most recently used one`() {
        val cache = cacheWithBudget(2 * ENTRY_BYTES)
        cache.write(URL_A, payload(), START_MILLIS)
        clock.advanceBy(ONE_MINUTE_MILLIS)
        cache.write(URL_B, payload(), START_MILLIS)

        // Reading A makes B the oldest, so B is what the next write must evict.
        clock.advanceBy(ONE_MINUTE_MILLIS)
        assertNotNull(cache.read(URL_A))
        clock.advanceBy(ONE_MINUTE_MILLIS)
        cache.write(URL_C, payload(), START_MILLIS)

        assertNotNull(cache.read(URL_A))
        assertNull(cache.read(URL_B))
        assertNotNull(cache.read(URL_C))
    }

    @Test
    fun `entries with the same access time are evicted in file name order`() {
        val cache = cacheWithBudget(2 * ENTRY_BYTES)
        // Written without advancing the clock, so both carry the same access time and only the
        // file name can decide.
        cache.write(URL_A, payload(), START_MILLIS)
        cache.write(URL_B, payload(), START_MILLIS)
        val first = listOf(URL_A, URL_B).minByOrNull(CacheKey::of)
        val second = listOf(URL_A, URL_B).maxByOrNull(CacheKey::of)
        // A filesystem with coarse timestamp granularity stores two accesses as one value; the file
        // name is then all that is left to order them by.
        val sharedStamp = File(directory, CacheKey.of(URL_A)).lastModified()
        assertTrue(File(directory, CacheKey.of(URL_B)).setLastModified(sharedStamp))

        clock.advanceBy(ONE_MINUTE_MILLIS)
        cache.write(URL_C, payload(), START_MILLIS)

        assertNull(cache.read(requireNotNull(first)))
        assertNotNull(cache.read(requireNotNull(second)))
    }

    @Test
    fun `replacing an entry counts only the file that survives`() {
        val cache = cacheWithBudget(2 * ENTRY_BYTES)
        cache.write(URL_A, payload(), START_MILLIS)
        clock.advanceBy(ONE_MINUTE_MILLIS)
        cache.write(URL_B, payload(), START_MILLIS)

        clock.advanceBy(ONE_MINUTE_MILLIS)
        cache.write(URL_A, payload(fill = 'z'.code.toByte()), START_MILLIS)

        // The replacement is one file, not the old one plus the new one, so B survives.
        assertEquals(2 * ENTRY_BYTES, canonicalBytes())
        assertArrayEquals(payload(fill = 'z'.code.toByte()), cache.read(URL_A)?.bytes)
        assertNotNull(cache.read(URL_B))
    }

    @Test
    fun `replacing an entry with a larger one evicts as much as it needs`() {
        val cache = cacheWithBudget(3 * ENTRY_BYTES)
        cache.write(URL_A, payload(), START_MILLIS)
        clock.advanceBy(ONE_MINUTE_MILLIS)
        cache.write(URL_B, payload(), START_MILLIS)
        clock.advanceBy(ONE_MINUTE_MILLIS)
        cache.write(URL_C, payload(), START_MILLIS)

        clock.advanceBy(ONE_MINUTE_MILLIS)
        cache.write(URL_A, payload(size = 2 * PAYLOAD_BYTES), START_MILLIS)

        assertTrue("cache grew past its budget: ${canonicalBytes()}", canonicalBytes() <= 3 * ENTRY_BYTES)
        assertNull(cache.read(URL_B))
        assertNotNull(cache.read(URL_A))
        assertNotNull(cache.read(URL_C))
    }

    @Test
    fun `replacing an entry with a smaller one evicts nothing`() {
        val cache = cacheWithBudget(3 * ENTRY_BYTES)
        cache.write(URL_A, payload(size = 2 * PAYLOAD_BYTES), START_MILLIS)
        clock.advanceBy(ONE_MINUTE_MILLIS)
        cache.write(URL_B, payload(), START_MILLIS)

        clock.advanceBy(ONE_MINUTE_MILLIS)
        cache.write(URL_A, payload(), START_MILLIS)

        assertNotNull(cache.read(URL_A))
        assertNotNull(cache.read(URL_B))
    }

    @Test
    fun `an entry larger than the whole budget is not stored and drops the previous one`() {
        val cache = cacheWithBudget(2 * ENTRY_BYTES)
        cache.write(URL_A, payload(), START_MILLIS)

        clock.advanceBy(ONE_MINUTE_MILLIS)
        cache.write(URL_A, payload(size = 4 * PAYLOAD_BYTES), START_MILLIS)

        // Neither the oversized image nor the previous one may be served as the current entry.
        assertNull(cache.read(URL_A))
        assertEquals(0L, canonicalBytes())
    }

    @Test
    fun `a cache that is already over budget is pruned on its first operation, not on construction`() {
        val existing = cacheWithBudget(4 * ENTRY_BYTES)
        existing.write(URL_A, payload(), START_MILLIS)
        clock.advanceBy(ONE_MINUTE_MILLIS)
        existing.write(URL_B, payload(), START_MILLIS)
        clock.advanceBy(ONE_MINUTE_MILLIS)
        existing.write(URL_C, payload(), START_MILLIS)

        // A build with a smaller budget opens the same directory: constructing it must not touch
        // the filesystem, because construction happens off the disk dispatcher.
        val shrunk = cacheWithBudget(ENTRY_BYTES)
        assertEquals(3 * ENTRY_BYTES, canonicalBytes())

        clock.advanceBy(ONE_MINUTE_MILLIS)
        assertNotNull(shrunk.read(URL_C))

        assertEquals(ENTRY_BYTES, canonicalBytes())
        assertNull(shrunk.read(URL_A))
        assertNull(shrunk.read(URL_B))
    }

    @Test
    fun `serving an entry does not rewrite its timestamp or extend its time to live`() {
        val cache = cacheWithBudget(2 * ENTRY_BYTES)
        cache.write(URL_A, payload(), START_MILLIS)

        clock.advanceBy(3 * ONE_HOUR_MILLIS)
        assertEquals(START_MILLIS, cache.read(URL_A)?.cachedAtMillis)

        // Four hours after the write, not after the read that made it most recently used.
        clock.currentMillis = START_MILLIS + CACHE_TTL_MILLIS
        assertNull(cache.read(URL_A))
    }

    @Test
    fun `pruning ignores response spools, write temporaries, unrelated files and directories`() {
        val spool = temporaryFolder.newFile("image-loader-response-active.tmp")
        // Another instance may be part-way through this write; age is no proof that it is debris.
        val temporary = temporaryFolder.newFile("imageloader1234567890.tmp")
        val unrelated = temporaryFolder.newFile("notes.txt")
        val nested = temporaryFolder.newFolder("0".repeat(64))
        listOf(spool, temporary, unrelated).forEach { file -> file.writeBytes(payload()) }
        val cache = cacheWithBudget(ENTRY_BYTES)
        cache.write(URL_A, payload(), START_MILLIS)
        clock.advanceBy(ONE_MINUTE_MILLIS)

        cache.write(URL_B, payload(), START_MILLIS)

        // Only the canonical entry was evicted; a download still spooling was not touched.
        assertNull(cache.read(URL_A))
        assertNotNull(cache.read(URL_B))
        assertTrue(spool.isFile)
        assertTrue(temporary.isFile)
        assertTrue(unrelated.isFile)
        assertTrue(nested.isDirectory)
    }

    @Test
    fun `an old write temporary is still left alone by pruning`() {
        val temporary = temporaryFolder.newFile("imageloader9876543210.tmp")
        temporary.writeBytes(payload())
        assertTrue(temporary.setLastModified(START_MILLIS - ONE_HOUR_MILLIS))
        val cache = cacheWithBudget(ENTRY_BYTES)
        cache.write(URL_A, payload(), START_MILLIS)

        clock.advanceBy(ONE_MINUTE_MILLIS)
        cache.write(URL_B, payload(), START_MILLIS)

        // A stalled 32 MiB write can look this old, and unlinking its pathname would break the
        // rename that finishes it. Age is not ownership, so pruning never touches temporaries.
        assertTrue(temporary.isFile)
        assertNull(cache.read(URL_A))
        assertNotNull(cache.read(URL_B))
    }

    @Test
    fun `two caches over one directory stay inside the shared budget`() {
        val first = cacheWithBudget(2 * ENTRY_BYTES)
        val second = cacheWithBudget(2 * ENTRY_BYTES)

        // Interleaved writers: without shared accounting the first cache still believes it holds
        // one entry and lets the directory reach three.
        first.write(URL_A, payload(), START_MILLIS)
        assertTrue(canonicalBytes() <= 2 * ENTRY_BYTES)
        clock.advanceBy(ONE_MINUTE_MILLIS)
        second.write(URL_B, payload(), START_MILLIS)
        assertTrue(canonicalBytes() <= 2 * ENTRY_BYTES)
        clock.advanceBy(ONE_MINUTE_MILLIS)
        first.write(URL_C, payload(), START_MILLIS)

        assertEquals(2 * ENTRY_BYTES, canonicalBytes())
        assertNull(first.read(URL_A))
        assertNotNull(second.read(URL_B))
        assertNotNull(first.read(URL_C))
    }

    @Test
    fun `a write after the clock rolls back is still the most recently used entry`() {
        val cache = cacheWithBudget(2 * ENTRY_BYTES)
        // Downloaded three hours ago, so a half-hour rollback below leaves them inside the TTL and
        // the test is about access order rather than about expiry.
        cache.write(URL_A, payload(), START_MILLIS - 3 * ONE_HOUR_MILLIS)
        clock.advanceBy(ONE_MINUTE_MILLIS)
        cache.write(URL_B, payload(), START_MILLIS - 3 * ONE_HOUR_MILLIS)

        // The device clock goes backwards - a manual change, or a network time correction.
        clock.currentMillis = START_MILLIS - 30 * ONE_MINUTE_MILLIS
        cache.write(URL_C, payload(), clock.currentMillis)

        // C is the newest access, so the oldest entry is evicted rather than C itself.
        assertEquals(2 * ENTRY_BYTES, canonicalBytes())
        assertNotNull(cache.read(URL_C))
        assertNull(cache.read(URL_A))
    }

    @Test
    fun `a read after the clock rolls back still promotes the entry it served`() {
        val cache = cacheWithBudget(2 * ENTRY_BYTES)
        cache.write(URL_A, payload(), START_MILLIS - 3 * ONE_HOUR_MILLIS)
        clock.advanceBy(ONE_MINUTE_MILLIS)
        cache.write(URL_B, payload(), START_MILLIS - 3 * ONE_HOUR_MILLIS)

        val stampBeforeRollback = File(directory, CacheKey.of(URL_B)).lastModified()

        clock.currentMillis = START_MILLIS - 30 * ONE_MINUTE_MILLIS
        assertNotNull(cache.read(URL_A))

        // Serving A recorded an access at least as recent as B's, rather than the rolled-back
        // clock, which would have put A first in line for eviction. Asserted on the recorded stamps
        // rather than on which file survives: consecutive accesses are a millisecond apart, and a
        // filesystem with second granularity would store them as the same value.
        assertTrue(File(directory, CacheKey.of(URL_A)).lastModified() >= stampBeforeRollback)
        assertNotNull(cache.read(URL_A))
    }

    @Test
    fun `files that only look like cache entries are neither counted nor evicted`() {
        val lookalikes = listOf(
            "A".repeat(64),
            "a".repeat(63),
            "a".repeat(65),
            "g".repeat(64),
        ).map { name -> File(directory, name).apply { writeBytes(payload()) } }
        val cache = cacheWithBudget(ENTRY_BYTES)

        cache.write(URL_A, payload(), START_MILLIS)

        assertNotNull(cache.read(URL_A))
        lookalikes.forEach { file -> assertTrue("evicted ${file.name}", file.isFile) }
    }

    @Test
    fun `peeking at an entry does not make it recently used`() {
        val cache = cacheWithBudget(2 * ENTRY_BYTES)
        cache.write(URL_A, payload(), START_MILLIS)
        clock.advanceBy(ONE_MINUTE_MILLIS)
        cache.write(URL_B, payload(), START_MILLIS)

        // Inspecting the bytes is not serving them, so A stays the eviction candidate.
        clock.advanceBy(ONE_MINUTE_MILLIS)
        assertArrayEquals(payload(), cache.peek(URL_A)?.bytes)
        clock.advanceBy(ONE_MINUTE_MILLIS)
        cache.write(URL_C, payload(), START_MILLIS)

        assertNull(cache.read(URL_A))
        assertNotNull(cache.read(URL_B))
    }

    @Test
    fun `clearing the cache resets the budget accounting`() {
        val cache = cacheWithBudget(2 * ENTRY_BYTES)
        cache.write(URL_A, payload(), START_MILLIS)
        clock.advanceBy(ONE_MINUTE_MILLIS)
        cache.write(URL_B, payload(), START_MILLIS)

        cache.clear()
        listOf(URL_C, URL_A, URL_B).forEach { url ->
            clock.advanceBy(ONE_MINUTE_MILLIS)
            cache.write(url, payload(), START_MILLIS)
        }

        assertTrue("cache grew past its budget: ${canonicalBytes()}", canonicalBytes() <= 2 * ENTRY_BYTES)
        assertNotNull(cache.read(URL_B))
    }

    @Test
    fun `a deletion outside the budget accounting is noticed by the next write`() {
        val cache = cacheWithBudget(2 * ENTRY_BYTES)
        cache.write(URL_A, payload(), START_MILLIS)
        clock.advanceBy(ONE_MINUTE_MILLIS)
        cache.write(URL_B, payload(), START_MILLIS)
        // Android reclaiming the cache directory, or another process deleting a file underneath us.
        assertTrue(File(directory, CacheKey.of(URL_A)).delete())

        clock.advanceBy(ONE_MINUTE_MILLIS)
        cache.write(URL_C, payload(), START_MILLIS)

        // Two entries fit, so the running total must not have counted the file that vanished.
        assertNotNull(cache.read(URL_B))
        assertNotNull(cache.read(URL_C))
    }

    private fun cacheWithBudget(maxSizeBytes: Long) = DiskImageCache(directory, clock, maxSizeBytes)

    /** Sum of the canonical entry files, which is what the budget applies to. */
    private fun canonicalBytes(): Long =
        directory.listFiles()
            ?.filter { file -> file.isFile && file.name.matches(Regex("[0-9a-f]{64}")) }
            ?.sumOf { file -> file.length() }
            ?: 0L

    private fun payload(size: Int = PAYLOAD_BYTES, fill: Byte = 'x'.code.toByte()) =
        ByteArray(size) { fill }

    private companion object {
        // A recent wall-clock instant, because the LRU tests write it to real file metadata and not
        // every filesystem can represent a timestamp from 1970.
        private const val START_MILLIS = 1_700_000_000_000L
        private const val ONE_HOUR_MILLIS = 60L * 60L * 1000L
        private const val ONE_MINUTE_MILLIS = 60L * 1000L
        private const val PAYLOAD_BYTES = 64
        /** What one [payload] costs on disk: the 8-byte timestamp header plus its bytes. */
        private const val ENTRY_BYTES = 8L + PAYLOAD_BYTES
        private const val URL_A = "https://example.test/a.png"
        private const val URL_B = "https://example.test/b.png"
        private const val URL_C = "https://example.test/c.png"
        private val BYTES_A = "image-a".toByteArray()
        private val BYTES_B = "image-b-longer".toByteArray()
    }
}

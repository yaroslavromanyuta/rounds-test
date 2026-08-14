package com.rounds.imageloader.cache

import android.graphics.Bitmap
import com.rounds.imageloader.testing.FakeClock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito

/**
 * Covers this cache's own policy — expiry, timestamp handling and the size bound — rather than the
 * `LruCache` it delegates to.
 */
class MemoryImageCacheTest {

    private val clock = FakeClock(START_MILLIS)
    private val cache = MemoryImageCache(clock, maxSizeBytes = MAX_SIZE_BYTES)

    @Test
    fun `returns a fresh entry with its original timestamp`() {
        val bitmap = bitmap()
        cache.put(URL_A, bitmap, START_MILLIS)

        clock.advanceBy(ONE_HOUR_MILLIS)
        val cached = cache.get(URL_A)

        assertSame(bitmap, cached?.bitmap)
        assertEquals(START_MILLIS, cached?.cachedAtMillis)
    }

    @Test
    fun `entry is still valid one millisecond before four hours`() {
        cache.put(URL_A, bitmap(), START_MILLIS)

        clock.advanceBy(CACHE_TTL_MILLIS - 1)

        assertTrue(cache.get(URL_A) != null)
    }

    @Test
    fun `entry is expired at exactly four hours`() {
        cache.put(URL_A, bitmap(), START_MILLIS)

        clock.advanceBy(CACHE_TTL_MILLIS)

        assertNull(cache.get(URL_A))
    }

    @Test
    fun `entry is expired one millisecond after four hours`() {
        cache.put(URL_A, bitmap(), START_MILLIS)

        clock.advanceBy(CACHE_TTL_MILLIS + 1)

        assertNull(cache.get(URL_A))
    }

    @Test
    fun `an expired entry is evicted rather than merely hidden`() {
        cache.put(URL_A, bitmap(), START_MILLIS)

        clock.advanceBy(CACHE_TTL_MILLIS)
        cache.get(URL_A)

        assertEquals(0, cache.sizeBytes())
    }

    @Test
    fun `reading does not extend the time to live`() {
        cache.put(URL_A, bitmap(), START_MILLIS)

        clock.advanceBy(ONE_HOUR_MILLIS)
        assertTrue(cache.get(URL_A) != null)
        clock.advanceBy(ONE_HOUR_MILLIS)
        assertTrue(cache.get(URL_A) != null)

        // Four hours after the original write, not after the last read.
        clock.currentMillis = START_MILLIS + CACHE_TTL_MILLIS
        assertNull(cache.get(URL_A))
    }

    @Test
    fun `stays within its size bound and evicts the least recently used entry`() {
        // Three bitmaps of 100 bytes each into a 250 byte cache.
        cache.put(URL_A, bitmap(), START_MILLIS)
        cache.put(URL_B, bitmap(), START_MILLIS)
        cache.put(URL_C, bitmap(), START_MILLIS)

        assertTrue("cache grew past its bound: ${cache.sizeBytes()}", cache.sizeBytes() <= MAX_SIZE_BYTES)
        assertNull(cache.get(URL_A))
        assertTrue(cache.get(URL_C) != null)
    }

    @Test
    fun `remove drops only the requested url`() {
        cache.put(URL_A, bitmap(), START_MILLIS)
        cache.put(URL_B, bitmap(), START_MILLIS)

        cache.remove(URL_A)

        assertNull(cache.get(URL_A))
        assertTrue(cache.get(URL_B) != null)
    }

    @Test
    fun `clear drops everything`() {
        cache.put(URL_A, bitmap(), START_MILLIS)
        cache.put(URL_B, bitmap(), START_MILLIS)

        cache.clear()

        assertNull(cache.get(URL_A))
        assertNull(cache.get(URL_B))
        assertEquals(0, cache.sizeBytes())
    }

    private fun bitmap(byteCount: Int = BITMAP_BYTES): Bitmap {
        val bitmap = Mockito.mock(Bitmap::class.java)
        Mockito.`when`(bitmap.byteCount).thenReturn(byteCount)
        return bitmap
    }

    private companion object {
        private const val START_MILLIS = 1_000_000L
        private const val ONE_HOUR_MILLIS = 60L * 60L * 1000L
        private const val BITMAP_BYTES = 100
        private const val MAX_SIZE_BYTES = 250
        private const val URL_A = "https://example.test/a.png"
        private const val URL_B = "https://example.test/b.png"
        private const val URL_C = "https://example.test/c.png"
    }
}

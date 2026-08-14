package com.rounds.imageloader.cache

import android.graphics.Bitmap
import androidx.collection.LruCache

/**
 * Bounded in-memory bitmap cache.
 *
 * Bounded rather than a plain map: bitmaps are the largest objects this library handles, and an
 * unbounded cache is an out-of-memory crash waiting for a long enough list. Capacity defaults to an
 * eighth of the process heap and entries are measured by their actual pixel cost, so eviction
 * reflects real memory pressure rather than entry count.
 *
 * `androidx.collection.LruCache` is used instead of `android.util.LruCache` because the latter is a
 * framework stub in JVM unit tests; the androidx one is ordinary Java and synchronises internally.
 */
internal class MemoryImageCache(
    private val clock: Clock,
    maxSizeBytes: Int = defaultMaxSizeBytes(),
) {

    private class Entry(val bitmap: Bitmap, val cachedAtMillis: Long)

    private val entries = object : LruCache<String, Entry>(maxSizeBytes) {
        override fun sizeOf(key: String, value: Entry): Int = maxOf(1, value.bitmap.byteCount)
    }

    /** Returns the entry with its original timestamp, dropping it if the TTL has passed. */
    fun get(url: String): CachedBitmap? {
        val entry = entries.get(url) ?: return null
        if (!isCacheEntryValid(entry.cachedAtMillis, clock.nowMillis())) {
            entries.remove(url)
            return null
        }
        return CachedBitmap(entry.bitmap, entry.cachedAtMillis)
    }

    /**
     * [cachedAtMillis] is supplied by the caller rather than read from the clock here: an entry
     * promoted from disk must keep the timestamp it was originally downloaded with, otherwise a
     * disk hit would silently restart the four-hour window.
     */
    fun put(url: String, bitmap: Bitmap, cachedAtMillis: Long) {
        entries.put(url, Entry(bitmap, cachedAtMillis))
    }

    fun remove(url: String) {
        entries.remove(url)
    }

    fun clear() {
        entries.evictAll()
    }

    /** Current cost of the cached bitmaps in bytes. Used by tests to prove the bound holds. */
    fun sizeBytes(): Int = entries.size()

    private companion object {

        private const val HEAP_FRACTION = 8

        fun defaultMaxSizeBytes(): Int =
            (Runtime.getRuntime().maxMemory() / HEAP_FRACTION)
                .coerceIn(1L, Int.MAX_VALUE.toLong())
                .toInt()
    }
}

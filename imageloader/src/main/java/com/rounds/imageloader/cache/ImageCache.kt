package com.rounds.imageloader.cache

import android.graphics.Bitmap
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext

/** A cached bitmap together with the moment it was originally stored. */
internal class CachedBitmap(val bitmap: Bitmap, val cachedAtMillis: Long)

/** Cached encoded bytes together with the moment they were originally stored. */
internal class CachedBytes(val bytes: ByteArray, val cachedAtMillis: Long)

/**
 * Identity of the cache state a request observed when it started. Used to reject writes from
 * requests that were overtaken by an invalidation.
 *
 * A `data class` because it is also half of an in-flight request's identity: two loads may share
 * one download only if they observed the same cache state, which is a value comparison.
 */
internal data class CacheSnapshot(val global: Long, val key: Long)

/**
 * Coordinates the two cache tiers and owns invalidation semantics.
 *
 * Memory operations are synchronous — an `LruCache` lookup is cheap enough for the main thread, and
 * doing it synchronously means a warm cache paints without a placeholder flicker. Every disk
 * operation is suspending and confined to [diskDispatcher].
 *
 * Two mechanisms keep invalidation deterministic against loads that are already running:
 *
 * 1. **Generations.** A request takes a [snapshot] when it starts and passes it back when storing.
 *    `clearCache()` bumps the global generation and `invalidate(url)` bumps that URL's, so a
 *    request that was already in flight when the cache was invalidated cannot repopulate it. The
 *    same snapshot identifies shared in-flight work, so a load started after an invalidation
 *    cannot join a load started before it.
 * 2. **A single-threaded disk dispatcher.** Disk work is FIFO, so a deletion queued by an
 *    invalidation always completes before a read queued after it — a pending delete can never let a
 *    stale file be served.
 */
internal class ImageCache(
    private val memory: MemoryImageCache,
    private val disk: DiskImageCache,
    private val diskDispatcher: CoroutineDispatcher,
) {

    private val globalGeneration = AtomicLong()
    private val keyGenerations = ConcurrentHashMap<String, Long>()

    fun snapshot(url: String): CacheSnapshot =
        CacheSnapshot(globalGeneration.get(), keyGenerations[url] ?: INITIAL_GENERATION)

    fun getFromMemory(url: String): CachedBitmap? = memory.get(url)

    suspend fun readFromDisk(url: String): CachedBytes? = withContext(diskDispatcher) {
        disk.read(url)
    }

    /**
     * Stores the bitmap unless the URL was invalidated since [snapshot] was taken.
     *
     * [cachedAtMillis] is always the timestamp of the original successful download — on promotion
     * from disk it is the disk entry's own timestamp, never "now".
     */
    fun putInMemory(url: String, bitmap: Bitmap, cachedAtMillis: Long, snapshot: CacheSnapshot) {
        if (isCurrent(url, snapshot)) memory.put(url, bitmap, cachedAtMillis)
    }

    suspend fun putOnDisk(
        url: String,
        bytes: ByteArray,
        cachedAtMillis: Long,
        snapshot: CacheSnapshot,
    ) {
        if (!isCurrent(url, snapshot)) return
        withContext(diskDispatcher) { disk.write(url, bytes, cachedAtMillis) }
    }

    /** Drops corrupt bytes only if the on-disk entry is still exactly the one that was decoded. */
    suspend fun dropDiskEntry(url: String, expected: CachedBytes, snapshot: CacheSnapshot) {
        withContext(diskDispatcher) {
            if (!isCurrent(url, snapshot)) return@withContext
            val current = disk.read(url) ?: return@withContext
            if (
                current.cachedAtMillis == expected.cachedAtMillis &&
                current.bytes.contentEquals(expected.bytes)
            ) {
                disk.remove(url)
            }
        }
    }

    /** Immediate half of `invalidate(url)`: the URL stops being served from memory at once. */
    fun invalidate(url: String) {
        // compute rather than get-then-put: the bump has to be atomic, or two concurrent
        // invalidations of the same url could lose one and leave a snapshot looking current.
        keyGenerations.compute(url) { _, current -> (current ?: INITIAL_GENERATION) + 1 }
        memory.remove(url)
    }

    suspend fun invalidateOnDisk(url: String) {
        withContext(diskDispatcher) { disk.remove(url) }
    }

    /** Immediate half of `clearCache()`: nothing is served from memory afterwards. */
    fun clear() {
        globalGeneration.incrementAndGet()
        keyGenerations.clear()
        memory.clear()
    }

    suspend fun clearDisk() {
        withContext(diskDispatcher) { disk.clear() }
    }

    private fun isCurrent(url: String, snapshot: CacheSnapshot): Boolean =
        snapshot.global == globalGeneration.get() &&
            snapshot.key == (keyGenerations[url] ?: INITIAL_GENERATION)

    private companion object {
        private const val INITIAL_GENERATION = 0L
    }
}

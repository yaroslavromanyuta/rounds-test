package com.rounds.imageloader.cache

import android.graphics.Bitmap
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
 *
 * Checking the generation and acting on the answer has to be **one transaction**, or the check
 * proves nothing: a request could pass it, be overtaken by an invalidation, and then store bytes the
 * invalidation was meant to drop. The two tiers get that guarantee from different mechanisms,
 * because they are mutated on different threads:
 *
 * - **Memory** is mutated synchronously by whichever thread is loading or invalidating, so the check
 *   and the `put` are performed under [generationLock], the same monitor [invalidate] and [clear]
 *   hold while they bump a generation and drop entries. Nothing suspends or touches the filesystem
 *   under that monitor; it is only ever held across an `LruCache` operation.
 * - **Disk** is mutated only on [diskDispatcher], so the check is performed *inside* the dispatched
 *   operation that writes, immediately before the write. An invalidation that has already bumped the
 *   generation is seen by that check; one that has not yet bumped it cannot have submitted its
 *   deletion either, and since deletion is submitted before the public invalidation call returns,
 *   that deletion is queued behind this write and still removes what the write stores.
 */
internal class ImageCache(
    private val memory: MemoryImageCache,
    private val disk: DiskImageCache,
    private val diskDispatcher: CoroutineDispatcher,
) {

    /**
     * Guards the generations *and* the memory-cache mutations that depend on them, so a caller can
     * never observe a generation that another thread has already invalidated by the time it writes.
     */
    private val generationLock = Any()

    private var globalGeneration = INITIAL_GENERATION
    private val keyGenerations = mutableMapOf<String, Long>()

    /** Both halves are read together, so a snapshot is never half of one state and half of another. */
    fun snapshot(url: String): CacheSnapshot = synchronized(generationLock) {
        CacheSnapshot(globalGeneration, keyGeneration(url))
    }

    fun getFromMemory(url: String): CachedBitmap? = memory.get(url)

    suspend fun readFromDisk(url: String): CachedBytes? = withContext(diskDispatcher) {
        disk.read(url)
    }

    /**
     * Stores the bitmap unless the URL was invalidated since [snapshot] was taken.
     *
     * The check and the store are one transaction against [invalidate] and [clear]: an invalidation
     * either happens entirely before this call — and is seen by the check — or entirely after it,
     * and then removes what was stored here. It can never land in between.
     *
     * [cachedAtMillis] is always the timestamp of the original successful download — on promotion
     * from disk it is the disk entry's own timestamp, never "now".
     */
    fun putInMemory(url: String, bitmap: Bitmap, cachedAtMillis: Long, snapshot: CacheSnapshot) {
        synchronized(generationLock) {
            if (!isCurrent(url, snapshot)) return
            memory.put(url, bitmap, cachedAtMillis)
        }
    }

    /**
     * Stores the encoded bytes unless the URL was invalidated since [snapshot] was taken.
     *
     * The check runs on [diskDispatcher] rather than before the switch to it, so the write is
     * authorised inside the same serialised disk operation that performs it. Deciding earlier would
     * let an invalidation delete the entry and then be overtaken by this already-authorised write,
     * resurrecting the bytes it had just removed.
     */
    suspend fun putOnDisk(
        url: String,
        bytes: ByteArray,
        cachedAtMillis: Long,
        snapshot: CacheSnapshot,
    ) {
        withContext(diskDispatcher) {
            if (!isCurrent(url, snapshot)) return@withContext
            disk.write(url, bytes, cachedAtMillis)
        }
    }

    /** Drops corrupt bytes only if the on-disk entry is still exactly the one that was decoded. */
    suspend fun dropDiskEntry(url: String, expected: CachedBytes, snapshot: CacheSnapshot) {
        withContext(diskDispatcher) {
            if (!isCurrent(url, snapshot)) return@withContext
            // The identity check and the deletion belong together, and neither may promote the
            // entry: confirming that a corrupt file is still there must not make it the most
            // recently used one and let it outlive good entries under the disk budget.
            disk.removeIfUnchanged(url, expected)
        }
    }

    /** Immediate half of `invalidate(url)`: the URL stops being served from memory at once. */
    fun invalidate(url: String) {
        // Under the lock, so the bump and the removal are one transaction both against another
        // invalidation — two concurrent ones must not lose a bump and leave a snapshot looking
        // current — and against a load storing into memory with a pre-invalidation snapshot.
        synchronized(generationLock) {
            keyGenerations[url] = keyGeneration(url) + 1
            memory.remove(url)
        }
    }

    suspend fun invalidateOnDisk(url: String) {
        withContext(diskDispatcher) { disk.remove(url) }
    }

    /** Immediate half of `clearCache()`: nothing is served from memory afterwards. */
    fun clear() {
        synchronized(generationLock) {
            globalGeneration++
            keyGenerations.clear()
            memory.clear()
        }
    }

    suspend fun clearDisk() {
        withContext(diskDispatcher) { disk.clear() }
    }

    private fun isCurrent(url: String, snapshot: CacheSnapshot): Boolean =
        synchronized(generationLock) {
            snapshot.global == globalGeneration && snapshot.key == keyGeneration(url)
        }

    /** Call under [generationLock]. */
    private fun keyGeneration(url: String): Long = keyGenerations[url] ?: INITIAL_GENERATION

    private companion object {
        private const val INITIAL_GENERATION = 0L
    }
}

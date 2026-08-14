package com.rounds.imageloader.internal

import android.graphics.Bitmap
import com.rounds.imageloader.cache.CacheSnapshot
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async

/**
 * Identity of a shared image-producing operation.
 *
 * The cache generation is part of the identity, not just the URL: a load that starts after
 * `invalidate(url)` or `clearCache()` observed a different cache state, and must not be served by
 * work that started before it — that would quietly undo the invalidation the caller just asked for.
 */
internal data class InFlightKey(val url: String, val cacheGeneration: CacheSnapshot)

/**
 * Keeps one shared operation per [InFlightKey] so concurrent cache misses for the same image
 * download and decode it once.
 *
 * The shared unit is the *image-producing operation*, not the target request. Each target keeps its
 * own token, placeholder, cancellation and stale-result check, and merely awaits the result:
 *
 * ```text
 * loader scope
 *    └── shared load A
 *
 * target request #1 ──await──┐
 *                            ├── shared load A
 * target request #2 ──await──┘
 * ```
 *
 * Operations are launched in [scope] — the loader's own, long-lived scope — never as a child of the
 * consumer that happened to create them. Awaiting establishes no parent/child link, so cancelling
 * one consumer cancels only that consumer's wait; work another consumer still needs survives.
 *
 * Synchronisation is a [ConcurrentHashMap] rather than a `Mutex`: `computeIfAbsent` makes
 * lookup-or-create atomic, and the two-argument `remove` makes cleanup atomic and identity-checked
 * without needing a coroutine to take a lock from a completion handler. Only registry bookkeeping
 * is ever synchronised — the coroutine is created lazily and started outside the map operation, so
 * no download or decode runs while the map is held, and distinct keys never serialise.
 */
internal class InFlightRequestRegistry(private val scope: CoroutineScope) {

    private val inFlight = ConcurrentHashMap<InFlightKey, Deferred<Bitmap?>>()

    /**
     * Returns the operation producing the image for [key], starting one from [produce] if none is
     * running. [produce] is ignored when an existing operation is joined — every caller for a given
     * key observed the same cache state, so their producers are interchangeable by construction.
     *
     * The result is `null` when the image could not be produced. Failures are deliberately not
     * propagated to consumers as exceptions: a failed shared load is the same outcome as an
     * undecodable payload, and turning it into a throw would make one consumer's error handling
     * depend on whether it created the operation or joined it.
     */
    fun sharedLoad(key: InFlightKey, produce: suspend () -> Bitmap?): Deferred<Bitmap?> {
        var created: Deferred<Bitmap?>? = null
        val shared = inFlight.computeIfAbsent(key) {
            scope.async(start = CoroutineStart.LAZY) {
                try {
                    produce()
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (failure: Exception) {
                    // Same failures the single-request path already tolerates: I/O error,
                    // non-success status, empty body, undecodable payload.
                    null
                }
            }.also { created = it }
        }
        // Only the creator registers cleanup, and removal is identity-checked, so a late completion
        // can never evict a newer entry that has already taken this key. Registered before the
        // operation can run - it is started below - so no completion is missed.
        created?.invokeOnCompletion { inFlight.remove(key, shared) }
        shared.start()
        return shared
    }

    /** Number of operations currently registered. Exists so tests can prove entries are released. */
    fun inFlightCount(): Int = inFlight.size
}

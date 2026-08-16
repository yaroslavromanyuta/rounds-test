package com.rounds.imageloader.internal

import android.graphics.Bitmap
import com.rounds.imageloader.cache.CacheSnapshot
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
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
 * work that started before it.
 */
internal data class InFlightKey(val url: String, val cacheGeneration: CacheSnapshot)

/**
 * Keeps one shared operation per [InFlightKey] while at least one target request is subscribed.
 *
 * Operations run in the loader-owned [scope]. Cancelling one consumer only releases that
 * subscription; the operation survives while another consumer remains. Releasing the final
 * subscription removes and cancels the operation, so recycled targets cannot leave an unbounded
 * backlog waiting for a load-limiter permit.
 */
internal class InFlightRequestRegistry(private val scope: CoroutineScope) {

    private class SharedOperation(
        val deferred: Deferred<Bitmap?>,
        val consumers: AtomicInteger = AtomicInteger(1),
    )

    internal class Subscription(
        private val deferred: Deferred<Bitmap?>,
        private val releaseAction: () -> Unit,
    ) {
        private val released = AtomicBoolean()

        suspend fun await(): Bitmap? = deferred.await()

        fun release() {
            if (released.compareAndSet(false, true)) releaseAction()
        }

        val isActive: Boolean
            get() = deferred.isActive
    }

    private val inFlight = ConcurrentHashMap<InFlightKey, SharedOperation>()

    /**
     * Subscribes to the operation for [key], creating it from [produce] when necessary.
     *
     * [Subscription.release] must run in a `finally` block. Failures become a `null` result so one
     * consumer's error handling never depends on whether it created or joined the operation.
     */
    fun sharedLoad(key: InFlightKey, produce: suspend () -> Bitmap?): Subscription {
        var created: SharedOperation? = null
        val operation = inFlight.compute(key) { _, existing ->
            if (existing != null) {
                existing.consumers.incrementAndGet()
                existing
            } else {
                SharedOperation(
                    deferred = scope.async(start = CoroutineStart.LAZY) {
                        try {
                            produce()
                        } catch (cancellation: CancellationException) {
                            throw cancellation
                        } catch (failure: Exception) {
                            null
                        }
                    },
                ).also { created = it }
            }
        } ?: error("compute must return an operation")

        created?.deferred?.invokeOnCompletion { inFlight.remove(key, operation) }
        operation.deferred.start()
        return Subscription(operation.deferred) { release(key, operation) }
    }

    private fun release(key: InFlightKey, operation: SharedOperation) {
        var cancelOperation = false
        inFlight.computeIfPresent(key) { _, current ->
            if (current !== operation) {
                current
            } else if (operation.consumers.decrementAndGet() == 0) {
                cancelOperation = true
                null
            } else {
                operation
            }
        }
        if (cancelOperation) operation.deferred.cancel()
    }

    /** Number of operations currently registered. Exists so tests can prove entries are released. */
    fun inFlightCount(): Int = inFlight.size
}

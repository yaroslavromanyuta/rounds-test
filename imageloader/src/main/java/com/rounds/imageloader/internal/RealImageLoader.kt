package com.rounds.imageloader.internal

import android.content.Context
import android.graphics.Bitmap
import android.widget.ImageView
import androidx.annotation.DrawableRes
import com.rounds.imageloader.ImageLoader
import com.rounds.imageloader.cache.CacheSnapshot
import com.rounds.imageloader.cache.Clock
import com.rounds.imageloader.cache.DiskImageCache
import com.rounds.imageloader.cache.ImageCache
import com.rounds.imageloader.cache.MemoryImageCache
import com.rounds.imageloader.decode.BitmapFactoryImageDecoder
import com.rounds.imageloader.decode.ImageDecoder
import com.rounds.imageloader.network.HttpImageDownloader
import com.rounds.imageloader.network.ImageDownloader
import com.rounds.imageloader.request.ImageViewTarget
import com.rounds.imageloader.request.Target
import com.rounds.imageloader.request.TargetRequest
import java.io.File
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Default [ImageLoader] implementation.
 *
 * Coroutine model: the loader owns one [CoroutineScope] built on a [SupervisorJob], so a failed or
 * cancelled request never takes down unrelated ones. The scope lives as long as the loader, which is
 * expected to be a process-wide singleton — it holds no Activity, Fragment or view of its own.
 * Downloading and decoding run on [ioDispatcher]; every view mutation happens on the main
 * dispatcher the scope is confined to. `GlobalScope` is never used, and callers never see a scope,
 * a dispatcher or a suspending function.
 *
 * There are two kinds of coroutine here, and keeping them apart is what makes concurrent loads
 * correct. A **target request** is per-`ImageView`: it owns the token, the placeholder and the
 * cancellation, and it only ever awaits a result. A **shared load** is per URL-and-cache-generation,
 * lives in [InFlightRequestRegistry] on this loader's scope, and owns the download, the decode and
 * the cache write. Several target requests can await one shared load; cancelling one of them leaves
 * the others — and the shared load — untouched.
 *
 * Collaborators are constructor parameters with production defaults so tests can substitute the
 * network, the decoder, the cache, the clock and both dispatchers without any timing-sensitive
 * setup.
 */
internal class RealImageLoader(
    private val cache: ImageCache,
    private val downloader: ImageDownloader = HttpImageDownloader(),
    private val decoder: ImageDecoder = BitmapFactoryImageDecoder(),
    private val clock: Clock = Clock.SYSTEM,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    mainDispatcher: CoroutineDispatcher = Dispatchers.Main.immediate,
) : ImageLoader {

    private val scope = CoroutineScope(SupervisorJob() + mainDispatcher)
    private val tokens = AtomicLong()
    private val inFlight = InFlightRequestRegistry(scope)

    override fun load(url: String, @DrawableRes placeholderRes: Int, target: ImageView) {
        load(ImageViewTarget(target), url, placeholderRes)
    }

    override fun load(url: String, target: ImageView) {
        load(ImageViewTarget(target), url, ImageLoader.NO_PLACEHOLDER)
    }

    override fun clear(target: ImageView) {
        clear(ImageViewTarget(target))
    }

    override fun clearCache() {
        // Memory is emptied and the generation bumped synchronously, so the cache is logically
        // empty the moment this returns; only the file deletion is deferred to the disk dispatcher.
        cache.clear()
        scope.launch { cache.clearDisk() }
    }

    override fun invalidate(url: String) {
        cache.invalidate(url)
        scope.launch { cache.invalidateOnDisk(url) }
    }

    /**
     * Applies the placeholder synchronously — before returning, so the caller never sees a stale
     * image — then starts the request. [ImageLoader.NO_PLACEHOLDER] empties the target instead.
     *
     * The token identifying this request is published to the target before the coroutine starts, so
     * the completion check below can never observe a target that has not yet been claimed.
     */
    internal fun load(target: Target, url: String, @DrawableRes placeholderRes: Int) {
        clear(target)
        target.setPlaceholder(placeholderRes)
        if (url.isBlank()) return

        val token = tokens.incrementAndGet()
        val job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                // Memory, then disk, then network. The snapshot is taken before any of it, so an
                // invalidation arriving while this request runs can reject its cache write — and,
                // because it is half of the in-flight key, a request that starts after that
                // invalidation cannot be served by work that started before it.
                val snapshot = cache.snapshot(url)

                val fromMemory = cache.getFromMemory(url)
                if (fromMemory != null) {
                    applyIfCurrent(target, token, fromMemory.bitmap)
                    return@launch
                }

                val fromDisk = cache.readFromDisk(url)
                if (fromDisk != null) {
                    val bitmap = withContext(ioDispatcher) { decoder.decode(fromDisk.bytes) }
                    ensureActive()
                    if (bitmap != null) {
                        // Promotion keeps the disk entry's own timestamp: a disk hit must not
                        // restart the four-hour window.
                        cache.putInMemory(url, bitmap, fromDisk.cachedAtMillis, snapshot)
                        applyIfCurrent(target, token, bitmap)
                        return@launch
                    }
                    // Stored bytes are not a decodable image — drop them and fall through.
                    cache.dropDiskEntry(url)
                }

                // A complete miss is the only path that reaches the network, so it is the only one
                // worth sharing. The producer below runs once per key however many targets await
                // it; a target that joins an existing load contributes nothing but its own wait.
                val bitmap = inFlight.sharedLoad(InFlightKey(url, snapshot)) {
                    val bytes = withContext(ioDispatcher) { downloader.download(url) }
                    val decoded = withContext(ioDispatcher) { decoder.decode(bytes) }
                    // Only a successful download *and* decode is worth caching. Storing here rather
                    // than in each consumer means one write per result, not one per target.
                    if (decoded != null) storeBestEffort(url, bytes, decoded, snapshot)
                    decoded
                }.await()
                ensureActive()
                if (bitmap != null) applyIfCurrent(target, token, bitmap)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Exception) {
                // Expected failure — malformed URL, I/O error, non-success status, empty body or
                // undecodable payload. The placeholder stays; the host application must not crash.
            } finally {
                if (target.currentRequest()?.token == token) target.setCurrentRequest(null)
            }
        }
        target.setCurrentRequest(TargetRequest(token, job))
        job.start()
    }

    /**
     * Stores a freshly downloaded image in both tiers, sharing the one timestamp taken here.
     *
     * Caching is best effort and this is where that is enforced: a storage failure must not become
     * a load failure for every target awaiting this result. Cancellation is not a storage failure
     * and is left to propagate.
     */
    private suspend fun storeBestEffort(
        url: String,
        bytes: ByteArray,
        bitmap: Bitmap,
        snapshot: CacheSnapshot,
    ) {
        try {
            val cachedAtMillis = clock.nowMillis()
            cache.putInMemory(url, bitmap, cachedAtMillis, snapshot)
            cache.putOnDisk(url, bytes, cachedAtMillis, snapshot)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Exception) {
            // Unwritable cache directory, full disk, revoked permission. The image is still shown.
        }
    }

    internal fun clear(target: Target) {
        target.currentRequest()?.let { request ->
            request.job.cancel()
            target.setCurrentRequest(null)
        }
    }

    /**
     * A newer request may have claimed the target while this one was in flight; the older result
     * must not win, regardless of completion order or of which tier produced it.
     */
    private fun applyIfCurrent(target: Target, token: Long, bitmap: Bitmap) {
        if (target.currentRequest()?.token == token) target.setBitmap(bitmap)
    }

    internal companion object {

        private const val CACHE_DIRECTORY_NAME = "image_loader"

        /**
         * Builds the production loader. Only the application context is retained, and only to
         * resolve the cache directory — an Activity would leak.
         */
        @OptIn(ExperimentalCoroutinesApi::class)
        fun create(context: Context): RealImageLoader {
            val clock = Clock.SYSTEM
            val cacheDirectory = File(context.applicationContext.cacheDir, CACHE_DIRECTORY_NAME)
            val cache = ImageCache(
                memory = MemoryImageCache(clock),
                disk = DiskImageCache(cacheDirectory, clock),
                // Serialising disk work keeps invalidation ordered against reads.
                diskDispatcher = Dispatchers.IO.limitedParallelism(1),
            )
            return RealImageLoader(cache = cache, clock = clock)
        }
    }
}

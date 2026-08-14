package com.rounds.imageloader.internal

import android.widget.ImageView
import androidx.annotation.DrawableRes
import com.rounds.imageloader.ImageLoader
import com.rounds.imageloader.decode.BitmapFactoryImageDecoder
import com.rounds.imageloader.decode.ImageDecoder
import com.rounds.imageloader.network.HttpImageDownloader
import com.rounds.imageloader.network.ImageDownloader
import com.rounds.imageloader.request.ImageViewTarget
import com.rounds.imageloader.request.Target
import com.rounds.imageloader.request.TargetRequest
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Default [ImageLoader] implementation.
 *
 * Coroutine model: the loader owns one [CoroutineScope] built on a [SupervisorJob], so a failed or
 * cancelled request never takes down unrelated ones. The scope lives as long as the loader, which is
 * expected to be a process-wide singleton — it holds no Activity, Fragment or view reference of its
 * own. Downloading and decoding run on [ioDispatcher]; every view mutation happens on the main
 * dispatcher the scope is confined to. `GlobalScope` is never used, and callers never see a scope,
 * a dispatcher or a suspending function.
 *
 * Collaborators are constructor parameters with production defaults so tests can substitute the
 * network, the decoder and both dispatchers without any timing-sensitive setup.
 */
internal class RealImageLoader(
    private val downloader: ImageDownloader = HttpImageDownloader(),
    private val decoder: ImageDecoder = BitmapFactoryImageDecoder(),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    mainDispatcher: CoroutineDispatcher = Dispatchers.Main.immediate,
) : ImageLoader {

    private val scope = CoroutineScope(SupervisorJob() + mainDispatcher)
    private val tokens = AtomicLong()

    override fun load(url: String, @DrawableRes placeholderRes: Int, target: ImageView) {
        load(ImageViewTarget(target), url, placeholderRes)
    }

    override fun load(url: String, target: ImageView) {
        load(ImageViewTarget(target), url, ImageLoader.NO_PLACEHOLDER)
    }

    override fun clear(target: ImageView) {
        clear(ImageViewTarget(target))
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
                val bytes = withContext(ioDispatcher) { downloader.download(url) }
                val bitmap = withContext(ioDispatcher) { decoder.decode(bytes) }
                ensureActive()
                // A newer request may have claimed the target while this one was in flight; the
                // older result must not win, regardless of completion order.
                if (bitmap != null && target.currentRequest()?.token == token) {
                    target.setBitmap(bitmap)
                }
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

    internal fun clear(target: Target) {
        target.currentRequest()?.let { request ->
            request.job.cancel()
            target.setCurrentRequest(null)
        }
    }
}

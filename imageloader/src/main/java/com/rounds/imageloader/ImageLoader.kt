package com.rounds.imageloader

import android.content.Context
import android.widget.ImageView
import androidx.annotation.DrawableRes
import com.rounds.imageloader.internal.RealImageLoader

/**
 * Loads remote images into [ImageView] targets.
 *
 * The API is deliberately small and blocking-free: [load] returns immediately, the placeholder is
 * applied before it returns, and the decoded image is delivered to the target on the main thread.
 * Images are served from a bounded memory cache, then a disk cache, then the network, and stay
 * valid for four hours.
 * Coroutines are an implementation detail — consumers never supply a scope, a dispatcher or a
 * suspending function, which keeps the library equally usable from Java and Kotlin.
 *
 * [load] and [clear] mutate the target view, so call them from the main thread, as with any other
 * view operation. [clearCache] and [invalidate] touch no view and may be called from any thread.
 * An instance is intended to be created once per process and holds no Activity, Fragment or view
 * of its own. Create one with [create].
 */
interface ImageLoader {

    /**
     * Shows [placeholderRes] on [target] immediately, then loads [url] in the background and
     * displays the decoded image on [target] once it arrives.
     *
     * Any previous request for [target] is cancelled, and a result that is no longer the target's
     * current request is discarded. Failures — malformed URL, I/O error, non-success HTTP status,
     * empty body or undecodable payload — leave the placeholder in place and never throw.
     *
     * Passing [NO_PLACEHOLDER] behaves exactly like the two-argument [load].
     */
    fun load(url: String, @DrawableRes placeholderRes: Int, target: ImageView)

    /**
     * Loads [url] into [target] without a placeholder.
     *
     * The target's current image is cleared before loading starts, because a reused view would
     * otherwise keep showing the previous item's image until the new one arrives. Everything else —
     * cancellation of the previous request, stale-result rejection, silent failure handling — is
     * identical to the three-argument [load]; on failure the target simply stays empty.
     */
    fun load(url: String, target: ImageView)

    /**
     * Cancels the request currently associated with [target], if any, and prevents its result from
     * being applied. Safe to call for a target that has no pending request.
     *
     * This is view lifecycle, not storage: it discards work for one `ImageView` and leaves cached
     * images alone. To discard cached images use [clearCache] or [invalidate].
     */
    fun clear(target: ImageView)

    /**
     * Removes every cached image from both memory and disk. Callable from any thread.
     *
     * Two things happen before this returns, whichever thread calls it: memory is emptied, and the
     * deletion of the cached files is submitted to the queue that serialises disk work. A load
     * started after this returns therefore queues its disk read behind that deletion and is not
     * served an old image from either tier. A load that was already in flight cannot repopulate the
     * cache with what it downloaded before the invalidation.
     *
     * The files themselves are deleted asynchronously. This returns without waiting for that I/O
     * and reports no completion, so there is no point at which the caller can observe the disk as
     * physically empty. That last guarantee therefore depends on the deletion actually happening,
     * and deleting is best effort: if the filesystem refuses, those bytes stay on disk and a later
     * load can still be served them, until they expire with the normal four-hour TTL or a fresh
     * download replaces them. The same applies if the process dies before the queued deletion runs.
     * This is neither a secure erase nor durable across process death.
     */
    fun clearCache()

    /**
     * Removes the cached image for [url] from both memory and disk, leaving every other entry in
     * place. Callable from any thread, with the same immediacy, the same in-flight guarantee and
     * the same best-effort, non-durable disk deletion as [clearCache].
     */
    fun invalidate(url: String)

    companion object {

        /**
         * Placeholder value meaning "no placeholder" — `0` is never a valid resource id. Useful when
         * a caller computes the placeholder and may not have one.
         */
        const val NO_PLACEHOLDER: Int = 0

        /**
         * Creates an [ImageLoader] backed by `HttpURLConnection`, `BitmapFactory`, a bounded memory
         * cache and a disk cache under the application's cache directory.
         *
         * Only `context.applicationContext` is retained, so passing an Activity here is safe.
         */
        @JvmStatic
        fun create(context: Context): ImageLoader = RealImageLoader.create(context)
    }
}

package com.rounds.imageloader

import android.widget.ImageView
import androidx.annotation.DrawableRes
import com.rounds.imageloader.internal.RealImageLoader

/**
 * Loads remote images into [ImageView] targets.
 *
 * The API is deliberately small and blocking-free: [load] returns immediately, the placeholder is
 * applied before it returns, and the decoded image is delivered to the target on the main thread.
 * Coroutines are an implementation detail — consumers never supply a scope, a dispatcher or a
 * suspending function, which keeps the library equally usable from Java and Kotlin.
 *
 * Call [load] and [clear] from the main thread, as with any other view mutation. An instance is
 * intended to be created once per process and holds no Activity, Fragment or view of its own.
 * Create one with [create].
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
     */
    fun clear(target: ImageView)

    companion object {

        /**
         * Placeholder value meaning "no placeholder" — `0` is never a valid resource id. Useful when
         * a caller computes the placeholder and may not have one.
         */
        const val NO_PLACEHOLDER: Int = 0

        /** Creates an [ImageLoader] backed by `HttpURLConnection` and `BitmapFactory`. */
        @JvmStatic
        fun create(): ImageLoader = RealImageLoader()
    }
}

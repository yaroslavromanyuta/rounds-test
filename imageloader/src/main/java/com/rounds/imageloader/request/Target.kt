package com.rounds.imageloader.request

import android.graphics.Bitmap
import androidx.annotation.DrawableRes
import kotlinx.coroutines.Job

/**
 * The request currently owned by a target: its identity token and the coroutine producing it.
 */
internal class TargetRequest(val token: Long, val job: Job)

/**
 * Everything the loading pipeline needs from a view: apply an image, and remember which request the
 * view currently belongs to.
 *
 * This exists because `ImageView` and `Bitmap` cannot be exercised on the JVM without pulling in a
 * full Android test framework. Keeping view mutation behind a two-method seam lets the request
 * coordination — the part with the interesting correctness properties — be tested directly. It is
 * internal and never appears in the public API.
 */
internal interface Target {

    /** Applies the placeholder, or empties the target when given `ImageLoader.NO_PLACEHOLDER`. */
    fun setPlaceholder(@DrawableRes resId: Int)

    fun setBitmap(bitmap: Bitmap)

    fun currentRequest(): TargetRequest?

    fun setCurrentRequest(request: TargetRequest?)
}

package com.rounds.imageloader.request

import android.graphics.Bitmap
import android.widget.ImageView
import com.rounds.imageloader.ImageLoader
import com.rounds.imageloader.R
import java.lang.ref.WeakReference

/**
 * [Target] backed by a real [ImageView].
 *
 * The current request is stored in the view's own tag rather than in a map owned by the loader:
 * a long-lived map keyed by views would outlive the Activity that created them. The view is held
 * through a [WeakReference] so an in-flight download cannot retain a detached view — and through it
 * an Activity — for the lifetime of a slow network call.
 *
 * Wrappers are cheap and stateless; a new one can be created for the same view at any time and will
 * observe the same request state.
 */
internal class ImageViewTarget(imageView: ImageView) : Target {

    private val viewRef = WeakReference(imageView)

    override fun setPlaceholder(resId: Int) {
        val view = viewRef.get() ?: return
        // No placeholder means the view must still be emptied: a recycled row would otherwise keep
        // showing the previous item's image until the new one arrives.
        if (resId == ImageLoader.NO_PLACEHOLDER) {
            view.setImageDrawable(null)
        } else {
            view.setImageResource(resId)
        }
    }

    override fun setBitmap(bitmap: Bitmap) {
        viewRef.get()?.setImageBitmap(bitmap)
    }

    override fun currentRequest(): TargetRequest? =
        viewRef.get()?.getTag(R.id.imageloader_target_request) as? TargetRequest

    override fun setCurrentRequest(request: TargetRequest?) {
        viewRef.get()?.setTag(R.id.imageloader_target_request, request)
    }
}

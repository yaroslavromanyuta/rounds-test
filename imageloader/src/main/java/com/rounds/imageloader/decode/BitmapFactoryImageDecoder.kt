package com.rounds.imageloader.decode

import android.graphics.Bitmap
import android.graphics.BitmapFactory

/**
 * Decodes with the platform [BitmapFactory], which returns `null` for corrupt, truncated or
 * non-image payloads instead of throwing.
 *
 * Decoding happens in two passes. The first reads only the header — `inJustDecodeBounds` — to learn
 * the source dimensions without allocating pixels; the second decodes for real, subsampled by
 * [sampleSizeFor] so no bitmap exceeds [maxDimensionPx] on either edge.
 *
 * The bound is not cosmetic. A `Bitmap` larger than 100 MB cannot be drawn at all: `RecordingCanvas`
 * throws `Canvas: trying to draw too large bitmap` from `ImageView.onDraw`, which crashes the host
 * application from the UI thread where no `try`/`catch` in this library could intercept it. The
 * supplied image list contains an 11000x7000 record — 308 MB decoded — so this is a real payload,
 * not a hypothetical one. Subsampling is also the only way a host can survive such an image at all,
 * since the allocation itself would otherwise risk `OutOfMemoryError`.
 *
 * This is a safety bound, not a resizing pipeline: the default is large enough that every ordinary
 * image, including the 1920x1080 wallpapers in the sample payload, is decoded untouched at
 * `inSampleSize = 1`.
 */
internal class BitmapFactoryImageDecoder(
    private val maxDimensionPx: Int = DEFAULT_MAX_DIMENSION_PX,
) : ImageDecoder {

    override fun decode(bytes: ByteArray): Bitmap? {
        if (bytes.isEmpty()) return null

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        // BitmapFactory reports -1 for a payload it cannot make sense of, which lets an
        // undecodable body be rejected here without paying for a full decode attempt.
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleSizeFor(bounds.outWidth, bounds.outHeight, maxDimensionPx)
        }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
    }

    internal companion object {

        /**
         * Chosen so that the largest permitted bitmap is 2048x2048x4 = 16 MB, comfortably inside the
         * platform's 100 MB draw limit, while leaving every common image untouched.
         */
        const val DEFAULT_MAX_DIMENSION_PX: Int = 2048
    }
}

/**
 * Smallest power of two that brings both edges of a [width] x [height] image to [maxDimensionPx] or
 * less. `1` means "decode at full size".
 *
 * `BitmapFactory` rounds `inSampleSize` down to a power of two anyway, so computing one directly
 * keeps the requested and the actual subsampling identical.
 *
 * Kept as a free function with no Android types so the boundary arithmetic is unit-testable on the
 * JVM, where `BitmapFactory` is a stub that cannot decode anything.
 */
internal fun sampleSizeFor(width: Int, height: Int, maxDimensionPx: Int): Int {
    require(maxDimensionPx > 0) { "maxDimensionPx must be positive, was $maxDimensionPx" }
    var sampleSize = 1
    while (width / sampleSize > maxDimensionPx || height / sampleSize > maxDimensionPx) {
        sampleSize *= 2
    }
    return sampleSize
}

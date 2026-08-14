package com.rounds.imageloader.decode

import android.graphics.Bitmap

/**
 * Turns downloaded bytes into a [Bitmap].
 *
 * Decoding is CPU-bound and must run off the main thread. Like the downloader this is a real
 * boundary — it is the seam that lets the loading pipeline be tested without the Android framework.
 */
internal fun interface ImageDecoder {

    /** Returns the decoded bitmap, or `null` when [bytes] are empty or not a decodable image. */
    fun decode(bytes: ByteArray): Bitmap?
}

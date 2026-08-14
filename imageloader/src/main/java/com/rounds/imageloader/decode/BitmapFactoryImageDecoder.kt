package com.rounds.imageloader.decode

import android.graphics.Bitmap
import android.graphics.BitmapFactory

/**
 * Decodes with the platform [BitmapFactory], which returns `null` for corrupt, truncated or
 * non-image payloads instead of throwing.
 */
internal class BitmapFactoryImageDecoder : ImageDecoder {

    override fun decode(bytes: ByteArray): Bitmap? {
        if (bytes.isEmpty()) return null
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
    }
}

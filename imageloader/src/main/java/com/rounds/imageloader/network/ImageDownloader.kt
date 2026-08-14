package com.rounds.imageloader.network

import java.io.IOException

/**
 * Retrieves the raw bytes of a remote image.
 *
 * Implementations block, so callers must invoke them off the main thread. This is an abstraction
 * over external I/O — the boundary that has to be replaced in tests.
 */
internal fun interface ImageDownloader {

    /** Returns the image bytes for [url], or throws [IOException] for any failure. */
    @Throws(IOException::class)
    fun download(url: String): ByteArray
}

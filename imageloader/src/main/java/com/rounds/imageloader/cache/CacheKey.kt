package com.rounds.imageloader.cache

import java.security.MessageDigest

/**
 * Turns an image URL into a filesystem-safe cache key.
 *
 * A raw URL is not a usable filename — it carries `/`, `?`, `:` and can exceed filename length
 * limits. A SHA-256 digest is deterministic (the same URL always yields the same key), fixed
 * length, and safe on every filesystem.
 */
internal object CacheKey {

    fun of(url: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(url.toByteArray(Charsets.UTF_8))
        return buildString(digest.size * 2) {
            digest.forEach { byte -> append("%02x".format(byte)) }
        }
    }
}

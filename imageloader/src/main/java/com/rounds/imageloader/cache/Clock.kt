package com.rounds.imageloader.cache

/**
 * Time source for cache expiry.
 *
 * TTL logic depends on this rather than on [System.currentTimeMillis] directly, so tests can move
 * time deterministically instead of waiting four hours.
 */
internal fun interface Clock {

    fun nowMillis(): Long

    companion object {
        val SYSTEM: Clock = Clock { System.currentTimeMillis() }
    }
}

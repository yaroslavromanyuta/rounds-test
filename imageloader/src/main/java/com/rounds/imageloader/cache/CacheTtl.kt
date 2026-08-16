package com.rounds.imageloader.cache

/**
 * The assignment fixes cache validity at four hours.
 *
 * The rule lives here once so both cache tiers and the coordinator cannot drift apart on the
 * boundary — see [isCacheEntryValid] for what it is.
 */
internal const val CACHE_TTL_MILLIS: Long = 4L * 60L * 60L * 1000L

/**
 * `true` while the entry's age is in `[0, 4h)` — the upper bound is strict, so at exactly four
 * hours the entry is already expired.
 *
 * The age must be non-negative as well as short: an entry stamped in the future — after a wall
 * clock rollback, a manually changed device clock or restored cache metadata — would otherwise
 * have a negative age and stay "valid" until real time caught up with it, which is exactly the
 * unbounded lifetime the four-hour contract rules out. A future entry is therefore treated as
 * invalid, so the caches drop it and the image is fetched again.
 *
 * The TTL is fixed from the moment the entry was stored, not sliding — reading an entry never
 * extends it, because nothing here writes `cachedAtMillis`.
 */
internal fun isCacheEntryValid(cachedAtMillis: Long, nowMillis: Long): Boolean {
    if (nowMillis < cachedAtMillis) return false
    // The comparison above is overflow-free; the subtraction is not. For extreme timestamps it
    // wraps to a negative value, which the range check then rejects rather than reading as a small,
    // apparently fresh age — an arithmetic edge case fails closed, never open.
    val ageMillis = nowMillis - cachedAtMillis
    return ageMillis >= 0 && ageMillis < CACHE_TTL_MILLIS
}

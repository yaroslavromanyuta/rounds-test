package com.rounds.imageloader.cache

/**
 * The assignment fixes cache validity at four hours.
 *
 * The rule lives here once so both cache tiers and the coordinator cannot drift apart on the
 * boundary: an entry is valid while it is *strictly* younger than the TTL, so at exactly four hours
 * it is already expired.
 */
internal const val CACHE_TTL_MILLIS: Long = 4L * 60L * 60L * 1000L

/**
 * `true` while `nowMillis - cachedAtMillis < 4h`.
 *
 * The TTL is fixed from the moment the entry was stored, not sliding — reading an entry never
 * extends it, because nothing here writes `cachedAtMillis`.
 */
internal fun isCacheEntryValid(cachedAtMillis: Long, nowMillis: Long): Boolean =
    nowMillis - cachedAtMillis < CACHE_TTL_MILLIS

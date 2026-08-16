package com.rounds.imageloader.cache

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The TTL rule on its own, away from either cache tier: both boundaries of the valid age range and
 * the arithmetic edge cases that must not be able to widen it.
 */
class CacheTtlTest {

    @Test
    fun `the time to live is exactly four hours`() {
        assertEquals(4L * 60L * 60L * 1000L, CACHE_TTL_MILLIS)
    }

    @Test
    fun `an entry stored at the current instant is valid`() {
        assertTrue(isCacheEntryValid(cachedAtMillis = NOW, nowMillis = NOW))
    }

    @Test
    fun `an entry is valid one millisecond before four hours`() {
        assertTrue(isCacheEntryValid(cachedAtMillis = NOW - CACHE_TTL_MILLIS + 1, nowMillis = NOW))
    }

    @Test
    fun `an entry is expired at exactly four hours`() {
        assertFalse(isCacheEntryValid(cachedAtMillis = NOW - CACHE_TTL_MILLIS, nowMillis = NOW))
    }

    @Test
    fun `an entry is expired one millisecond after four hours`() {
        assertFalse(isCacheEntryValid(cachedAtMillis = NOW - CACHE_TTL_MILLIS - 1, nowMillis = NOW))
    }

    @Test
    fun `an entry stamped one millisecond in the future is invalid`() {
        assertFalse(isCacheEntryValid(cachedAtMillis = NOW + 1, nowMillis = NOW))
    }

    @Test
    fun `an entry stamped far in the future is invalid`() {
        // What a wall clock rollback of a day leaves behind: without the age floor this entry would
        // stay "valid" for a day plus four hours.
        assertFalse(isCacheEntryValid(cachedAtMillis = NOW + ONE_DAY_MILLIS, nowMillis = NOW))
    }

    @Test
    fun `the extreme future timestamp is invalid`() {
        assertFalse(isCacheEntryValid(cachedAtMillis = Long.MAX_VALUE, nowMillis = NOW))
    }

    @Test
    fun `an age too large to represent fails closed`() {
        // now - cachedAt overflows here; the wrapped result must not be read as a fresh age.
        assertFalse(isCacheEntryValid(cachedAtMillis = Long.MIN_VALUE, nowMillis = Long.MAX_VALUE))
    }

    @Test
    fun `an extreme future timestamp against an extreme clock fails closed`() {
        assertFalse(isCacheEntryValid(cachedAtMillis = Long.MAX_VALUE, nowMillis = Long.MIN_VALUE))
    }

    @Test
    fun `a zero clock does not make an old entry valid`() {
        assertFalse(isCacheEntryValid(cachedAtMillis = NOW, nowMillis = 0L))
    }

    private companion object {
        private const val NOW = 1_700_000_000_000L
        private const val ONE_DAY_MILLIS = 24L * 60L * 60L * 1000L
    }
}

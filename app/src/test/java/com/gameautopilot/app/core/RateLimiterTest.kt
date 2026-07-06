package com.gameautopilot.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RateLimiterTest {

    @Test
    fun `allows up to maxPerMinute acquisitions in the same window`() {
        val limiter = RateLimiter(maxPerMinute = 3)
        val now = 10_000L
        assertTrue(limiter.tryAcquire(now))
        assertTrue(limiter.tryAcquire(now))
        assertTrue(limiter.tryAcquire(now))
        assertFalse(limiter.tryAcquire(now))
    }

    @Test
    fun `frees up capacity once the window slides past old timestamps`() {
        val limiter = RateLimiter(maxPerMinute = 1)
        assertTrue(limiter.tryAcquire(0L))
        assertFalse(limiter.tryAcquire(30_000L))
        assertTrue(limiter.tryAcquire(60_001L))
    }

    @Test
    fun `reset clears all tracked timestamps`() {
        val limiter = RateLimiter(maxPerMinute = 1)
        assertTrue(limiter.tryAcquire(0L))
        assertFalse(limiter.tryAcquire(1L))
        limiter.reset()
        assertTrue(limiter.tryAcquire(2L))
    }

    @Test
    fun `zero or negative maxPerMinute still allows one acquisition per window`() {
        // coerceAtLeast(1) in tryAcquire means a misconfigured 0 doesn't hard-lock the loop.
        val limiter = RateLimiter(maxPerMinute = 0)
        assertTrue(limiter.tryAcquire(0L))
        assertFalse(limiter.tryAcquire(1L))
    }

    @Test
    fun `maxPerMinute can be changed live`() {
        val limiter = RateLimiter(maxPerMinute = 1)
        assertTrue(limiter.tryAcquire(0L))
        assertFalse(limiter.tryAcquire(1L))
        limiter.maxPerMinute = 2
        assertTrue(limiter.tryAcquire(2L))
        assertEquals(2, limiter.maxPerMinute)
    }
}

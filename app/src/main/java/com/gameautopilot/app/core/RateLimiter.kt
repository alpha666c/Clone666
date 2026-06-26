package com.gameautopilot.app.core

class RateLimiter(@Volatile var maxPerMinute: Int) {
    private val timestamps = ArrayDeque<Long>()

    @Synchronized
    fun tryAcquire(now: Long = System.currentTimeMillis()): Boolean {
        val windowStart = now - 60_000L
        while (timestamps.isNotEmpty() && timestamps.first() < windowStart) {
            timestamps.removeFirst()
        }
        if (timestamps.size >= maxPerMinute.coerceAtLeast(1)) return false
        timestamps.addLast(now)
        return true
    }

    @Synchronized
    fun reset() {
        timestamps.clear()
    }
}

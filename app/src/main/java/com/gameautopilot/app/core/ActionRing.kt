package com.gameautopilot.app.core

class ActionRing(private val capacity: Int = 12) {
    private val buf = ArrayDeque<String>(capacity)

    @Synchronized
    fun add(label: String) {
        if (buf.size >= capacity) buf.removeFirst()
        buf.addLast(label)
    }

    @Synchronized
    fun snapshot(): List<String> = buf.toList()

    @Synchronized
    fun clear() {
        buf.clear()
    }
}

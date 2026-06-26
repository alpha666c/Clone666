package com.gameautopilot.app.core

import org.json.JSONObject

sealed class Action {
    data class Tap(val x: Int, val y: Int, val durationMs: Long = 60) : Action()
    data class LongPress(val x: Int, val y: Int, val durationMs: Long = 800) : Action()
    data class Swipe(
        val x1: Int, val y1: Int,
        val x2: Int, val y2: Int,
        val durationMs: Long = 300
    ) : Action()
    data class Wait(val ms: Long) : Action()
    data object Back : Action()
    data object NoOp : Action()

    fun shortLabel(): String = when (this) {
        is Tap -> "tap($x,$y)"
        is LongPress -> "long($x,$y,${durationMs}ms)"
        is Swipe -> "swipe($x1,$y1→$x2,$y2)"
        is Wait -> "wait(${ms}ms)"
        Back -> "back"
        NoOp -> "noop"
    }

    companion object {
        fun fromJson(o: JSONObject): Action? {
            return when (o.optString("type").lowercase()) {
                "tap" -> Tap(
                    x = o.optInt("x", -1),
                    y = o.optInt("y", -1),
                    durationMs = o.optLong("durationMs", 60L)
                ).takeIf { it.x >= 0 && it.y >= 0 }
                "longpress", "long_press" -> LongPress(
                    x = o.optInt("x", -1),
                    y = o.optInt("y", -1),
                    durationMs = o.optLong("durationMs", 800L)
                ).takeIf { it.x >= 0 && it.y >= 0 }
                "swipe" -> Swipe(
                    x1 = o.optInt("x1", -1),
                    y1 = o.optInt("y1", -1),
                    x2 = o.optInt("x2", -1),
                    y2 = o.optInt("y2", -1),
                    durationMs = o.optLong("durationMs", 300L)
                ).takeIf { it.x1 >= 0 && it.y1 >= 0 && it.x2 >= 0 && it.y2 >= 0 }
                "wait" -> Wait(ms = o.optLong("ms", 500L).coerceIn(0L, 60_000L))
                "back" -> Back
                "noop", "" -> NoOp
                else -> null
            }
        }
    }
}

package com.gameautopilot.app.core

import com.gameautopilot.app.accessibility.AutopilotAccessibilityService
import com.gameautopilot.app.accessibility.GestureDispatcher
import com.gameautopilot.app.util.Logger
import kotlinx.coroutines.delay

class ActionDispatcher(
    private val screenWidth: () -> Int,
    private val screenHeight: () -> Int
) {

    suspend fun dispatch(action: Action, marks: List<MarkBox>): Boolean {
        val svc = AutopilotAccessibilityService.get()
        if (svc == null) {
            Logger.w("Cannot dispatch — accessibility service not connected")
            return false
        }
        val w = screenWidth()
        val h = screenHeight()
        return when (action) {
            is Action.Tap -> tap(svc, action.x, action.y, action.durationMs, w, h)
            is Action.LongPress -> tap(svc, action.x, action.y, action.durationMs, w, h)
            is Action.TapMark -> {
                val m = marks.firstOrNull { it.id == action.markId }
                if (m == null) {
                    Logger.w("TapMark id=${action.markId} not in current marks (size=${marks.size})")
                    false
                } else tap(svc, m.cx, m.cy, 60, w, h)
            }
            is Action.LongPressMark -> {
                val m = marks.firstOrNull { it.id == action.markId }
                if (m == null) {
                    Logger.w("LongPressMark id=${action.markId} not in current marks")
                    false
                } else tap(svc, m.cx, m.cy, action.durationMs, w, h)
            }
            is Action.Swipe -> {
                if (!inBounds(action.x1, action.y1, w, h) || !inBounds(action.x2, action.y2, w, h)) {
                    Logger.w("Skipping out-of-bounds swipe")
                    return false
                }
                GestureDispatcher.swipe(
                    svc,
                    action.x1.toFloat(), action.y1.toFloat(),
                    action.x2.toFloat(), action.y2.toFloat(),
                    action.durationMs
                )
            }
            is Action.TypeText -> svc.typeOnFocused(action.text, action.submit)
            Action.Back -> GestureDispatcher.back(svc)
            is Action.Wait -> {
                delay(action.ms.coerceIn(0L, 60_000L))
                true
            }
            Action.NoOp -> true
        }
    }

    private suspend fun tap(
        svc: AutopilotAccessibilityService,
        x: Int, y: Int, dur: Long,
        w: Int, h: Int
    ): Boolean {
        if (!inBounds(x, y, w, h)) {
            Logger.w("Skipping out-of-bounds tap ($x,$y) screen=${w}x$h")
            return false
        }
        return GestureDispatcher.tap(svc, x.toFloat(), y.toFloat(), dur)
    }

    private fun inBounds(x: Int, y: Int, w: Int, h: Int): Boolean =
        x in 0 until w && y in 0 until h
}

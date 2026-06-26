package com.gameautopilot.app.core

import com.gameautopilot.app.accessibility.AutopilotAccessibilityService
import com.gameautopilot.app.accessibility.GestureDispatcher
import com.gameautopilot.app.util.Logger
import kotlinx.coroutines.delay

class ActionDispatcher(
    private val screenWidth: () -> Int,
    private val screenHeight: () -> Int
) {
    suspend fun dispatch(action: Action): Boolean {
        val svc = AutopilotAccessibilityService.get()
        if (svc == null) {
            Logger.w("Cannot dispatch — accessibility service not connected")
            return false
        }
        val w = screenWidth()
        val h = screenHeight()
        return when (action) {
            is Action.Tap -> {
                if (!inBounds(action.x, action.y, w, h)) {
                    Logger.w("Skipping out-of-bounds tap (${action.x},${action.y})")
                    return false
                }
                GestureDispatcher.tap(svc, action.x.toFloat(), action.y.toFloat(), action.durationMs)
            }
            is Action.LongPress -> {
                if (!inBounds(action.x, action.y, w, h)) return false
                GestureDispatcher.tap(svc, action.x.toFloat(), action.y.toFloat(), action.durationMs)
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
            Action.Back -> GestureDispatcher.back(svc)
            is Action.Wait -> {
                delay(action.ms.coerceIn(0L, 60_000L))
                true
            }
            Action.NoOp -> true
        }
    }

    private fun inBounds(x: Int, y: Int, w: Int, h: Int): Boolean =
        x in 0 until w && y in 0 until h
}

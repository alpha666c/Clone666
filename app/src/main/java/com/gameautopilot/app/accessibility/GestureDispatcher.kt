package com.gameautopilot.app.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import com.gameautopilot.app.util.Logger
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

object GestureDispatcher {

    suspend fun tap(
        service: AccessibilityService,
        x: Float, y: Float,
        durationMs: Long
    ): Boolean {
        val path = Path().apply { moveTo(x, y); lineTo(x + 0.5f, y + 0.5f) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0L, durationMs.coerceIn(1L, 5_000L)))
            .build()
        return dispatch(service, gesture)
    }

    suspend fun swipe(
        service: AccessibilityService,
        x1: Float, y1: Float,
        x2: Float, y2: Float,
        durationMs: Long
    ): Boolean {
        val path = Path().apply { moveTo(x1, y1); lineTo(x2, y2) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0L, durationMs.coerceIn(50L, 10_000L)))
            .build()
        return dispatch(service, gesture)
    }

    fun back(service: AccessibilityService): Boolean =
        service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)

    private suspend fun dispatch(
        service: AccessibilityService,
        gesture: GestureDescription
    ): Boolean = suspendCancellableCoroutine { cont ->
        val ok = service.dispatchGesture(gesture, object : AccessibilityService.GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                if (cont.isActive) cont.resume(true)
            }
            override fun onCancelled(gestureDescription: GestureDescription?) {
                Logger.w("Gesture cancelled")
                if (cont.isActive) cont.resume(false)
            }
        }, null)
        if (!ok && cont.isActive) cont.resume(false)
    }
}

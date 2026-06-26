package com.gameautopilot.app.accessibility

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import com.gameautopilot.app.util.Logger
import java.util.concurrent.atomic.AtomicReference

class AutopilotAccessibilityService : AccessibilityService() {

    @Volatile var foregroundPackage: String? = null
        private set

    override fun onServiceConnected() {
        super.onServiceConnected()
        INSTANCE.set(this)
        Logger.i("AccessibilityService connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
            event.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
        ) {
            val pkg = event.packageName?.toString()
            if (!pkg.isNullOrBlank() && pkg != "com.android.systemui") {
                foregroundPackage = pkg
            }
        }
    }

    override fun onInterrupt() {
        // no-op
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        INSTANCE.compareAndSet(this, null)
        Logger.i("AccessibilityService unbound")
        return super.onUnbind(intent)
    }

    companion object {
        private val INSTANCE = AtomicReference<AutopilotAccessibilityService?>(null)
        fun get(): AutopilotAccessibilityService? = INSTANCE.get()
    }
}

package com.gameautopilot.app.accessibility

import android.accessibilityservice.AccessibilityService
import android.os.Bundle
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
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

    /**
     * Attempt to set text on the currently focused editable node.
     * If submit=true, also try IME-action-done after the text is set.
     * Returns true if any text was successfully set.
     */
    fun typeOnFocused(text: String, submit: Boolean): Boolean {
        val root = rootInActiveWindow ?: return false
        return try {
            val target = findEditable(root) ?: return false
            val args = Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
            }
            val ok = target.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
            if (ok && submit) {
                target.performAction(AccessibilityNodeInfo.ACTION_IME_ENTER)
            }
            ok
        } finally {
            runCatching { root.recycle() }
        }
    }

    private fun findEditable(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (node == null) return null
        if (node.isFocused && node.isEditable) return node
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = findEditable(child)
            if (found != null) return found
        }
        // Fallback: any editable
        if (node.isEditable) return node
        return null
    }

    companion object {
        private val INSTANCE = AtomicReference<AutopilotAccessibilityService?>(null)
        fun get(): AutopilotAccessibilityService? = INSTANCE.get()
    }
}

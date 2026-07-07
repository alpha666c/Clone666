package com.gameautopilot.app.accessibility

import android.accessibilityservice.AccessibilityService
import android.os.Build
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
            // Our own overlay chip updates its status text every tick (Idle/Thinking/
            // Acting), which fires TYPE_WINDOW_CONTENT_CHANGED with our own package —
            // without this exclusion that was constantly overwriting foregroundPackage
            // with our own app, making the loop think the game got interrupted by
            // itself on every single tick.
            if (!pkg.isNullOrBlank() && pkg != "com.android.systemui" && pkg != packageName) {
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
                submitImeAction(target)
            }
            ok
        } finally {
            runCatching { root.recycle() }
        }
    }

    /**
     * ACTION_IME_ENTER only exists as a public AccessibilityAction (API 30+) — there
     * is no legacy int constant for it on AccessibilityNodeInfo itself, so on older
     * OS versions there's no accessibility-service-safe way to submit an IME action.
     */
    private fun submitImeAction(target: AccessibilityNodeInfo): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            Logger.w("IME submit action requires API 30+ (device is ${Build.VERSION.SDK_INT})")
            return false
        }
        return runCatching {
            target.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)
        }.getOrDefault(false)
    }

    private fun findEditable(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (node == null) return null
        if (node.isFocused && node.isEditable) return node
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = findEditable(child)
            if (found != null) return found
            runCatching { child.recycle() }
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

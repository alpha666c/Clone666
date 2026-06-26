package com.gameautopilot.app.accessibility

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo

object NodeTreeReader {

    private const val MAX_NODES = 80

    fun read(service: AutopilotAccessibilityService): List<String> {
        val root = service.rootInActiveWindow ?: return emptyList()
        val out = ArrayList<String>(64)
        try {
            traverse(root, out, 0)
        } finally {
            runCatching { root.recycle() }
        }
        return out.take(MAX_NODES)
    }

    private fun traverse(
        node: AccessibilityNodeInfo?,
        out: MutableList<String>,
        depth: Int
    ) {
        if (node == null || out.size >= MAX_NODES) return
        val text = node.text?.toString().orEmpty().trim()
        val cd = node.contentDescription?.toString().orEmpty().trim()
        val label = when {
            text.isNotEmpty() && cd.isNotEmpty() && text != cd -> "$text | $cd"
            text.isNotEmpty() -> text
            cd.isNotEmpty() -> cd
            else -> ""
        }
        if (label.isNotEmpty() || node.isClickable) {
            val r = Rect()
            node.getBoundsInScreen(r)
            val displayLabel = label.ifEmpty { "(unlabeled)" }.replace('\n', ' ').take(80)
            out.add("[${r.left},${r.top},${r.right},${r.bottom}] click=${if (node.isClickable) "Y" else "N"} \"$displayLabel\"")
        }
        for (i in 0 until node.childCount) {
            traverse(node.getChild(i), out, depth + 1)
            if (out.size >= MAX_NODES) return
        }
    }
}

package com.gameautopilot.app.accessibility

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo

object NodeTreeReader {

    private const val MAX_NODES = 80

    data class ClickableNode(
        val text: String,
        val left: Int,
        val top: Int,
        val right: Int,
        val bottom: Int
    )

    data class A11yResult(
        val lines: List<String>,
        val clickables: List<ClickableNode>
    )

    fun read(service: AutopilotAccessibilityService): A11yResult {
        val root = service.rootInActiveWindow ?: return A11yResult(emptyList(), emptyList())
        val lines = ArrayList<String>(64)
        val clickables = ArrayList<ClickableNode>(32)
        try {
            traverse(root, lines, clickables)
        } finally {
            runCatching { root.recycle() }
        }
        return A11yResult(lines.take(MAX_NODES), clickables)
    }

    private fun traverse(
        node: AccessibilityNodeInfo?,
        lines: MutableList<String>,
        clickables: MutableList<ClickableNode>
    ) {
        if (node == null || lines.size >= MAX_NODES) return
        val text = node.text?.toString().orEmpty().trim()
        val cd = node.contentDescription?.toString().orEmpty().trim()
        val label = when {
            text.isNotEmpty() && cd.isNotEmpty() && text != cd -> "$text | $cd"
            text.isNotEmpty() -> text
            cd.isNotEmpty() -> cd
            else -> ""
        }
        val r = Rect()
        node.getBoundsInScreen(r)
        if (label.isNotEmpty() || node.isClickable) {
            val displayLabel = label.ifEmpty { "(unlabeled)" }.replace('\n', ' ').take(80)
            lines.add(
                "[${r.left},${r.top},${r.right},${r.bottom}] " +
                    "click=${if (node.isClickable) "Y" else "N"} \"$displayLabel\""
            )
        }
        if (node.isClickable && r.width() > 0 && r.height() > 0) {
            clickables.add(
                ClickableNode(
                    text = label.ifEmpty { "(unlabeled)" }.take(80),
                    left = r.left, top = r.top, right = r.right, bottom = r.bottom
                )
            )
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i)
            traverse(child, lines, clickables)
            runCatching { child?.recycle() }
            if (lines.size >= MAX_NODES) return
        }
    }
}

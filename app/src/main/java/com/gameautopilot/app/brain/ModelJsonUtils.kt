package com.gameautopilot.app.brain

/** Strips a leading/trailing ```json ... ``` fence some models wrap strict-JSON responses in. */
internal fun stripCodeFences(s: String): String {
    val t = s.trim()
    if (!t.startsWith("```")) return t
    val firstNewline = t.indexOf('\n').takeIf { it >= 0 } ?: return t
    val withoutOpen = t.substring(firstNewline + 1)
    val endFence = withoutOpen.lastIndexOf("```")
    return if (endFence >= 0) withoutOpen.substring(0, endFence) else withoutOpen
}

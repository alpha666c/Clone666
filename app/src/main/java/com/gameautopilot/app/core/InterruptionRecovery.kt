package com.gameautopilot.app.core

/**
 * Heuristic for the "another app/activity is in front of the game" case
 * (interstitial ads, permission dialogs, OS overlays). Rather than just
 * idling until the target package comes back on its own, look for a mark
 * whose label reads like a dismiss/skip control and tap it; fall back to
 * BACK when nothing matches. Bounded by the caller (DecisionLoop) to a few
 * consecutive attempts so a genuinely stuck interruption doesn't turn into
 * an infinite tap/back loop.
 */
object InterruptionRecovery {

    private val EXACT_KEYWORDS = setOf(
        "skip", "skip ad", "close", "close ad", "x", "✕", "✖", "×",
        "no thanks", "not now", "later", "dismiss", "got it", "ok", "okay",
        "continue", "done", "no, thanks", "maybe later"
    )

    fun findRecoveryAction(marks: List<MarkBox>): Action {
        val match = marks
            .filter { it.label.isNotBlank() }
            .firstOrNull { it.label.trim().lowercase() in EXACT_KEYWORDS }
        return if (match != null) Action.TapMark(match.id) else Action.Back
    }
}

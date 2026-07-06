package com.gameautopilot.app.core

/**
 * Cheap heuristic that skips the LLM round-trip on the common idle-game
 * pattern: same "Collect" / "Claim" / "Continue" button repeatedly.
 *
 * After the brain dispatches a TapMark, we remember its label. On
 * subsequent ticks, if the same label is still visible AND the screen
 * is largely unchanged (small perceptual-hash delta), we re-issue the
 * same tap without calling the brain. Capped at maxConsecutive to
 * avoid feedback loops; resets when the brain decides differently or
 * the screen changes meaningfully.
 */
class A11yFastPath(
    private val maxConsecutive: Int = 3,
    private val maxDelta: Int = 4
) {
    @Volatile private var lastLabel: String? = null
    @Volatile private var consecutive: Int = 0

    fun tryFastPath(snapshot: ScreenSnapshot, deltaSincePrev: Int): List<Action>? {
        if (consecutive >= maxConsecutive) return null
        if (deltaSincePrev < 0 || deltaSincePrev > maxDelta) return null
        val label = lastLabel ?: return null
        val match = snapshot.marks.firstOrNull { it.label.equals(label, ignoreCase = true) }
            ?: return null
        consecutive++
        return listOf(Action.TapMark(match.id))
    }

    fun recordBrainDispatch(actions: List<Action>, marks: List<MarkBox>) {
        val tap = actions.firstOrNull { it is Action.TapMark } as? Action.TapMark
        lastLabel = tap?.let { mid -> marks.firstOrNull { it.id == mid.markId }?.label }
        consecutive = 0
    }

    fun reset() {
        lastLabel = null
        consecutive = 0
    }
}

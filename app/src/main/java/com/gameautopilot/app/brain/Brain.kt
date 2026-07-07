package com.gameautopilot.app.brain

import com.gameautopilot.app.core.Action
import com.gameautopilot.app.core.MarkBox
import com.gameautopilot.app.data.GameMemory

/** The "Reasoner" seam: whatever decides what to do next from a BrainContext. */
interface Brain {
    suspend fun decide(ctx: BrainContext): BrainDecision
}

data class BrainContext(
    val gameName: String,
    val gamePackage: String,
    val gameSystemPrompt: String,
    val screenWidth: Int,
    val screenHeight: Int,
    val screenshotBase64Jpeg: String,
    val ocrLines: List<String>,
    val a11yLines: List<String>,
    val marks: List<MarkBox>,
    val recentActionLabels: List<String>,
    /** Perceptual-hash Hamming distance since the last tick (-1 if there is no prior tick). */
    val lastActionDelta: Int = -1,
    val stuckHint: String? = null,
    /** Persistent per-game notes carried across ticks (and app restarts) — see GameMemoryStore. */
    val gameMemory: GameMemory = GameMemory(),
    /** Ephemeral, one-tick-only web research result — see WebSearchProvider. Not persisted. */
    val researchNotes: String? = null
)

data class BrainDecision(
    val thought: String,
    val actions: List<Action>,
    val confidence: Double,
    /** Non-null when the brain wants to replace gameMemory for future ticks. */
    val memoryUpdate: GameMemory? = null
) {
    companion object {
        val EMPTY = BrainDecision("", emptyList(), 0.0)
    }
}

class BrainException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

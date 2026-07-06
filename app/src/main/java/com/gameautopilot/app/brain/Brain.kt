package com.gameautopilot.app.brain

import com.gameautopilot.app.core.Action
import com.gameautopilot.app.core.MarkBox

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
    val stuckHint: String? = null,
    /** Persistent per-game notes carried across ticks (and app restarts) — see GameMemoryStore. */
    val gameMemory: String = ""
)

data class BrainDecision(
    val thought: String,
    val actions: List<Action>,
    val confidence: Double,
    /** Non-null when the brain wants to replace gameMemory for future ticks. */
    val memoryUpdate: String? = null
) {
    companion object {
        val EMPTY = BrainDecision("", emptyList(), 0.0)
    }
}

class BrainException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

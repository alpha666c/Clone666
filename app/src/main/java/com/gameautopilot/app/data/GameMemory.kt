package com.gameautopilot.app.data

/**
 * Structured per-game long-term memory the brain writes to itself, replacing
 * the flat free-text blob from Batch I. `goal`/`unlocks` are queryable
 * progress state; `notes` is free-form scratch space for anything that
 * doesn't fit a goal/unlock (a mistake to avoid, a UI quirk of this game).
 */
data class GameMemory(
    val goal: String = "",
    val unlocks: List<String> = emptyList(),
    val notes: String = "",
    val updatedAtMs: Long = 0L
) {
    fun isBlank(): Boolean = goal.isBlank() && unlocks.isEmpty() && notes.isBlank()

    /** Rendered back into the brain's prompt each tick. */
    fun toPromptText(): String {
        if (isBlank()) return "(none yet — this is a fresh start)"
        return buildString {
            if (goal.isNotBlank()) appendLine("Current goal: $goal")
            if (unlocks.isNotEmpty()) appendLine("Unlocked so far: ${unlocks.joinToString(", ")}")
            if (notes.isNotBlank()) appendLine("Notes: $notes")
        }.trim()
    }

    companion object {
        const val MAX_GOAL_CHARS = 200
        const val MAX_NOTES_CHARS = 3000
        const val MAX_UNLOCKS = 40
        const val MAX_UNLOCK_CHARS = 80
    }
}

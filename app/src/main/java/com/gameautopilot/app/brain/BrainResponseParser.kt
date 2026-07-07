package com.gameautopilot.app.brain

import com.gameautopilot.app.core.Action
import com.gameautopilot.app.data.GameMemory
import com.gameautopilot.app.util.Logger
import org.json.JSONArray
import org.json.JSONObject

/**
 * Parses the strict-JSON `{thought, actions[], confidence, memory}` payload
 * that every Brain implementation asks the model for, once each provider has
 * unwrapped its own response envelope down to the raw model text.
 */
object BrainResponseParser {

    fun parse(rawModelText: String): BrainDecision {
        val cleaned = stripCodeFences(rawModelText).trim()
        val obj = try {
            JSONObject(cleaned)
        } catch (e: Throwable) {
            throw BrainException("Brain returned non-JSON: ${cleaned.take(200)}", e)
        }

        val thought = obj.optString("thought", "")
        val confidence = obj.optDouble("confidence", 0.5)
        val actionsArr = obj.optJSONArray("actions") ?: JSONArray()
        val actions = (0 until actionsArr.length()).mapNotNull { idx ->
            runCatching { Action.fromJson(actionsArr.getJSONObject(idx)) }
                .onFailure { Logger.w("Skipping bad action: ${it.message}") }
                .getOrNull()
        }
        val memoryUpdate = parseMemory(obj.opt("memory"))
        return BrainDecision(thought = thought, actions = actions, confidence = confidence, memoryUpdate = memoryUpdate)
    }

    /**
     * Accepts the documented shape (`{"goal":..,"unlocks":[..],"notes":..}`)
     * and, for leniency, a bare string — some vision models ignore the
     * "must be an object" instruction and just echo free text; treat that as
     * `notes` rather than dropping the update entirely.
     */
    private fun parseMemory(raw: Any?): GameMemory? = when (raw) {
        is JSONObject -> {
            val unlocksArr = raw.optJSONArray("unlocks")
            val mem = GameMemory(
                goal = raw.optString("goal", ""),
                unlocks = unlocksArr?.let { arr -> (0 until arr.length()).map { arr.getString(it) } } ?: emptyList(),
                notes = raw.optString("notes", ""),
                updatedAtMs = System.currentTimeMillis()
            )
            mem.takeUnless { it.isBlank() }
        }
        is String -> raw.ifBlank { null }?.let { GameMemory(notes = it, updatedAtMs = System.currentTimeMillis()) }
        else -> null
    }
}

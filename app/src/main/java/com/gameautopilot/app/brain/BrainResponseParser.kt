package com.gameautopilot.app.brain

import com.gameautopilot.app.core.Action
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
        val memoryUpdate = obj.optString("memory", "").ifBlank { null }
        return BrainDecision(thought = thought, actions = actions, confidence = confidence, memoryUpdate = memoryUpdate)
    }

    private fun stripCodeFences(s: String): String {
        val t = s.trim()
        if (!t.startsWith("```")) return t
        val firstNewline = t.indexOf('\n').takeIf { it >= 0 } ?: return t
        val withoutOpen = t.substring(firstNewline + 1)
        val endFence = withoutOpen.lastIndexOf("```")
        return if (endFence >= 0) withoutOpen.substring(0, endFence) else withoutOpen
    }
}

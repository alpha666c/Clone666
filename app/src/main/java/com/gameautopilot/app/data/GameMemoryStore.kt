package com.gameautopilot.app.data

import android.content.Context
import com.gameautopilot.app.util.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Persists structured [GameMemory] per game — the brain's own notes about
 * goal/progress/unlocks, carried across ticks and app restarts so it doesn't
 * relearn the game from scratch every session. Files predating Batch J are
 * plain text (no JSON); [get] transparently migrates those into `notes` the
 * first time they're read, so no session loses its history on upgrade.
 */
class GameMemoryStore(context: Context) {
    private val dir = File(context.filesDir, "memory").apply { mkdirs() }

    fun get(gameId: String): GameMemory {
        val file = File(dir, fileName(gameId))
        if (!file.exists()) return GameMemory()
        val text = try {
            file.readText()
        } catch (t: Throwable) {
            Logger.e("Failed to read memory for $gameId", t)
            return GameMemory()
        }
        return parse(text)
    }

    suspend fun set(gameId: String, memory: GameMemory) = withContext(Dispatchers.IO) {
        try {
            val json = JSONObject().apply {
                put("goal", memory.goal.take(GameMemory.MAX_GOAL_CHARS))
                put(
                    "unlocks",
                    JSONArray(memory.unlocks.take(GameMemory.MAX_UNLOCKS).map { it.take(GameMemory.MAX_UNLOCK_CHARS) })
                )
                put("notes", memory.notes.take(GameMemory.MAX_NOTES_CHARS))
                put("updatedAtMs", memory.updatedAtMs)
            }
            File(dir, fileName(gameId)).writeText(json.toString())
        } catch (t: Throwable) {
            Logger.e("Failed to write memory for $gameId", t)
        }
    }

    suspend fun clear(gameId: String) = withContext(Dispatchers.IO) {
        runCatching { File(dir, fileName(gameId)).delete() }
    }

    private fun parse(text: String): GameMemory = try {
        val obj = JSONObject(text)
        val unlocksArr = obj.optJSONArray("unlocks")
        GameMemory(
            goal = obj.optString("goal", ""),
            unlocks = unlocksArr?.let { arr -> (0 until arr.length()).map { arr.getString(it) } } ?: emptyList(),
            notes = obj.optString("notes", ""),
            updatedAtMs = obj.optLong("updatedAtMs", 0L)
        )
    } catch (t: Throwable) {
        // Pre-Batch-J file: a flat text blob. Migrate it into `notes` rather than discarding it.
        GameMemory(notes = text.take(GameMemory.MAX_NOTES_CHARS))
    }

    private fun fileName(gameId: String) = "$gameId.txt"
}

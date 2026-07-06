package com.gameautopilot.app.data

import android.content.Context
import com.gameautopilot.app.util.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Persists a small free-text "memory" per game — the brain's own notes about
 * goal/progress/what-to-try-next, carried across ticks and app restarts so
 * it doesn't relearn the game from scratch every session.
 */
class GameMemoryStore(context: Context) {
    private val dir = File(context.filesDir, "memory").apply { mkdirs() }

    fun get(gameId: String): String = try {
        File(dir, fileName(gameId)).takeIf { it.exists() }?.readText().orEmpty()
    } catch (t: Throwable) {
        Logger.e("Failed to read memory for $gameId", t)
        ""
    }

    suspend fun set(gameId: String, text: String) = withContext(Dispatchers.IO) {
        try {
            File(dir, fileName(gameId)).writeText(text.take(MAX_CHARS))
        } catch (t: Throwable) {
            Logger.e("Failed to write memory for $gameId", t)
        }
    }

    suspend fun clear(gameId: String) = withContext(Dispatchers.IO) {
        runCatching { File(dir, fileName(gameId)).delete() }
    }

    private fun fileName(gameId: String) = "$gameId.txt"

    companion object {
        private const val MAX_CHARS = 4000
    }
}

package com.gameautopilot.app.data

import android.content.Context
import com.gameautopilot.app.util.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.io.File

class GameRepository(context: Context) {
    private val file = File(context.filesDir, FILE_NAME)
    private val mutex = Mutex()
    private val _games = MutableStateFlow<List<Game>>(emptyList())
    val games: StateFlow<List<Game>> = _games.asStateFlow()

    init {
        _games.value = loadSync()
    }

    private fun loadSync(): List<Game> = try {
        if (!file.exists()) emptyList()
        else {
            val txt = file.readText()
            if (txt.isBlank()) emptyList()
            else {
                val arr = JSONArray(txt)
                (0 until arr.length()).mapNotNull { idx ->
                    runCatching { Game.fromJson(arr.getJSONObject(idx)) }.getOrNull()
                }
            }
        }
    } catch (t: Throwable) {
        Logger.e("Failed to load games", t)
        emptyList()
    }

    private suspend fun persist(games: List<Game>) = withContext(Dispatchers.IO) {
        val arr = JSONArray()
        games.forEach { arr.put(it.toJson()) }
        file.writeText(arr.toString(2))
    }

    suspend fun upsert(game: Game) = mutex.withLock {
        val list = _games.value.toMutableList()
        val idx = list.indexOfFirst { it.id == game.id }
        if (idx >= 0) list[idx] = game else list.add(game)
        _games.value = list
        persist(list)
    }

    suspend fun delete(id: String) = mutex.withLock {
        val list = _games.value.filterNot { it.id == id }
        _games.value = list
        persist(list)
    }

    fun find(id: String): Game? = _games.value.firstOrNull { it.id == id }

    companion object {
        private const val FILE_NAME = "games.json"
    }
}

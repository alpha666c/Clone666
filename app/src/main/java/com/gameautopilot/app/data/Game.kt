package com.gameautopilot.app.data

import org.json.JSONObject
import java.util.UUID

data class Game(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val packageName: String,
    val systemPrompt: String,
    val tickIntervalMs: Long = 1500L,
    /** Optional calibrated grid (match-3 board, slot reels, bingo card, ...) — see BoardConfig. */
    val board: BoardConfig? = null
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("name", name)
        put("packageName", packageName)
        put("systemPrompt", systemPrompt)
        put("tickIntervalMs", tickIntervalMs)
        board?.let { put("board", it.toJson()) }
    }

    companion object {
        fun fromJson(o: JSONObject): Game = Game(
            id = o.optString("id", UUID.randomUUID().toString()),
            name = o.optString("name", "Unnamed"),
            packageName = o.optString("packageName", ""),
            systemPrompt = o.optString("systemPrompt", ""),
            tickIntervalMs = o.optLong("tickIntervalMs", 1500L).coerceIn(250L, 60_000L),
            board = BoardConfig.fromJson(o.optJSONObject("board"))
        )
    }
}

package com.gameautopilot.app.brain

import com.gameautopilot.app.data.BoardConfig
import org.json.JSONObject

/** Result of asking a vision-capable Brain to find a grid board in a screenshot. */
data class BoardDetection(
    val rows: Int,
    val cols: Int,
    val leftPct: Double,
    val topPct: Double,
    val rightPct: Double,
    val bottomPct: Double
) {
    fun toBoardConfig(): BoardConfig? = BoardConfig(
        rows = rows,
        cols = cols,
        leftFrac = (leftPct / 100.0).toFloat(),
        topFrac = (topPct / 100.0).toFloat(),
        rightFrac = (rightPct / 100.0).toFloat(),
        bottomFrac = (bottomPct / 100.0).toFloat()
    ).takeIf { it.isValid() }
}

/**
 * One-shot "does this screenshot have a grid board, and where" prompt — this is
 * the self-calibration engine: instead of the user hand-measuring percentages,
 * the same vision model that already plays the game is asked to look once and
 * report the grid's shape and bounds.
 */
object BoardDetectionParser {

    fun buildPrompt(): String = """
Look at this screenshot of a mobile game. Does it contain a grid-based game
board — a match-3 tile grid, slot machine reels, a bingo card, or any other
evenly-spaced rows x cols layout of interactive cells? Ignore decorative
borders, frames, or background art around it; find the grid of CELLS itself,
as tightly as you can.

Respond with STRICT JSON and nothing else:
- If you find one: {"found":true,"rows":<int>,"cols":<int>,"leftPct":<0-100>,"topPct":<0-100>,"rightPct":<0-100>,"bottomPct":<0-100>}
  Percentages are relative to the FULL screenshot width/height (0 = left/top
  edge of the whole image, 100 = right/bottom edge of the whole image), and
  must tightly bound just the grid of cells — not surrounding UI chrome.
- If there is no such grid visible right now: {"found":false}
Never include text outside the JSON object.
""".trimIndent()

    fun parse(rawModelText: String): BoardDetection? {
        val cleaned = stripCodeFences(rawModelText).trim()
        val obj = runCatching { JSONObject(cleaned) }.getOrNull() ?: return null
        if (!obj.optBoolean("found", false)) return null
        return BoardDetection(
            rows = obj.optInt("rows", 0),
            cols = obj.optInt("cols", 0),
            leftPct = obj.optDouble("leftPct", -1.0),
            topPct = obj.optDouble("topPct", -1.0),
            rightPct = obj.optDouble("rightPct", -1.0),
            bottomPct = obj.optDouble("bottomPct", -1.0)
        )
    }
}

package com.gameautopilot.app.data

import org.json.JSONObject

/**
 * A calibrated rows x cols grid over a rectangular region of the screen,
 * stored as fractions of screen width/height so it survives rotation and
 * resolution changes. Lets a game (match-3, bejeweled-style, slot reels,
 * bingo cards, ...) be addressed by (row, col) instead of the brain having
 * to guess raw pixel coordinates from a downscaled screenshot, and lets the
 * app read each cell's dominant color back as text context.
 */
data class BoardConfig(
    val rows: Int,
    val cols: Int,
    val leftFrac: Float,
    val topFrac: Float,
    val rightFrac: Float,
    val bottomFrac: Float
) {
    fun isValid(): Boolean =
        rows in 1..30 && cols in 1..30 &&
            leftFrac in 0f..1f && topFrac in 0f..1f &&
            rightFrac in 0f..1f && bottomFrac in 0f..1f &&
            rightFrac > leftFrac && bottomFrac > topFrac

    /** Pixel center of cell (row, col) for a screen of the given size. */
    fun cellCenter(row: Int, col: Int, screenWidth: Int, screenHeight: Int): Pair<Int, Int> {
        val b = cellBounds(row, col, screenWidth, screenHeight)
        return (b.left + b.right) / 2 to (b.top + b.bottom) / 2
    }

    /** Pixel bounds of cell (row, col), used for color sampling and hit-testing. */
    fun cellBounds(row: Int, col: Int, screenWidth: Int, screenHeight: Int): CellRect {
        val left = leftFrac * screenWidth
        val top = topFrac * screenHeight
        val cellW = (rightFrac - leftFrac) * screenWidth / cols
        val cellH = (bottomFrac - topFrac) * screenHeight / rows
        return CellRect(
            left = (left + col * cellW).toInt(),
            top = (top + row * cellH).toInt(),
            right = (left + (col + 1) * cellW).toInt(),
            bottom = (top + (row + 1) * cellH).toInt()
        )
    }

    fun toJson(): JSONObject = JSONObject().apply {
        put("rows", rows)
        put("cols", cols)
        put("leftFrac", leftFrac.toDouble())
        put("topFrac", topFrac.toDouble())
        put("rightFrac", rightFrac.toDouble())
        put("bottomFrac", bottomFrac.toDouble())
    }

    data class CellRect(val left: Int, val top: Int, val right: Int, val bottom: Int)

    companion object {
        fun fromJson(o: JSONObject?): BoardConfig? {
            if (o == null) return null
            val cfg = BoardConfig(
                rows = o.optInt("rows", 0),
                cols = o.optInt("cols", 0),
                leftFrac = o.optDouble("leftFrac", 0.0).toFloat(),
                topFrac = o.optDouble("topFrac", 0.0).toFloat(),
                rightFrac = o.optDouble("rightFrac", 0.0).toFloat(),
                bottomFrac = o.optDouble("bottomFrac", 0.0).toFloat()
            )
            return cfg.takeIf { it.isValid() }
        }
    }
}

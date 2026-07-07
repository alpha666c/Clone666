package com.gameautopilot.app.vision

import android.graphics.Bitmap
import com.gameautopilot.app.data.BoardConfig
import kotlin.math.max

/**
 * Reads a calibrated BoardConfig back from a captured frame: samples each
 * cell's dominant color and names it, giving the brain textual grid state
 * ("row 0: green, purple, red, ...") instead of making it infer gem/symbol
 * colors purely by eyeballing a downscaled screenshot. This is color-based,
 * not symbol recognition — it tells apart red/green/blue/etc, not "cherry"
 * vs "seven" on a slot reel, but that's already enough to ground matches
 * and swaps precisely instead of guessing pixels.
 */
object BoardReader {

    data class BoardState(val rows: Int, val cols: Int, val cellColors: List<List<String>>)

    fun read(bitmap: Bitmap, board: BoardConfig): BoardState {
        val w = bitmap.width
        val h = bitmap.height
        val grid = (0 until board.rows).map { row ->
            (0 until board.cols).map { col ->
                nameColor(averageColor(bitmap, board.cellBounds(row, col, w, h)))
            }
        }
        return BoardState(board.rows, board.cols, grid)
    }

    /** Average RGB sampled from the central 60% of the cell, avoiding grid-line/border pixels. */
    private fun averageColor(bitmap: Bitmap, bounds: BoardConfig.CellRect): IntArray {
        val cellW = bounds.right - bounds.left
        val cellH = bounds.bottom - bounds.top
        if (cellW <= 0 || cellH <= 0) return intArrayOf(0, 0, 0)
        val insetX = (cellW * 0.2f).toInt()
        val insetY = (cellH * 0.2f).toInt()
        val left = (bounds.left + insetX).coerceIn(0, bitmap.width - 1)
        val top = (bounds.top + insetY).coerceIn(0, bitmap.height - 1)
        val right = (bounds.right - insetX).coerceIn(left + 1, bitmap.width)
        val bottom = (bounds.bottom - insetY).coerceIn(top + 1, bitmap.height)
        var rs = 0L
        var gs = 0L
        var bs = 0L
        var n = 0L
        val stepX = max(1, (right - left) / 8)
        val stepY = max(1, (bottom - top) / 8)
        var y = top
        while (y < bottom) {
            var x = left
            while (x < right) {
                val px = bitmap.getPixel(x, y)
                rs += (px shr 16) and 0xFF
                gs += (px shr 8) and 0xFF
                bs += px and 0xFF
                n++
                x += stepX
            }
            y += stepY
        }
        if (n == 0L) n = 1
        return intArrayOf((rs / n).toInt(), (gs / n).toInt(), (bs / n).toInt())
    }

    private fun nameColor(rgb: IntArray): String = nameColor(rgb[0], rgb[1], rgb[2])

    /** Nearest-hue classification into a small, brain-friendly color vocabulary. */
    fun nameColor(r: Int, g: Int, b: Int): String {
        val max = maxOf(r, g, b)
        val min = minOf(r, g, b)
        val delta = max - min
        if (max < 60) return "black"
        if (min > 200 && delta < 25) return "white"
        if (delta < 25) return "gray"
        val hue = hueDegrees(r, g, b)
        return when {
            hue < 15 || hue >= 345 -> "red"
            hue < 45 -> "orange"
            hue < 70 -> "yellow"
            hue < 170 -> "green"
            hue < 200 -> "cyan"
            hue < 260 -> "blue"
            hue < 320 -> "purple"
            else -> "pink"
        }
    }

    private fun hueDegrees(r: Int, g: Int, b: Int): Float {
        val rf = r / 255f
        val gf = g / 255f
        val bf = b / 255f
        val max = maxOf(rf, gf, bf)
        val min = minOf(rf, gf, bf)
        val delta = max - min
        if (delta == 0f) return 0f
        val hue = when (max) {
            rf -> 60 * (((gf - bf) / delta).mod(6f))
            gf -> 60 * (((bf - rf) / delta) + 2)
            else -> 60 * (((rf - gf) / delta) + 4)
        }
        return if (hue < 0) hue + 360 else hue
    }
}

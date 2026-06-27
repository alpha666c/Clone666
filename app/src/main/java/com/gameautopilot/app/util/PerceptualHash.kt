package com.gameautopilot.app.util

import android.graphics.Bitmap
import android.graphics.Color

/**
 * Classic dHash: scale to 9x8, grayscale, compare adjacent columns → 64-bit hash.
 * Stable enough for "did the screen change after my action?" verification.
 */
object PerceptualHash {

    fun dHash(src: Bitmap): Long {
        val small = Bitmap.createScaledBitmap(src, 9, 8, true)
        var hash = 0L
        var bit = 0
        for (y in 0 until 8) {
            var prevGray = luminance(small.getPixel(0, y))
            for (x in 1 until 9) {
                val gray = luminance(small.getPixel(x, y))
                if (gray > prevGray) hash = hash or (1L shl bit)
                bit++
                prevGray = gray
            }
        }
        if (small !== src) small.recycle()
        return hash
    }

    fun hamming(a: Long, b: Long): Int = java.lang.Long.bitCount(a xor b)

    private fun luminance(argb: Int): Int {
        val r = Color.red(argb)
        val g = Color.green(argb)
        val b = Color.blue(argb)
        // BT.601 luma
        return (r * 299 + g * 587 + b * 114) / 1000
    }
}

package com.gameautopilot.app.core

enum class MarkSource { A11Y, OCR }

data class MarkBox(
    val id: Int,
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
    val source: MarkSource,
    val label: String
) {
    val cx: Int get() = (left + right) / 2
    val cy: Int get() = (top + bottom) / 2
    val width: Int get() = right - left
    val height: Int get() = bottom - top
    val area: Long get() = width.toLong() * height.toLong()

    fun iou(other: MarkBox): Float {
        val ix1 = maxOf(left, other.left)
        val iy1 = maxOf(top, other.top)
        val ix2 = minOf(right, other.right)
        val iy2 = minOf(bottom, other.bottom)
        if (ix2 <= ix1 || iy2 <= iy1) return 0f
        val inter = (ix2 - ix1).toLong() * (iy2 - iy1).toLong()
        val union = area + other.area - inter
        if (union <= 0) return 0f
        return (inter.toDouble() / union.toDouble()).toFloat()
    }
}

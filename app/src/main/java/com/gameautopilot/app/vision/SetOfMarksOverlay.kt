package com.gameautopilot.app.vision

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import com.gameautopilot.app.core.MarkBox
import kotlin.math.max
import kotlin.math.min

/**
 * Draws translucent numbered rectangles on top of a bitmap copy.
 * The numbers correspond to MarkBox.id; the brain returns these IDs
 * via {"type":"tapMark","markId":N} instead of raw pixel coordinates.
 */
object SetOfMarksOverlay {

    fun annotate(src: Bitmap, marks: List<MarkBox>): Bitmap {
        val out = src.copy(Bitmap.Config.ARGB_8888, true)
        if (marks.isEmpty()) return out
        val canvas = Canvas(out)
        val box = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = max(2f, src.width / 480f)
            color = Color.argb(220, 80, 220, 120)
        }
        val labelBg = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = Color.argb(220, 0, 0, 0)
        }
        val labelText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(255, 255, 255, 120)
            textSize = max(18f, src.width / 50f)
            isFakeBoldText = true
        }
        val pad = max(4f, src.width / 240f)

        for (m in marks) {
            canvas.drawRect(m.left.toFloat(), m.top.toFloat(), m.right.toFloat(), m.bottom.toFloat(), box)
            val text = m.id.toString()
            val bounds = Rect()
            labelText.getTextBounds(text, 0, text.length, bounds)
            val bgLeft = m.left.toFloat()
            val bgTop = max(0f, m.top.toFloat() - bounds.height() - 2f * pad)
            val bgRight = bgLeft + bounds.width() + 2f * pad
            val bgBottom = bgTop + bounds.height() + 2f * pad
            canvas.drawRect(bgLeft, bgTop, bgRight, bgBottom, labelBg)
            canvas.drawText(text, bgLeft + pad, bgBottom - pad - 2f, labelText)
        }
        return out
    }
}

@Suppress("unused")
private fun clampi(v: Int, lo: Int, hi: Int) = min(max(v, lo), hi)

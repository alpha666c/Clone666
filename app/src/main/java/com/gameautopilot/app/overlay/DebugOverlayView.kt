package com.gameautopilot.app.overlay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.view.View
import com.gameautopilot.app.core.MarkBox
import kotlin.math.max

/**
 * Full-screen non-interactive overlay that paints the current set-of-marks
 * boxes on the live screen. Used during development to see exactly what
 * the brain sees. Settings-toggled; off by default.
 */
class DebugOverlayView(context: Context) : View(context) {

    @Volatile private var marks: List<MarkBox> = emptyList()
    @Volatile private var lastTappedMarkId: Int? = null

    private val box = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f
        color = Color.argb(160, 80, 220, 120)
    }
    private val boxTapped = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 6f
        color = Color.argb(220, 255, 80, 80)
    }
    private val labelBg = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.argb(180, 0, 0, 0)
    }
    private val labelText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 24f
        isFakeBoldText = true
    }

    fun update(newMarks: List<MarkBox>, tappedId: Int?) {
        this.marks = newMarks
        this.lastTappedMarkId = tappedId
        postInvalidateOnAnimation()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        for (m in marks) {
            val paint = if (m.id == lastTappedMarkId) boxTapped else box
            canvas.drawRect(m.left.toFloat(), m.top.toFloat(),
                m.right.toFloat(), m.bottom.toFloat(), paint)
            val text = m.id.toString()
            val r = Rect()
            labelText.getTextBounds(text, 0, text.length, r)
            val pad = 6f
            canvas.drawRect(
                m.left.toFloat(),
                max(0f, m.top.toFloat() - r.height() - pad * 2),
                m.left.toFloat() + r.width() + pad * 2,
                max(r.height() + pad * 2, m.top.toFloat()),
                labelBg
            )
            canvas.drawText(
                text,
                m.left.toFloat() + pad,
                max(r.height().toFloat() + pad, m.top.toFloat() - pad),
                labelText
            )
        }
    }
}

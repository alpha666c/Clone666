package com.gameautopilot.app.vision

import com.gameautopilot.app.accessibility.NodeTreeReader.ClickableNode
import com.gameautopilot.app.core.MarkBox
import com.gameautopilot.app.core.MarkSource

/**
 * Combines a11y clickable nodes + OCR line boxes into a single mark table.
 * If a clickable a11y node and an OCR line overlap > 0.6 IoU, the a11y
 * one wins (richer label + already a tap target). Sorted by area DESC
 * and truncated to maxMarks; IDs assigned 1..N.
 */
object CandidateExtractor {

    fun build(
        clickables: List<ClickableNode>,
        ocrBoxes: List<OcrEngine.OcrLine>,
        maxMarks: Int = 80,
        iouThreshold: Float = 0.6f
    ): List<MarkBox> {
        val a11yMarks = clickables.mapIndexed { i, c ->
            MarkBox(
                id = -1, // assigned later
                left = c.left, top = c.top, right = c.right, bottom = c.bottom,
                source = MarkSource.A11Y, label = c.text.ifBlank { "a11y$i" }
            )
        }
        val ocrMarks = ocrBoxes.map { o ->
            MarkBox(
                id = -1,
                left = o.left, top = o.top, right = o.right, bottom = o.bottom,
                source = MarkSource.OCR, label = o.text
            )
        }

        val filteredOcr = ocrMarks.filter { ocr ->
            a11yMarks.none { a -> a.iou(ocr) > iouThreshold }
        }

        val combined = (a11yMarks + filteredOcr)
            .filter { it.width > 4 && it.height > 4 }
            .sortedByDescending { it.area }
            .take(maxMarks)

        return combined.mapIndexed { idx, m -> m.copy(id = idx + 1) }
    }
}

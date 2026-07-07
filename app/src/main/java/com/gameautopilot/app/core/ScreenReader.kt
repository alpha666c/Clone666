package com.gameautopilot.app.core

import android.graphics.Bitmap
import com.gameautopilot.app.accessibility.AutopilotAccessibilityService
import com.gameautopilot.app.accessibility.NodeTreeReader
import com.gameautopilot.app.capture.ScreenCaptureManager
import com.gameautopilot.app.capture.ScreenshotEncoder
import com.gameautopilot.app.data.BoardConfig
import com.gameautopilot.app.util.Logger
import com.gameautopilot.app.util.PerceptualHash
import com.gameautopilot.app.vision.BoardReader
import com.gameautopilot.app.vision.CandidateExtractor
import com.gameautopilot.app.vision.OcrEngine
import com.gameautopilot.app.vision.SetOfMarksOverlay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Seam between DecisionLoop and however the screen actually gets perceived.
 * DefaultScreenReader (MediaProjection + ML Kit OCR + accessibility tree) is
 * the only impl today; this interface is what would let e.g. an on-device
 * vision model or a different capture backend drop in without touching
 * DecisionLoop.
 */
interface ScreenReader {
    suspend fun read(useMarks: Boolean, board: BoardConfig? = null): ScreenSnapshot?
}

/** Default [ScreenReader]: MediaProjection capture + ML Kit OCR + a11y node tree + set-of-marks. */
class DefaultScreenReader(
    private val capture: ScreenCaptureManager,
    private val ocr: OcrEngine
) : ScreenReader {

    override suspend fun read(useMarks: Boolean, board: BoardConfig?): ScreenSnapshot? = withContext(Dispatchers.Default) {
        val bmp: Bitmap = capture.captureLatest() ?: return@withContext null
        val w = bmp.width
        val h = bmp.height
        val svc = AutopilotAccessibilityService.get()
        val fg = svc?.foregroundPackage
        val a11y = svc?.let { NodeTreeReader.read(it) }
            ?: NodeTreeReader.A11yResult(emptyList(), emptyList())
        val ocrResult = runCatching { ocr.extract(bmp) }.getOrElse {
            Logger.w("OCR failed: ${it.message}")
            OcrEngine.OcrResult(emptyList(), emptyList())
        }
        val marks = if (useMarks) {
            CandidateExtractor.build(a11y.clickables, ocrResult.boxes, maxMarks = 80)
        } else {
            emptyList()
        }
        val hash = PerceptualHash.dHash(bmp)
        val boardState = board?.let {
            runCatching { BoardReader.read(bmp, it) }.getOrElse { e ->
                Logger.w("Board read failed: ${e.message}")
                null
            }
        }
        val toEncode = if (useMarks && marks.isNotEmpty()) {
            SetOfMarksOverlay.annotate(bmp, marks)
        } else {
            bmp
        }
        val base64 = ScreenshotEncoder.encode(toEncode)
        if (toEncode !== bmp) toEncode.recycle()
        bmp.recycle()
        ScreenSnapshot(
            width = w,
            height = h,
            foregroundPackage = fg,
            ocrLines = ocrResult.lines,
            a11yLines = a11y.lines,
            marks = marks,
            screenshotBase64Jpeg = base64,
            perceptualHash = hash,
            boardState = boardState
        )
    }
}

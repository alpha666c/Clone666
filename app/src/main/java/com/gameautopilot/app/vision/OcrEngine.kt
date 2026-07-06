package com.gameautopilot.app.vision

import android.graphics.Bitmap
import com.gameautopilot.app.data.OcrScript
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.google.mlkit.vision.text.devanagari.DevanagariTextRecognizerOptions
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * ML Kit ships a separate on-device model per script family — a single
 * recognizer can't read e.g. both Latin and Japanese well, so the game's
 * script is a per-session choice (Settings) rather than autodetected.
 */
class OcrEngine(val script: OcrScript = OcrScript.LATIN) {
    private val recognizer: TextRecognizer = TextRecognition.getClient(optionsFor(script))

    data class OcrLine(
        val text: String,
        val left: Int,
        val top: Int,
        val right: Int,
        val bottom: Int
    )

    data class OcrResult(
        val lines: List<String>,
        val boxes: List<OcrLine>
    )

    suspend fun extract(bitmap: Bitmap): OcrResult = suspendCancellableCoroutine { cont ->
        val input = InputImage.fromBitmap(bitmap, 0)
        recognizer.process(input)
            .addOnSuccessListener { result ->
                val lines = mutableListOf<String>()
                val boxes = mutableListOf<OcrLine>()
                for (block in result.textBlocks) {
                    for (line in block.lines) {
                        val text = line.text.trim()
                        if (text.isEmpty()) continue
                        lines.add(text)
                        val b = line.boundingBox
                        if (b != null) {
                            boxes.add(OcrLine(text, b.left, b.top, b.right, b.bottom))
                        }
                    }
                }
                cont.resume(OcrResult(lines, boxes))
            }
            .addOnFailureListener { e ->
                if (!cont.isCancelled) cont.resumeWithException(e)
            }
    }

    fun close() {
        runCatching { recognizer.close() }
    }

    companion object {
        private fun optionsFor(script: OcrScript) = when (script) {
            OcrScript.LATIN -> TextRecognizerOptions.DEFAULT_OPTIONS
            OcrScript.CHINESE -> ChineseTextRecognizerOptions.Builder().build()
            OcrScript.JAPANESE -> JapaneseTextRecognizerOptions.Builder().build()
            OcrScript.KOREAN -> KoreanTextRecognizerOptions.Builder().build()
            OcrScript.DEVANAGARI -> DevanagariTextRecognizerOptions.Builder().build()
        }
    }
}

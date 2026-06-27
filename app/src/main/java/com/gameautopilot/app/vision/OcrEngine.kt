package com.gameautopilot.app.vision

import android.graphics.Bitmap
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class OcrEngine {
    private val recognizer: TextRecognizer =
        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

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
}

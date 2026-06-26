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

    suspend fun extract(bitmap: Bitmap): List<String> = suspendCancellableCoroutine { cont ->
        val input = InputImage.fromBitmap(bitmap, 0)
        recognizer.process(input)
            .addOnSuccessListener { result ->
                val lines = mutableListOf<String>()
                for (block in result.textBlocks) {
                    for (line in block.lines) {
                        val text = line.text.trim()
                        if (text.isNotEmpty()) lines.add(text)
                    }
                }
                cont.resume(lines)
            }
            .addOnFailureListener { e ->
                if (!cont.isCancelled) cont.resumeWithException(e)
            }
    }

    fun close() {
        runCatching { recognizer.close() }
    }
}

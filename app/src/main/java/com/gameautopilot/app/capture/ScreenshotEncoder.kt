package com.gameautopilot.app.capture

import android.graphics.Bitmap
import com.gameautopilot.app.util.BitmapUtils

object ScreenshotEncoder {
    const val DEFAULT_MAX_EDGE = 1024
    const val DEFAULT_JPEG_QUALITY = 80

    fun encode(
        bitmap: Bitmap,
        maxEdge: Int = DEFAULT_MAX_EDGE,
        jpegQuality: Int = DEFAULT_JPEG_QUALITY
    ): String {
        val scaled = BitmapUtils.scaleToMaxEdge(bitmap, maxEdge)
        val base64 = BitmapUtils.toJpegBase64(scaled, jpegQuality)
        if (scaled !== bitmap) scaled.recycle()
        return base64
    }
}

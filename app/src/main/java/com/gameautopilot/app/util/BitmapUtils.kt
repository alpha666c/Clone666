package com.gameautopilot.app.util

import android.graphics.Bitmap
import android.util.Base64
import java.io.ByteArrayOutputStream

object BitmapUtils {

    fun scaleToMaxEdge(src: Bitmap, maxEdge: Int): Bitmap {
        val w = src.width
        val h = src.height
        val longest = maxOf(w, h)
        if (longest <= maxEdge) return src
        val scale = maxEdge.toFloat() / longest
        val nw = (w * scale).toInt().coerceAtLeast(1)
        val nh = (h * scale).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(src, nw, nh, true)
    }

    fun toJpegBase64(bmp: Bitmap, quality: Int = 80): String {
        val baos = ByteArrayOutputStream(64 * 1024)
        bmp.compress(Bitmap.CompressFormat.JPEG, quality.coerceIn(20, 95), baos)
        return Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP)
    }
}

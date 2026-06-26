package com.gameautopilot.app.capture

import android.content.Context
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.os.Handler
import android.os.HandlerThread
import com.gameautopilot.app.util.Logger
import java.util.concurrent.atomic.AtomicBoolean

class ScreenCaptureManager(private val appContext: Context) {

    private var projection: MediaProjection? = null
    private var reader: ImageReader? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var handlerThread: HandlerThread? = null
    private var handler: Handler? = null
    private val running = AtomicBoolean(false)

    var width: Int = 0; private set
    var height: Int = 0; private set

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            Logger.i("MediaProjection stopped by system")
            tearDown()
        }
    }

    fun start(projection: MediaProjection, width: Int, height: Int, densityDpi: Int) {
        if (running.get()) tearDown()

        this.projection = projection
        this.width = width
        this.height = height

        handlerThread = HandlerThread("CapturePump").also { it.start() }
        handler = Handler(handlerThread!!.looper)

        projection.registerCallback(projectionCallback, handler)

        reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 3)
        virtualDisplay = projection.createVirtualDisplay(
            "AutopilotCapture",
            width, height, densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            reader!!.surface,
            null,
            handler
        )
        running.set(true)
        Logger.i("ScreenCapture started ${width}x$height")
    }

    fun captureLatest(): Bitmap? {
        val r = reader ?: return null
        val image = try {
            r.acquireLatestImage()
        } catch (t: Throwable) {
            Logger.w("acquireLatestImage failed", t)
            null
        } ?: return null

        return try {
            val plane = image.planes[0]
            val rowStride = plane.rowStride
            val pixelStride = plane.pixelStride
            val rowPadding = rowStride - pixelStride * width
            val paddedWidth = width + rowPadding / pixelStride
            val padded = Bitmap.createBitmap(paddedWidth, height, Bitmap.Config.ARGB_8888)
            padded.copyPixelsFromBuffer(plane.buffer)
            if (paddedWidth == width) {
                padded
            } else {
                val cropped = Bitmap.createBitmap(padded, 0, 0, width, height)
                padded.recycle()
                cropped
            }
        } catch (t: Throwable) {
            Logger.e("Failed to convert image to bitmap", t)
            null
        } finally {
            image.close()
        }
    }

    fun tearDown() {
        if (!running.compareAndSet(true, false) && projection == null) return
        runCatching { virtualDisplay?.release() }
        runCatching { reader?.close() }
        runCatching { projection?.unregisterCallback(projectionCallback) }
        runCatching { projection?.stop() }
        runCatching { handlerThread?.quitSafely() }
        virtualDisplay = null
        reader = null
        projection = null
        handler = null
        handlerThread = null
        Logger.i("ScreenCapture torn down")
    }
}

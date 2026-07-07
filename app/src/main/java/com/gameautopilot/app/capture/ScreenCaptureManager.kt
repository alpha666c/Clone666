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

    /**
     * Guards projection/reader/virtualDisplay/width/height together so a
     * rotation-triggered resize() can never race captureLatest() reading a
     * reader that resize just closed, or width/height that no longer match
     * the reader actually in use.
     */
    private val lock = Any()

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

    fun start(projection: MediaProjection, width: Int, height: Int, densityDpi: Int) = synchronized(lock) {
        if (running.get()) tearDownLocked()

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

    /**
     * Resizes the *existing* VirtualDisplay in place — used on rotation, where
     * the user shouldn't have to re-grant capture permission just because the
     * screen dimensions swapped. Deliberately does NOT call
     * MediaProjection#createVirtualDisplay again: a MediaProjection token is
     * single-use for that call — Android throws SecurityException ("don't
     * take multiple captures... on the same instance") the second time, no
     * matter how the resulting VirtualDisplay is torn down first. VirtualDisplay
     * itself supports resize()/setSurface() precisely for this case.
     */
    fun resize(newWidth: Int, newHeight: Int, densityDpi: Int) = synchronized(lock) {
        val vd = virtualDisplay
        if (!running.get() || vd == null) return@synchronized
        if (newWidth == width && newHeight == height) return@synchronized

        val oldReader = reader
        val newReader = ImageReader.newInstance(newWidth, newHeight, PixelFormat.RGBA_8888, 3)
        vd.resize(newWidth, newHeight, densityDpi)
        vd.surface = newReader.surface
        reader = newReader
        width = newWidth
        height = newHeight
        runCatching { oldReader?.close() }
        Logger.i("ScreenCapture resized to ${newWidth}x$newHeight")
    }

    fun captureLatest(): Bitmap? = synchronized(lock) {
        val r = reader ?: return@synchronized null
        val w = width
        val h = height
        val image = try {
            r.acquireLatestImage()
        } catch (t: Throwable) {
            Logger.w("acquireLatestImage failed", t)
            null
        } ?: return@synchronized null

        try {
            val plane = image.planes[0]
            val rowStride = plane.rowStride
            val pixelStride = plane.pixelStride
            val rowPadding = rowStride - pixelStride * w
            val paddedWidth = w + rowPadding / pixelStride
            val padded = Bitmap.createBitmap(paddedWidth, h, Bitmap.Config.ARGB_8888)
            padded.copyPixelsFromBuffer(plane.buffer)
            if (paddedWidth == w) {
                padded
            } else {
                val cropped = Bitmap.createBitmap(padded, 0, 0, w, h)
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

    fun tearDown() = synchronized(lock) { tearDownLocked() }

    private fun tearDownLocked() {
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

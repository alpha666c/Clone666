package com.gameautopilot.app.overlay

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import android.widget.TextView
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.gameautopilot.app.App
import com.gameautopilot.app.MainActivity
import com.gameautopilot.app.R
import com.gameautopilot.app.core.AutopilotController
import com.gameautopilot.app.core.AutopilotState
import com.gameautopilot.app.core.LoopPhase
import com.gameautopilot.app.data.SettingsRepository
import com.gameautopilot.app.util.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

class OverlayService : Service() {

    private lateinit var controller: AutopilotController
    private lateinit var wm: WindowManager
    private var overlayView: View? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var stateJob: Job? = null
    private var debugFrameJob: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var debugOverlayView: DebugOverlayView? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        controller = AutopilotController.get(this)
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        controller.onConfigurationChanged(newConfig)
    }

    /**
     * The game we're driving owns the foreground window, not us — a window-level
     * FLAG_KEEP_SCREEN_ON on our overlay wouldn't stop the display from sleeping.
     * SCREEN_BRIGHT_WAKE_LOCK is deprecated but is the only wake-lock flag that
     * actually keeps the display lit; a PARTIAL_WAKE_LOCK alone would keep the CPU
     * running while the screen (and therefore MediaProjection's captured frames) go dark.
     */
    @Suppress("DEPRECATION")
    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(
            PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ON_AFTER_RELEASE,
            "GameAutopilot:autopilotScreen"
        ).apply { setReferenceCounted(false); acquire(MAX_WAKE_LOCK_MS) }
    }

    private fun releaseWakeLock() {
        runCatching { wakeLock?.takeIf { it.isHeld }?.release() }
        wakeLock = null
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_ATTACH_PROJECTION -> handleAttach(intent)
            ACTION_QUIT -> {
                handleQuit()
                return START_NOT_STICKY
            }
            ACTION_CANCEL -> {
                handleQuit()
                return START_NOT_STICKY
            }
        }
        return START_STICKY
    }

    /**
     * Order matters here and is easy to get backwards (this took three tries
     * to get right, confirmed against real device logs each time):
     * 1. By the time this runs, the user has already granted screen-capture
     *    consent (resultCode/data prove it) — that alone satisfies the check
     *    inside startForeground() that a mediaProjection-typed FGS needs a
     *    live capture grant to exist.
     * 2. MediaProjectionManager.getMediaProjection() has its OWN, separate
     *    requirement in the other direction: it throws SecurityException
     *    ("Media projections require a foreground service of type ...")
     *    unless the mediaProjection-typed FGS is *already running* when
     *    it's called.
     * So: startForeground() must run before getMediaProjection(), not after.
     * Getting this backwards doesn't show up as this SecurityException
     * directly in the crash log if it's caught here — it surfaces as the
     * much more confusing ForegroundServiceDidNotStartInTimeException,
     * because stopSelf() after catching it still counts as "stopped without
     * ever calling startForeground."
     */
    private fun handleAttach(intent: Intent) {
        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
        val data: Intent? = intent.getParcelableExtra(EXTRA_RESULT_DATA)
        if (data == null) {
            Logger.w("No projection data; quitting")
            handleQuit()
            return
        }
        startForegroundCompat()
        val projection = try {
            controller.registerProjection(resultCode, data)
        } catch (t: Throwable) {
            Logger.e("Failed to register projection", t)
            handleQuit()
            return
        }
        try {
            controller.startCapture(projection)
        } catch (t: Throwable) {
            Logger.e("Failed to start capture", t)
            handleQuit()
            return
        }
        acquireWakeLock()
        showOverlay()
        showDebugOverlayIfEnabled()
        observeState()
        launchTargetGame()
    }

    private fun handleQuit() {
        controller.quit()
        removeOverlay()
        removeDebugOverlay()
        releaseWakeLock()
        stateJob?.cancel()
        debugFrameJob?.cancel()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun showDebugOverlayIfEnabled() {
        if (!SettingsRepository(this).current().showDebugOverlay) return
        if (debugOverlayView != null) return
        val view = DebugOverlayView(this)
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        )
        runCatching { wm.addView(view, params) }.onFailure {
            Logger.w("Failed to add debug overlay", it)
            return
        }
        debugOverlayView = view
        debugFrameJob?.cancel()
        debugFrameJob = scope.launch {
            controller.debugFrame.collectLatest { frame ->
                debugOverlayView?.update(frame.marks, frame.lastTappedMarkId)
            }
        }
    }

    private fun removeDebugOverlay() {
        debugFrameJob?.cancel()
        debugFrameJob = null
        val v = debugOverlayView ?: return
        runCatching { wm.removeView(v) }
        debugOverlayView = null
    }

    private fun launchTargetGame() {
        val pkg = controller.currentGame?.packageName ?: return
        val launch = packageManager.getLaunchIntentForPackage(pkg)
        if (launch == null) {
            Logger.w("No launch intent for $pkg")
            return
        }
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        startActivity(launch)
    }

    private fun startForegroundCompat() {
        val notif = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                App.NOTIF_ID_OVERLAY,
                notif,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            )
        } else {
            startForeground(App.NOTIF_ID_OVERLAY, notif)
        }
    }

    private fun buildNotification(): Notification {
        val openApp = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stopSelfPI = PendingIntent.getService(
            this, 1,
            Intent(this, OverlayService::class.java).setAction(ACTION_QUIT),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, App.NOTIF_CHANNEL_OVERLAY)
            .setSmallIcon(R.drawable.ic_play)
            .setContentTitle(getString(R.string.notif_overlay_title))
            .setContentText(getString(R.string.notif_overlay_text))
            .setContentIntent(openApp)
            .addAction(R.drawable.ic_close, getString(R.string.quit), stopSelfPI)
            .setOngoing(true)
            .setSilent(true)
            .build()
    }

    private fun showOverlay() {
        if (overlayView != null) return
        val view = LayoutInflater.from(this).inflate(R.layout.overlay_control, null, false)
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 32
            y = 120
        }

        val startBtn = view.findViewById<ImageView>(R.id.startBtn)
        val stopBtn = view.findViewById<ImageView>(R.id.stopBtn)
        val closeBtn = view.findViewById<ImageView>(R.id.closeBtn)

        startBtn.setOnClickListener {
            if (!controller.start()) {
                renderError(controller.state.value)
            }
        }
        stopBtn.setOnClickListener { controller.stop() }
        closeBtn.setOnClickListener { handleQuit() }

        attachDrag(view, params)
        wm.addView(view, params)
        overlayView = view
    }

    private fun attachDrag(view: View, params: WindowManager.LayoutParams) {
        var initialX = 0
        var initialY = 0
        var touchX = 0f
        var touchY = 0f
        view.setOnTouchListener { _, ev ->
            when (ev.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    touchX = ev.rawX
                    touchY = ev.rawY
                    false
                }
                MotionEvent.ACTION_MOVE -> {
                    params.x = initialX + (ev.rawX - touchX).roundToInt()
                    params.y = initialY + (ev.rawY - touchY).roundToInt()
                    runCatching { wm.updateViewLayout(view, params) }
                    false
                }
                else -> false
            }
        }
    }

    private fun observeState() {
        stateJob?.cancel()
        stateJob = scope.launch {
            controller.state.collectLatest { render(it) }
        }
    }

    private fun render(state: AutopilotState) {
        val v = overlayView ?: return
        val dot = v.findViewById<View>(R.id.statusDot)
        val text = v.findViewById<TextView>(R.id.statusText)
        when (state) {
            is AutopilotState.Idle -> {
                dot.setBackgroundColor(ContextCompat.getColor(this, R.color.status_idle))
                text.setText(R.string.overlay_state_idle)
            }
            AutopilotState.Ready -> {
                dot.setBackgroundColor(ContextCompat.getColor(this, R.color.status_idle))
                text.setText(R.string.overlay_state_idle)
            }
            is AutopilotState.Running -> {
                when (state.phase) {
                    LoopPhase.IDLE -> {
                        dot.setBackgroundColor(ContextCompat.getColor(this, R.color.status_idle))
                        text.text = state.note ?: getString(R.string.overlay_state_running)
                    }
                    LoopPhase.THINKING -> {
                        dot.setBackgroundColor(ContextCompat.getColor(this, R.color.status_thinking))
                        text.setText(R.string.overlay_state_thinking)
                    }
                    LoopPhase.ACTING -> {
                        dot.setBackgroundColor(ContextCompat.getColor(this, R.color.status_running))
                        text.text = state.note ?: getString(R.string.overlay_state_acting)
                    }
                    LoopPhase.ERROR -> {
                        dot.setBackgroundColor(ContextCompat.getColor(this, R.color.status_error))
                        text.text = state.note ?: getString(R.string.overlay_state_error)
                    }
                }
            }
            is AutopilotState.Error -> {
                dot.setBackgroundColor(ContextCompat.getColor(this, R.color.status_error))
                text.text = state.message.take(40)
            }
        }
    }

    private fun renderError(state: AutopilotState) {
        if (state is AutopilotState.Error) render(state)
    }

    private fun removeOverlay() {
        val v = overlayView ?: return
        runCatching { wm.removeView(v) }
        overlayView = null
    }

    override fun onDestroy() {
        scope.cancel()
        removeOverlay()
        removeDebugOverlay()
        releaseWakeLock()
        super.onDestroy()
    }

    companion object {
        private const val MAX_WAKE_LOCK_MS = 6 * 60 * 60 * 1000L // 6h safety cap; renewed on each attach
        const val ACTION_ATTACH_PROJECTION = "com.gameautopilot.app.action.ATTACH"
        const val ACTION_QUIT = "com.gameautopilot.app.action.QUIT"
        const val ACTION_CANCEL = "com.gameautopilot.app.action.CANCEL"
        const val EXTRA_RESULT_CODE = "extra_result_code"
        const val EXTRA_RESULT_DATA = "extra_result_data"

        fun attachProjection(context: Context, resultCode: Int, data: Intent) {
            val intent = Intent(context, OverlayService::class.java)
                .setAction(ACTION_ATTACH_PROJECTION)
                .putExtra(EXTRA_RESULT_CODE, resultCode)
                .putExtra(EXTRA_RESULT_DATA, data)
            ContextCompat.startForegroundService(context, intent)
        }

        fun cancel(context: Context) {
            val intent = Intent(context, OverlayService::class.java)
                .setAction(ACTION_CANCEL)
            context.startService(intent)
        }
    }
}

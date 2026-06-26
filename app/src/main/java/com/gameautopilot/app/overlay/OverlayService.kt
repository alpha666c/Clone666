package com.gameautopilot.app.overlay

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
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
import com.gameautopilot.app.ui.ProjectionRequestActivity
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

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        controller = AutopilotController.get(this)
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PREPARE -> handlePrepare(intent.getStringExtra(EXTRA_GAME_ID))
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

    private fun handlePrepare(gameId: String?) {
        startForegroundCompat()
        // Always request a fresh projection (token can't be reused across runs).
        startActivity(
            Intent(this, ProjectionRequestActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    private fun handleAttach(intent: Intent) {
        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
        val data: Intent? = intent.getParcelableExtra(EXTRA_RESULT_DATA)
        if (data == null) {
            Logger.w("No projection data; quitting")
            handleQuit()
            return
        }
        try {
            controller.attachProjection(resultCode, data)
        } catch (t: Throwable) {
            Logger.e("Failed to attach projection", t)
            handleQuit()
            return
        }
        showOverlay()
        observeState()
        launchTargetGame()
    }

    private fun handleQuit() {
        controller.quit()
        removeOverlay()
        stateJob?.cancel()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
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
        super.onDestroy()
    }

    companion object {
        const val ACTION_PREPARE = "com.gameautopilot.app.action.PREPARE"
        const val ACTION_ATTACH_PROJECTION = "com.gameautopilot.app.action.ATTACH"
        const val ACTION_QUIT = "com.gameautopilot.app.action.QUIT"
        const val ACTION_CANCEL = "com.gameautopilot.app.action.CANCEL"
        const val EXTRA_GAME_ID = "extra_game_id"
        const val EXTRA_RESULT_CODE = "extra_result_code"
        const val EXTRA_RESULT_DATA = "extra_result_data"

        fun prepareLaunch(context: Context, gameId: String) {
            val intent = Intent(context, OverlayService::class.java)
                .setAction(ACTION_PREPARE)
                .putExtra(EXTRA_GAME_ID, gameId)
            ContextCompat.startForegroundService(context, intent)
        }

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

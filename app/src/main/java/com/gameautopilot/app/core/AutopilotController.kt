package com.gameautopilot.app.core

import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.util.DisplayMetrics
import android.view.WindowManager
import com.gameautopilot.app.App
import com.gameautopilot.app.brain.Brain
import com.gameautopilot.app.brain.BrainFactory
import com.gameautopilot.app.brain.BraveSearchProvider
import com.gameautopilot.app.capture.ScreenCaptureManager
import com.gameautopilot.app.capture.ScreenshotEncoder
import com.gameautopilot.app.data.BoardConfig
import com.gameautopilot.app.data.Game
import com.gameautopilot.app.data.GameMemoryStore
import com.gameautopilot.app.data.Settings
import com.gameautopilot.app.data.SettingsRepository
import com.gameautopilot.app.util.Logger
import com.gameautopilot.app.vision.OcrEngine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class AutopilotController private constructor(private val appContext: Context) {

    private val settingsRepo = SettingsRepository(appContext)
    private val capture = ScreenCaptureManager(appContext)
    private var ocr = OcrEngine(settingsRepo.current().ocrScript)
    private val recent = ActionRing()
    private val rate = RateLimiter(maxPerMinute = settingsRepo.current().maxActionsPerMinute)
    private val dispatcher: ActionExecutor = ActionDispatcher(
        screenWidth = { capture.width },
        screenHeight = { capture.height },
        board = { currentGame?.board }
    )
    private var screenReader: ScreenReader = DefaultScreenReader(capture, ocr)
    private val cycleLog = CycleLog(appContext).apply {
        setEnabled(settingsRepo.current().logCycles)
    }
    private val memoryStore = GameMemoryStore(appContext)

    @Volatile var currentGame: Game? = null
        private set

    @Volatile private var brain: Brain? = null
    @Volatile private var loopJob: Job? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _state = MutableStateFlow<AutopilotState>(AutopilotState.Idle())
    val state: StateFlow<AutopilotState> = _state.asStateFlow()

    private val _debugFrame = MutableSharedFlow<DebugFrame>(replay = 1, extraBufferCapacity = 1)
    val debugFrame: SharedFlow<DebugFrame> = _debugFrame.asSharedFlow()

    private var fastPath: A11yFastPath? = null

    fun settings(): Settings = settingsRepo.current()
    fun settingsRepo(): SettingsRepository = settingsRepo
    fun memoryStore(): GameMemoryStore = memoryStore

    fun selectGame(game: Game) {
        currentGame = game
        rate.maxPerMinute = settingsRepo.current().maxActionsPerMinute
        rate.reset()
        recent.clear()
        Logger.i("Selected game: ${game.name} (${game.packageName})")
    }

    /**
     * Split from the capture setup below on purpose: the caller (OverlayService)
     * must call startForeground() with type mediaProjection right after this
     * returns, before doing anything else — the OS's "didn't call
     * startForeground in time" watchdog runs from the moment
     * startForegroundService() was invoked, and VirtualDisplay creation
     * (in startCapture) can be slow enough on some devices to blow that budget
     * if it's allowed to run first.
     */
    fun registerProjection(resultCode: Int, data: Intent): MediaProjection {
        val mpm = appContext.getSystemService(Context.MEDIA_PROJECTION_SERVICE)
            as MediaProjectionManager
        return mpm.getMediaProjection(resultCode, data)
    }

    fun startCapture(projection: MediaProjection) {
        val (w, h, dpi) = displayMetrics()
        capture.start(projection, w, h, dpi)
        _state.value = AutopilotState.Ready
        Logger.i("Projection attached, capture ${w}x$h @$dpi dpi")
    }

    fun isProjectionReady(): Boolean = capture.width > 0 && capture.height > 0

    fun start(): Boolean {
        val game = currentGame ?: run {
            _state.value = AutopilotState.Error("No game selected")
            return false
        }
        val s = settingsRepo.current()
        if (!s.hasApiKey()) {
            _state.value = AutopilotState.Error("API key not set")
            return false
        }
        if (!isProjectionReady()) {
            _state.value = AutopilotState.Error("Screen capture not ready")
            return false
        }
        if (loopJob?.isActive == true) return true

        rate.maxPerMinute = s.maxActionsPerMinute
        cycleLog.setEnabled(s.logCycles)
        brain = BrainFactory.create(s)
        if (ocr.script != s.ocrScript) {
            ocr.close()
            ocr = OcrEngine(s.ocrScript)
            screenReader = DefaultScreenReader(capture, ocr)
        }
        val useMarks = s.useSetOfMarks
        fastPath = if (s.useFastPath) A11yFastPath() else null
        val loop = DecisionLoop(
            takeSnapshot = { screenReader.read(useMarks, game.board) },
            brain = brain!!,
            dispatcher = dispatcher,
            recent = recent,
            rate = rate,
            baseTickIntervalMs = game.tickIntervalMs,
            gameName = game.name,
            gamePackage = game.packageName,
            gameSystemPrompt = game.systemPrompt,
            onlyActOnTargetPackage = s.onlyActOnTarget,
            autoRecoverInterruptions = s.autoRecoverInterruptions,
            onRelaunch = { relaunchTargetGame() },
            initialMemory = memoryStore.get(game.id),
            onMemoryUpdate = { mem -> scope.launch { memoryStore.set(game.id, mem) } },
            onState = { phase, note -> updatePhase(phase, note) },
            onCycle = { rec ->
                cycleLog.write(
                    CycleLog.CycleEntry(
                        timestampMs = System.currentTimeMillis(),
                        gamePackage = game.packageName,
                        foregroundPackage = rec.snapshot.foregroundPackage,
                        hash = rec.snapshot.perceptualHash,
                        deltaSincePrev = rec.deltaSincePrev,
                        markCount = rec.snapshot.marks.size,
                        thought = rec.thought,
                        actions = rec.actionLabels,
                        dispatchOk = rec.dispatchOk
                    )
                )
            },
            fastPath = fastPath,
            onDebugFrame = { marks, lastTapped -> _debugFrame.tryEmit(DebugFrame(marks, lastTapped)) },
            webSearch = if (s.hasWebSearchKey()) BraveSearchProvider(s.webSearchApiKey) else null
        )
        _state.value = AutopilotState.Running(LoopPhase.IDLE, null)
        loopJob = scope.launch {
            while (isActive) {
                val nextDelay = try {
                    loop.tick()
                } catch (c: CancellationException) {
                    // stop()/quit() cancel this job while a tick is suspended (mid-brain-call,
                    // mid-delay, etc.) — that surfaces here as a CancellationException. Swallowing
                    // it as a generic Throwable was reporting a normal stop as "Tick crashed" and
                    // briefly clobbering the state we'd just set to Idle with an Error; rethrowing
                    // lets structured concurrency finish cancelling this coroutine as intended.
                    throw c
                } catch (t: Throwable) {
                    Logger.e("Tick crashed", t)
                    _state.value = AutopilotState.Error(t.message ?: "tick crashed")
                    game.tickIntervalMs
                }
                delay(nextDelay.coerceAtLeast(50L))
            }
        }
        Logger.i("Autopilot started for ${game.name}")
        return true
    }

    /**
     * Self-calibration: grabs the current live frame and asks the configured
     * vision brain to locate a grid board in it, instead of making the user
     * hand-measure percentages. Works whether or not the tick loop is running —
     * only needs a live capture and an API key — so it can be triggered right
     * after launching a game, before ever pressing Start.
     */
    suspend fun calibrateBoard(): CalibrationResult {
        val game = currentGame ?: return CalibrationResult.Error("No game selected")
        if (!isProjectionReady()) return CalibrationResult.Error("Screen capture not ready")
        val s = settingsRepo.current()
        if (!s.hasApiKey()) return CalibrationResult.Error("API key not set")
        val activeBrain = brain ?: BrainFactory.create(s)
        val bmp = capture.captureLatest() ?: return CalibrationResult.Error("No frame available")
        val base64 = try {
            ScreenshotEncoder.encode(bmp)
        } finally {
            bmp.recycle()
        }
        val detection = try {
            activeBrain.detectBoard(base64)
        } catch (t: Throwable) {
            Logger.e("Board calibration failed", t)
            return CalibrationResult.Error(t.message ?: "calibration failed")
        } ?: return CalibrationResult.NotFound
        val cfg = detection.toBoardConfig()
            ?: return CalibrationResult.Error("Model returned an invalid board shape (${detection.rows}x${detection.cols})")
        val updated = game.copy(board = cfg)
        App.get().gameRepository.upsert(updated)
        currentGame = updated
        Logger.i("Board calibrated for ${game.name}: ${cfg.rows}x${cfg.cols}")
        return CalibrationResult.Success(cfg)
    }

    sealed class CalibrationResult {
        data class Success(val config: BoardConfig) : CalibrationResult()
        data object NotFound : CalibrationResult()
        data class Error(val message: String) : CalibrationResult()
    }

    /**
     * Brings the target game back to the foreground directly. Used both for the
     * initial launch after attaching the projection, and as a recovery step when
     * the game has been fully backgrounded (e.g. kicked to the launcher) — in
     * that case BACK has nothing to go back to and just does nothing forever.
     */
    fun relaunchTargetGame() {
        val pkg = currentGame?.packageName ?: return
        val launch = appContext.packageManager.getLaunchIntentForPackage(pkg)
        if (launch == null) {
            Logger.w("No launch intent for $pkg")
            return
        }
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        appContext.startActivity(launch)
    }

    fun stop() {
        loopJob?.cancel()
        loopJob = null
        _state.value = if (isProjectionReady()) AutopilotState.Ready else AutopilotState.Idle()
        Logger.i("Autopilot stopped")
    }

    fun quit() {
        stop()
        capture.tearDown()
        currentGame = null
        brain = null
        fastPath = null
        _state.value = AutopilotState.Idle()
        recent.clear()
        Logger.i("Autopilot quit")
    }

    private fun updatePhase(phase: LoopPhase, note: String?) {
        val s = _state.value
        if (s is AutopilotState.Running) {
            _state.value = AutopilotState.Running(phase, note)
        }
    }

    private fun displayMetrics(): Triple<Int, Int, Int> {
        val wm = appContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        return if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            val metrics = wm.currentWindowMetrics
            val bounds = metrics.bounds
            val density = appContext.resources.displayMetrics.densityDpi
            Triple(bounds.width(), bounds.height(), density)
        } else {
            val dm = DisplayMetrics()
            @Suppress("DEPRECATION")
            wm.defaultDisplay.getRealMetrics(dm)
            Triple(dm.widthPixels, dm.heightPixels, dm.densityDpi)
        }
    }

    /**
     * Recreates the capture VirtualDisplay at the post-rotation screen size
     * without dropping the MediaProjection consent — called from
     * OverlayService.onConfigurationChanged(). Marks (a11y/OCR boxes) are
     * naturally rebuilt fresh from the next captured frame, so nothing else
     * needs to be reset.
     */
    fun onConfigurationChanged(@Suppress("UNUSED_PARAMETER") cfg: Configuration) {
        if (!isProjectionReady()) return
        val (w, h, dpi) = displayMetrics()
        capture.resize(w, h, dpi)
        Logger.i("Configuration changed — resized capture to ${w}x$h")
    }

    companion object {
        @Volatile private var INSTANCE: AutopilotController? = null
        fun get(context: Context): AutopilotController {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: AutopilotController(context.applicationContext).also { INSTANCE = it }
            }
        }
    }
}

sealed class AutopilotState {
    data class Idle(val note: String? = null) : AutopilotState()
    data object Ready : AutopilotState()
    data class Running(val phase: LoopPhase, val note: String?) : AutopilotState()
    data class Error(val message: String) : AutopilotState()
}

data class DebugFrame(val marks: List<MarkBox>, val lastTappedMarkId: Int?)

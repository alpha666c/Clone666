package com.gameautopilot.app.core

import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.util.DisplayMetrics
import android.view.WindowManager
import com.gameautopilot.app.accessibility.AutopilotAccessibilityService
import com.gameautopilot.app.accessibility.NodeTreeReader
import com.gameautopilot.app.brain.Brain
import com.gameautopilot.app.brain.BrainFactory
import com.gameautopilot.app.capture.ScreenCaptureManager
import com.gameautopilot.app.capture.ScreenshotEncoder
import com.gameautopilot.app.data.Game
import com.gameautopilot.app.data.GameMemoryStore
import com.gameautopilot.app.data.Settings
import com.gameautopilot.app.data.SettingsRepository
import com.gameautopilot.app.util.Logger
import com.gameautopilot.app.util.PerceptualHash
import com.gameautopilot.app.vision.CandidateExtractor
import com.gameautopilot.app.vision.OcrEngine
import com.gameautopilot.app.vision.SetOfMarksOverlay
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class AutopilotController private constructor(private val appContext: Context) {

    private val settingsRepo = SettingsRepository(appContext)
    private val capture = ScreenCaptureManager(appContext)
    private val ocr = OcrEngine()
    private val recent = ActionRing()
    private val rate = RateLimiter(maxPerMinute = settingsRepo.current().maxActionsPerMinute)
    private val dispatcher = ActionDispatcher(
        screenWidth = { capture.width },
        screenHeight = { capture.height }
    )
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

    fun attachProjection(resultCode: Int, data: Intent) {
        val mpm = appContext.getSystemService(Context.MEDIA_PROJECTION_SERVICE)
            as MediaProjectionManager
        val projection: MediaProjection = mpm.getMediaProjection(resultCode, data)
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
        val useMarks = s.useSetOfMarks
        val loop = DecisionLoop(
            takeSnapshot = { buildSnapshot(useMarks) },
            brain = brain!!,
            dispatcher = dispatcher,
            recent = recent,
            rate = rate,
            baseTickIntervalMs = game.tickIntervalMs,
            gameName = game.name,
            gamePackage = game.packageName,
            gameSystemPrompt = game.systemPrompt,
            onlyActOnTargetPackage = s.onlyActOnTarget,
            initialMemory = memoryStore.get(game.id),
            onMemoryUpdate = { text -> scope.launch { memoryStore.set(game.id, text) } },
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
            }
        )
        _state.value = AutopilotState.Running(LoopPhase.IDLE, null)
        loopJob = scope.launch {
            while (isActive) {
                val nextDelay = try {
                    loop.tick()
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

    private suspend fun buildSnapshot(useMarks: Boolean): ScreenSnapshot? =
        withContext(Dispatchers.Default) {
            val bmp: Bitmap = capture.captureLatest() ?: return@withContext null
            val w = bmp.width
            val h = bmp.height
            val svc = AutopilotAccessibilityService.get()
            val fg = svc?.foregroundPackage
            val a11y = svc?.let { NodeTreeReader.read(it) }
                ?: NodeTreeReader.A11yResult(emptyList(), emptyList())
            val ocrResult = runCatching { ocr.extract(bmp) }.getOrElse {
                Logger.w("OCR failed: ${it.message}")
                OcrEngine.OcrResult(emptyList(), emptyList())
            }
            val marks = if (useMarks) {
                CandidateExtractor.build(a11y.clickables, ocrResult.boxes, maxMarks = 80)
            } else {
                emptyList()
            }
            val hash = PerceptualHash.dHash(bmp)
            val toEncode = if (useMarks && marks.isNotEmpty()) {
                SetOfMarksOverlay.annotate(bmp, marks)
            } else {
                bmp
            }
            val base64 = ScreenshotEncoder.encode(toEncode)
            if (toEncode !== bmp) toEncode.recycle()
            bmp.recycle()
            ScreenSnapshot(
                width = w,
                height = h,
                foregroundPackage = fg,
                ocrLines = ocrResult.lines,
                a11yLines = a11y.lines,
                marks = marks,
                screenshotBase64Jpeg = base64,
                perceptualHash = hash
            )
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

    fun onConfigurationChanged(@Suppress("UNUSED_PARAMETER") cfg: Configuration) {
        // Reserved hook for rotation handling.
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

package com.gameautopilot.app.core

import com.gameautopilot.app.brain.Brain
import com.gameautopilot.app.brain.BrainContext
import com.gameautopilot.app.brain.BrainException
import com.gameautopilot.app.brain.WebSearchProvider
import com.gameautopilot.app.data.GameMemory
import com.gameautopilot.app.util.Logger
import com.gameautopilot.app.util.PerceptualHash
import kotlinx.coroutines.delay

/**
 * One tick = capture snapshot → call brain → dispatch returned actions.
 * Action.Wait extends the delay BEFORE the next tick (it is not a no-op).
 *
 * Stuck-state guard: tracks the last K perceptual hashes; if Hamming
 * distance is < STUCK_DELTA across all of them, asks the brain for a
 * recovery action with stuckHint set, then trips the loop into
 * LoopPhase.ERROR after STUCK_TRIP consecutive stuck ticks.
 */
class DecisionLoop(
    private val takeSnapshot: suspend () -> ScreenSnapshot?,
    private val brain: Brain,
    private val dispatcher: ActionExecutor,
    private val recent: ActionRing,
    private val rate: RateLimiter,
    private val baseTickIntervalMs: Long,
    private val gameName: String,
    private val gamePackage: String,
    private val gameSystemPrompt: String,
    private val onlyActOnTargetPackage: Boolean,
    private val autoRecoverInterruptions: Boolean = true,
    initialMemory: GameMemory = GameMemory(),
    private val onMemoryUpdate: (GameMemory) -> Unit = {},
    private val onState: suspend (LoopPhase, String?) -> Unit,
    private val onCycle: (CycleRecord) -> Unit = {},
    private val fastPath: A11yFastPath? = null,
    private val onDebugFrame: (List<MarkBox>, Int?) -> Unit = { _, _ -> },
    private val webSearch: WebSearchProvider? = null
) {

    private val recentHashes = ArrayDeque<Long>(STUCK_WINDOW)
    private var stuckCount = 0
    private var interruptionAttempts = 0
    @Volatile private var memory: GameMemory = initialMemory
    @Volatile private var pendingResearchNotes: String? = null

    /** @return delay (ms) before the next tick should run. */
    suspend fun tick(): Long {
        val snapshot = takeSnapshot()
        if (snapshot == null) {
            onState(LoopPhase.IDLE, "No frame available")
            return baseTickIntervalMs
        }

        if (onlyActOnTargetPackage &&
            snapshot.foregroundPackage != null &&
            snapshot.foregroundPackage != gamePackage
        ) {
            if (autoRecoverInterruptions && interruptionAttempts < MAX_INTERRUPTION_ATTEMPTS) {
                interruptionAttempts++
                val recovery = InterruptionRecovery.findRecoveryAction(snapshot.marks)
                Logger.i(
                    "Off-target foreground=${snapshot.foregroundPackage} — " +
                        "recovery attempt $interruptionAttempts/${MAX_INTERRUPTION_ATTEMPTS}: ${recovery.shortLabel()}"
                )
                onState(LoopPhase.ACTING, "Dismissing interruption…")
                val (labels, oks, extraWaitMs) = dispatchActions(listOf(recovery), snapshot)
                onCycle(
                    CycleRecord(
                        snapshot,
                        "(auto-recovery: off-target foreground=${snapshot.foregroundPackage})",
                        labels, oks, -1
                    )
                )
                return (baseTickIntervalMs + extraWaitMs).coerceAtLeast(800L)
            }
            Logger.d("Foreground=${snapshot.foregroundPackage}, expected=$gamePackage — skipping tick")
            onState(LoopPhase.IDLE, "Waiting for ${gamePackage.substringAfterLast('.')}")
            return baseTickIntervalMs
        }
        interruptionAttempts = 0

        val delta = recentHashes.lastOrNull()?.let { PerceptualHash.hamming(it, snapshot.perceptualHash) } ?: -1
        recordHash(snapshot.perceptualHash)
        val stuck = isStuck()
        val stuckHint = if (stuck) {
            stuckCount++
            "screen has not changed across the last ${recentHashes.size} ticks — try a different action, or BACK."
        } else {
            stuckCount = 0
            null
        }

        if (stuckCount >= STUCK_TRIP) {
            onState(LoopPhase.ERROR, "Stuck — same screen $stuckCount ticks; pausing")
            stuckCount = 0
            return baseTickIntervalMs.coerceAtLeast(4_000L)
        }

        if (stuckHint == null) {
            val fastActions = fastPath?.tryFastPath(snapshot, delta)
            if (fastActions != null) {
                onState(LoopPhase.ACTING, "fast-path")
                val (labels, oks, extraWaitMs) = dispatchActions(fastActions, snapshot)
                onDebugFrame(snapshot.marks, (fastActions.firstOrNull() as? Action.TapMark)?.markId)
                onCycle(CycleRecord(snapshot, "(fast-path repeat)", labels, oks, delta))
                return baseTickIntervalMs + extraWaitMs
            }
        }

        onState(LoopPhase.THINKING, null)
        val researchNotes = pendingResearchNotes
        pendingResearchNotes = null
        val ctx = BrainContext(
            gameName = gameName,
            gamePackage = gamePackage,
            gameSystemPrompt = gameSystemPrompt,
            screenWidth = snapshot.width,
            screenHeight = snapshot.height,
            screenshotBase64Jpeg = snapshot.screenshotBase64Jpeg,
            ocrLines = snapshot.ocrLines,
            a11yLines = snapshot.a11yLines,
            marks = snapshot.marks,
            recentActionLabels = recent.snapshot(),
            stuckHint = stuckHint,
            gameMemory = memory,
            researchNotes = researchNotes
        )

        val decision = try {
            brain.decide(ctx)
        } catch (e: BrainException) {
            Logger.e("Brain error: ${e.message}", e)
            onState(LoopPhase.ERROR, e.message ?: "brain error")
            onCycle(CycleRecord(snapshot, "(brain error: ${e.message})", emptyList(), emptyList(), delta))
            return baseTickIntervalMs.coerceAtLeast(2_000L)
        } catch (t: Throwable) {
            Logger.e("Unexpected brain failure", t)
            onState(LoopPhase.ERROR, t.message ?: "error")
            onCycle(CycleRecord(snapshot, "(crash: ${t.message})", emptyList(), emptyList(), delta))
            return baseTickIntervalMs.coerceAtLeast(2_000L)
        }

        val newMemory = decision.memoryUpdate
        if (newMemory != null && newMemory != memory) {
            memory = newMemory
            onMemoryUpdate(newMemory)
        }

        if (decision.actions.isEmpty()) {
            onState(LoopPhase.IDLE, decision.thought.ifBlank { "no actions" })
            onCycle(CycleRecord(snapshot, decision.thought, emptyList(), emptyList(), delta))
            return baseTickIntervalMs
        }

        val searchAction = decision.actions.firstOrNull { it is Action.WebSearch } as? Action.WebSearch
        if (searchAction != null) {
            onState(LoopPhase.THINKING, "Researching: ${searchAction.query.take(60)}")
            pendingResearchNotes = if (webSearch != null) {
                runCatching { webSearch.search(searchAction.query) }
                    .getOrElse { "Search failed: ${it.message}" }
            } else {
                "(web search unavailable — no provider configured)"
            }
            onCycle(
                CycleRecord(
                    snapshot, decision.thought,
                    listOf(searchAction.shortLabel()), listOf(webSearch != null), delta
                )
            )
            return baseTickIntervalMs
        }

        onState(LoopPhase.ACTING, decision.thought.take(80))

        val (labels, oks, extraWaitMs) = dispatchActions(decision.actions, snapshot)
        fastPath?.recordBrainDispatch(decision.actions, snapshot.marks)
        onDebugFrame(snapshot.marks, (decision.actions.firstOrNull { it is Action.TapMark } as? Action.TapMark)?.markId)
        onCycle(CycleRecord(snapshot, decision.thought, labels, oks, delta))

        return baseTickIntervalMs + extraWaitMs
    }

    private suspend fun dispatchActions(
        actions: List<Action>,
        snapshot: ScreenSnapshot
    ): Triple<List<String>, List<Boolean>, Long> {
        var extraWaitMs = 0L
        val labels = mutableListOf<String>()
        val oks = mutableListOf<Boolean>()
        for (action in actions) {
            if (action is Action.Wait) {
                extraWaitMs += action.ms
                labels.add(action.shortLabel())
                oks.add(true)
                continue
            }
            if (action is Action.NoOp) {
                labels.add(action.shortLabel())
                oks.add(true)
                continue
            }
            if (!rate.tryAcquire()) {
                Logger.w("Rate limit reached; skipping remaining actions this tick")
                break
            }
            val ok = dispatcher.dispatch(action, snapshot.marks)
            labels.add(action.shortLabel() + if (!ok) "(fail)" else "")
            oks.add(ok)
            recent.add(action.shortLabel() + if (!ok) "(fail)" else "")
            delay(120L)
        }
        return Triple(labels, oks, extraWaitMs)
    }

    private fun recordHash(h: Long) {
        if (recentHashes.size >= STUCK_WINDOW) recentHashes.removeFirst()
        recentHashes.addLast(h)
    }

    private fun isStuck(): Boolean {
        if (recentHashes.size < STUCK_WINDOW) return false
        val first = recentHashes.first()
        return recentHashes.all { PerceptualHash.hamming(first, it) <= STUCK_DELTA }
    }

    companion object {
        const val STUCK_WINDOW = 3
        const val STUCK_DELTA = 5
        const val STUCK_TRIP = 6
        const val MAX_INTERRUPTION_ATTEMPTS = 5
    }
}

enum class LoopPhase { IDLE, THINKING, ACTING, ERROR }

data class CycleRecord(
    val snapshot: ScreenSnapshot,
    val thought: String,
    val actionLabels: List<String>,
    val dispatchOk: List<Boolean>,
    val deltaSincePrev: Int
)

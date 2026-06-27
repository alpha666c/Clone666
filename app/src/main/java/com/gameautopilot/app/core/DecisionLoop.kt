package com.gameautopilot.app.core

import com.gameautopilot.app.brain.Brain
import com.gameautopilot.app.brain.BrainContext
import com.gameautopilot.app.brain.BrainException
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
    private val dispatcher: ActionDispatcher,
    private val recent: ActionRing,
    private val rate: RateLimiter,
    private val baseTickIntervalMs: Long,
    private val gameName: String,
    private val gamePackage: String,
    private val gameSystemPrompt: String,
    private val onlyActOnTargetPackage: Boolean,
    private val onState: suspend (LoopPhase, String?) -> Unit,
    private val onCycle: (CycleRecord) -> Unit = {}
) {

    private val recentHashes = ArrayDeque<Long>(STUCK_WINDOW)
    private var stuckCount = 0

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
            Logger.d("Foreground=${snapshot.foregroundPackage}, expected=$gamePackage — skipping tick")
            onState(LoopPhase.IDLE, "Waiting for ${gamePackage.substringAfterLast('.')}")
            return baseTickIntervalMs
        }

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

        onState(LoopPhase.THINKING, null)
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
            stuckHint = stuckHint
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

        if (decision.actions.isEmpty()) {
            onState(LoopPhase.IDLE, decision.thought.ifBlank { "no actions" })
            onCycle(CycleRecord(snapshot, decision.thought, emptyList(), emptyList(), delta))
            return baseTickIntervalMs
        }

        onState(LoopPhase.ACTING, decision.thought.take(80))

        var extraWaitMs = 0L
        val labels = mutableListOf<String>()
        val oks = mutableListOf<Boolean>()
        for (action in decision.actions) {
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
        onCycle(CycleRecord(snapshot, decision.thought, labels, oks, delta))

        return baseTickIntervalMs + extraWaitMs
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

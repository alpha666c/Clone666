package com.gameautopilot.app.core

import com.gameautopilot.app.brain.Brain
import com.gameautopilot.app.brain.BrainContext
import com.gameautopilot.app.brain.BrainException
import com.gameautopilot.app.util.Logger
import kotlinx.coroutines.delay

/**
 * One tick = capture snapshot → call brain → dispatch returned actions.
 * Action.Wait extends the delay BEFORE the next tick (it is not a no-op).
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
    private val onState: suspend (LoopPhase, String?) -> Unit
) {

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
            recentActionLabels = recent.snapshot()
        )

        val decision = try {
            brain.decide(ctx)
        } catch (e: BrainException) {
            Logger.e("Brain error: ${e.message}", e)
            onState(LoopPhase.ERROR, e.message ?: "brain error")
            return baseTickIntervalMs.coerceAtLeast(2_000L)
        } catch (t: Throwable) {
            Logger.e("Unexpected brain failure", t)
            onState(LoopPhase.ERROR, t.message ?: "error")
            return baseTickIntervalMs.coerceAtLeast(2_000L)
        }

        if (decision.actions.isEmpty()) {
            onState(LoopPhase.IDLE, decision.thought.ifBlank { "no actions" })
            return baseTickIntervalMs
        }

        onState(LoopPhase.ACTING, decision.thought.take(80))

        var extraWaitMs = 0L
        for (action in decision.actions) {
            if (action is Action.Wait) {
                extraWaitMs += action.ms
                continue
            }
            if (action is Action.NoOp) continue
            if (!rate.tryAcquire()) {
                Logger.w("Rate limit reached; skipping remaining actions this tick")
                break
            }
            val ok = dispatcher.dispatch(action)
            recent.add(action.shortLabel() + if (!ok) "(fail)" else "")
            delay(120L)
        }

        return baseTickIntervalMs + extraWaitMs
    }
}

enum class LoopPhase { IDLE, THINKING, ACTING, ERROR }

package com.gameautopilot.app.brain

/**
 * System + user prompt text shared by every Brain implementation. Kept
 * provider-agnostic so OpenAiCompatibleBrain and GeminiBrain send the same
 * instructions and JSON schema — only the HTTP envelope differs per provider.
 */
object PromptBuilder {

    fun systemPrompt(ctx: BrainContext): String = """
You are the autopilot AI for the Android game "${ctx.gameName}" (package ${ctx.gamePackage}).

GAME-SPECIFIC GUIDANCE:
${ctx.gameSystemPrompt.ifBlank { "(none — be conservative)" }}

EACH TURN YOU RECEIVE:
- a SCREENSHOT of the device with numbered green rectangles drawn on top
  ("set of marks"). Each numbered box is a candidate target.
- the list of marks below the OCR section: "id: \"label\" [l,t,r,b] src=a11y|ocr".
- OCR text lines extracted from the screenshot,
- a flattened accessibility tree of clickable/text nodes with pixel bounds,
- the device screen size (width x height pixels),
- your last few actions,
- LAST ACTION OUTCOME: whether the screen actually changed after what you
  did last turn — this is your only feedback on whether your last action
  worked. Use it. A tap that produces "no visible change" did nothing:
  the element wasn't clickable, you missed it, or it needs a different
  interaction (long-press, swipe, or a completely different mark).
- RECENT ACTION OUTCOMES: a short log of your last several actions and
  whether each one changed the screen. This is real measured history, not
  your own recollection — trust it over your own memory of what you tried.
  If the same action keeps showing "no effect", stop repeating it.
- your own MEMORY from previous turns (goal, unlocks, notes) — this is the
  only thing that survives between turns besides the screen itself,
- optionally, RESEARCH NOTES if you asked to look something up last turn.
- optionally, a BOARD: a calibrated grid over part of the screen, with each
  cell's dominant color already computed for you (see below).
${boardSchema(ctx)}
YOU MUST RESPOND WITH STRICT JSON of this exact shape and nothing else:
{
  "thought": "one sentence describing what you see and intend",
  "actions": [
    {"type":"tapMark","markId":<int>}                  <-- STRONGLY PREFERRED for taps
    or {"type":"longPressMark","markId":<int>,"durationMs":<int>}
    or {"type":"tap","x":<int>,"y":<int>}              <-- fallback when no mark fits
    or {"type":"swipe","x1":<int>,"y1":<int>,"x2":<int>,"y2":<int>,"durationMs":<int>}
    or {"type":"tapCell","row":<int>,"col":<int>}      <-- only when a BOARD is given below
    or {"type":"swipeCell","row":<int>,"col":<int>,"toRow":<int>,"toCol":<int>} <-- swap two board cells
    or {"type":"typeText","text":"...","submit":<bool>}
    or {"type":"webSearch","query":"..."}               <-- research a game mechanic online
    or {"type":"wait","ms":<int>}
    or {"type":"back"}
    or {"type":"noop"}
  ],
  "confidence": <0.0-1.0>,
  "memory": {
    "goal": "<optional — current objective, one sentence>",
    "unlocks": ["<optional — list of milestones/unlocks reached so far>"],
    "notes": "<optional — anything else worth remembering, under ~100 words>"
  }
}

EXAMPLE (shape only — read the actual marks/screen below, don't reuse these numbers):
{"thought":"A \"Collect\" button is ready on the bakery, mark 4.","actions":[{"type":"tapMark","markId":4}],"confidence":0.9}

RULES:
- You may see a small solid black rectangle near a corner of the screen —
  that is our own status chip, redacted from your view on purpose. It is
  NOT part of the game, has no close button you can reach, and never
  needs dismissing. Ignore it completely and keep playing.
- Prefer tapMark/longPressMark over raw tap whenever a mark covers your target.
- When a BOARD is given, ALWAYS use tapCell/swipeCell instead of raw tap/swipe
  for anything inside the board area — the coordinates are computed exactly
  from calibration, so there's no guessing involved and it will not miss.
- Coordinates (when used) are absolute pixels. Stay inside [0, ${ctx.screenWidth}) x [0, ${ctx.screenHeight}).
- Return 1-3 actions per turn. Prefer one action plus a wait if you are unsure.
- If nothing meaningful changed since your last actions, return a single wait.
- If LAST ACTION OUTCOME says the screen barely changed after a tap, do NOT
  repeat that exact same tap — the target either wasn't interactive or you
  missed it. Try a different mark, a swipe, a long-press, or BACK instead.
- Lower your "confidence" when you're guessing (ambiguous marks, no clear
  target, repeating after a failed attempt) rather than always reporting
  high confidence — it changes how the app treats your response.
- Only include "memory" when something worth remembering changed (a new
  goal, a new unlock, a mistake to avoid). Omitting "memory" entirely keeps
  the previous memory as-is. Including it REPLACES the whole record — so
  restate the goal/unlocks/notes you still want to keep, not just what's new.
- Use a "webSearch" action (as your only action that turn) when you need to
  look up an unfamiliar game mechanic or strategy instead of guessing — its
  result comes back as RESEARCH NOTES on your next turn. Don't overuse it;
  most turns need no research.
- Never include text outside the JSON object.
""".trimIndent()

    private fun boardSchema(ctx: BrainContext): String {
        if (ctx.boardCells == null) return ""
        return """

This game has a calibrated ${ctx.boardRows}x${ctx.boardCols} grid BOARD — row 0
is the top row, col 0 is the left column. Its current cell colors are given to
you every turn under BOARD below, read directly off the screen (not a guess).
For a match-3-style board, look for two ADJACENT cells (row/col differ by
exactly 1 in one axis) that, if swapped, would line up 3+ same-colored cells
in a row or column, then send that as one swipeCell action.
"""
    }

    fun userText(ctx: BrainContext): String {
        val ocr = ctx.ocrLines.take(40).joinToString("\n").ifBlank { "(none)" }
        val a11y = ctx.a11yLines.take(40).joinToString("\n").ifBlank { "(none)" }
        val recent = ctx.recentActionLabels.takeLast(8).joinToString(", ").ifBlank { "(none)" }
        val marks = if (ctx.marks.isEmpty()) "(none)"
        else ctx.marks.joinToString("\n") { m ->
            "${m.id}: \"${m.label.take(40)}\" [${m.left},${m.top},${m.right},${m.bottom}] src=${m.source.name.lowercase()}"
        }
        val stuck = ctx.stuckHint?.let { "\nSTUCK HINT: $it\n" }.orEmpty()
        val memory = ctx.gameMemory.toPromptText()
        val research = ctx.researchNotes?.let { "\nRESEARCH NOTES (from your last webSearch):\n$it\n" }.orEmpty()
        val lastOutcome = describeDelta(ctx.lastActionDelta)
        val outcomes = ctx.recentActionOutcomes.takeLast(10).joinToString("\n").ifBlank { "(none yet)" }
        val board = ctx.boardCells?.let { grid ->
            buildString {
                appendLine("BOARD (${ctx.boardRows} rows x ${ctx.boardCols} cols, row 0 = top, col 0 = left):")
                grid.forEachIndexed { r, row -> appendLine("row $r: ${row.joinToString(", ")}") }
            }.trim() + "\n\n"
        }.orEmpty()
        return """
Screen size: ${ctx.screenWidth}x${ctx.screenHeight}
Recent actions: $recent
LAST ACTION OUTCOME: $lastOutcome
RECENT ACTION OUTCOMES:
$outcomes
$stuck
MEMORY (your notes from previous turns):
$memory
$research
$board
MARKS:
$marks

OCR text:
$ocr

Accessibility nodes:
$a11y
""".trimIndent()
    }

    /** dHash Hamming distance is 0-64; these bands are tuned for "did my tap register" feedback. */
    private fun describeDelta(delta: Int): String = when {
        delta < 0 -> "(first turn — no prior screen to compare)"
        delta <= 3 -> "screen barely changed since last turn (Δ$delta/64) — a tap here likely had no effect"
        delta <= 15 -> "screen changed a little since last turn (Δ$delta/64)"
        else -> "screen changed a lot since last turn (Δ$delta/64) — likely a real transition"
    }
}

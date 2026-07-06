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
- your own MEMORY notes from previous turns (goal, progress, what you were
  about to do next) — this is the only thing that survives between turns
  besides the screen itself.

YOU MUST RESPOND WITH STRICT JSON of this exact shape and nothing else:
{
  "thought": "one sentence describing what you see and intend",
  "actions": [
    {"type":"tapMark","markId":<int>}                  <-- STRONGLY PREFERRED for taps
    or {"type":"longPressMark","markId":<int>,"durationMs":<int>}
    or {"type":"tap","x":<int>,"y":<int>}              <-- fallback when no mark fits
    or {"type":"swipe","x1":<int>,"y1":<int>,"x2":<int>,"y2":<int>,"durationMs":<int>}
    or {"type":"typeText","text":"...","submit":<bool>}
    or {"type":"wait","ms":<int>}
    or {"type":"back"}
    or {"type":"noop"}
  ],
  "confidence": <0.0-1.0>,
  "memory": "<optional — updated notes to remember next turn>"
}

RULES:
- Prefer tapMark/longPressMark over raw tap whenever a mark covers your target.
- Coordinates (when used) are absolute pixels. Stay inside [0, ${ctx.screenWidth}) x [0, ${ctx.screenHeight}).
- Return 1-3 actions per turn. Prefer one action plus a wait if you are unsure.
- If nothing meaningful changed since your last actions, return a single wait.
- Only include "memory" when something worth remembering changed (current
  goal, progress milestone, what to try next, a mistake to avoid). Keep it
  under ~120 words of plain notes, not JSON. Omit it (or repeat the same
  text) when nothing new happened — it replaces the previous memory verbatim.
- Never include text outside the JSON object.
""".trimIndent()

    fun userText(ctx: BrainContext): String {
        val ocr = ctx.ocrLines.take(40).joinToString("\n").ifBlank { "(none)" }
        val a11y = ctx.a11yLines.take(40).joinToString("\n").ifBlank { "(none)" }
        val recent = ctx.recentActionLabels.takeLast(8).joinToString(", ").ifBlank { "(none)" }
        val marks = if (ctx.marks.isEmpty()) "(none)"
        else ctx.marks.joinToString("\n") { m ->
            "${m.id}: \"${m.label.take(40)}\" [${m.left},${m.top},${m.right},${m.bottom}] src=${m.source.name.lowercase()}"
        }
        val stuck = ctx.stuckHint?.let { "\nSTUCK HINT: $it\n" }.orEmpty()
        val memory = ctx.gameMemory.ifBlank { "(none yet — this is a fresh start)" }
        return """
Screen size: ${ctx.screenWidth}x${ctx.screenHeight}
Recent actions: $recent
$stuck
MEMORY (your notes from previous turns):
$memory

MARKS:
$marks

OCR text:
$ocr

Accessibility nodes:
$a11y
""".trimIndent()
    }
}

package com.gameautopilot.app.brain

import com.gameautopilot.app.core.Action
import com.gameautopilot.app.util.Logger
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class OpenAiCompatibleBrain(
    private val baseUrl: String,
    private val apiKey: String,
    private val model: String,
    private val client: OkHttpClient = defaultClient
) : Brain {

    override suspend fun decide(ctx: BrainContext): BrainDecision {
        if (apiKey.isBlank()) throw BrainException("API key not set")

        val body = buildRequestBody(ctx)
        val req = Request.Builder()
            .url("${baseUrl.trimEnd('/')}/chat/completions")
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("Content-Type", "application/json")
            .post(body.toString().toRequestBody(JSON))
            .build()

        val raw = try {
            client.newCall(req).await()
        } catch (e: IOException) {
            throw BrainException("Network error: ${e.message}", e)
        }

        raw.use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                throw BrainException("HTTP ${resp.code}: ${text.take(400)}")
            }
            return parseDecision(text)
        }
    }

    private fun buildRequestBody(ctx: BrainContext): JSONObject {
        val systemPrompt = buildSystemPrompt(ctx)
        val userText = buildUserText(ctx)

        val systemMsg = JSONObject().apply {
            put("role", "system")
            put("content", systemPrompt)
        }
        val userMsg = JSONObject().apply {
            put("role", "user")
            put("content", JSONArray().apply {
                put(JSONObject().apply {
                    put("type", "text")
                    put("text", userText)
                })
                put(JSONObject().apply {
                    put("type", "image_url")
                    put("image_url", JSONObject().apply {
                        put("url", "data:image/jpeg;base64,${ctx.screenshotBase64Jpeg}")
                    })
                })
            })
        }

        return JSONObject().apply {
            put("model", model)
            put("temperature", 0.2)
            put("max_tokens", 800)
            put("response_format", JSONObject().apply { put("type", "json_object") })
            put("messages", JSONArray().apply {
                put(systemMsg)
                put(userMsg)
            })
        }
    }

    private fun buildSystemPrompt(ctx: BrainContext): String = """
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
- your last few actions.

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
  "confidence": <0.0-1.0>
}

RULES:
- Prefer tapMark/longPressMark over raw tap whenever a mark covers your target.
- Coordinates (when used) are absolute pixels. Stay inside [0, ${ctx.screenWidth}) x [0, ${ctx.screenHeight}).
- Return 1-3 actions per turn. Prefer one action plus a wait if you are unsure.
- If nothing meaningful changed since your last actions, return a single wait.
- Never include text outside the JSON object.
""".trimIndent()

    private fun buildUserText(ctx: BrainContext): String {
        val ocr = ctx.ocrLines.take(40).joinToString("\n").ifBlank { "(none)" }
        val a11y = ctx.a11yLines.take(40).joinToString("\n").ifBlank { "(none)" }
        val recent = ctx.recentActionLabels.takeLast(8).joinToString(", ").ifBlank { "(none)" }
        val marks = if (ctx.marks.isEmpty()) "(none)"
        else ctx.marks.joinToString("\n") { m ->
            "${m.id}: \"${m.label.take(40)}\" [${m.left},${m.top},${m.right},${m.bottom}] src=${m.source.name.lowercase()}"
        }
        val stuck = ctx.stuckHint?.let { "\nSTUCK HINT: $it\n" }.orEmpty()
        return """
Screen size: ${ctx.screenWidth}x${ctx.screenHeight}
Recent actions: $recent
$stuck
MARKS:
$marks

OCR text:
$ocr

Accessibility nodes:
$a11y
""".trimIndent()
    }

    private fun parseDecision(httpBody: String): BrainDecision {
        val outer = JSONObject(httpBody)
        val choices = outer.optJSONArray("choices") ?: throw BrainException("No choices in response")
        if (choices.length() == 0) throw BrainException("Empty choices")
        val content = choices.getJSONObject(0)
            .optJSONObject("message")
            ?.optString("content")
            ?: throw BrainException("No content in first choice")

        val cleaned = stripCodeFences(content).trim()
        val obj = try {
            JSONObject(cleaned)
        } catch (e: Throwable) {
            throw BrainException("Brain returned non-JSON: ${cleaned.take(200)}", e)
        }

        val thought = obj.optString("thought", "")
        val confidence = obj.optDouble("confidence", 0.5)
        val actionsArr = obj.optJSONArray("actions") ?: JSONArray()
        val actions = (0 until actionsArr.length()).mapNotNull { idx ->
            runCatching { Action.fromJson(actionsArr.getJSONObject(idx)) }
                .onFailure { Logger.w("Skipping bad action: ${it.message}") }
                .getOrNull()
        }
        return BrainDecision(thought = thought, actions = actions, confidence = confidence)
    }

    private fun stripCodeFences(s: String): String {
        val t = s.trim()
        if (!t.startsWith("```")) return t
        val firstNewline = t.indexOf('\n').takeIf { it >= 0 } ?: return t
        val withoutOpen = t.substring(firstNewline + 1)
        val endFence = withoutOpen.lastIndexOf("```")
        return if (endFence >= 0) withoutOpen.substring(0, endFence) else withoutOpen
    }

    companion object {
        private val JSON = "application/json; charset=utf-8".toMediaType()

        val defaultClient: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .writeTimeout(60, TimeUnit.SECONDS)
                .build()
        }
    }
}

private suspend fun Call.await(): Response = suspendCancellableCoroutine { cont ->
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            if (!cont.isCancelled) cont.resumeWithException(e)
        }
        override fun onResponse(call: Call, response: Response) {
            cont.resume(response)
        }
    })
    cont.invokeOnCancellation { runCatching { cancel() } }
}

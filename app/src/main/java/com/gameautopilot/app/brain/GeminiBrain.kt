package com.gameautopilot.app.brain

import com.gameautopilot.app.util.Logger
import java.io.IOException
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

/**
 * Brain backed by Google's Gemini REST API (`generateContent`). Uses the
 * same strict-JSON schema as OpenAiCompatibleBrain (PromptBuilder /
 * BrainResponseParser) — only the request/response envelope differs:
 * Gemini takes a `system_instruction` + `contents[].parts[]` body with
 * inline base64 image data, and returns `candidates[].content.parts[].text`.
 */
class GeminiBrain(
    private val baseUrl: String,
    private val apiKey: String,
    private val model: String,
    private val client: OkHttpClient = OpenAiCompatibleBrain.defaultClient
) : Brain {

    override suspend fun decide(ctx: BrainContext): BrainDecision {
        val text = callGenerateContent(
            systemPrompt = PromptBuilder.systemPrompt(ctx),
            userText = PromptBuilder.userText(ctx),
            imageBase64 = ctx.screenshotBase64Jpeg,
            maxOutputTokens = 4096
        )
        return BrainResponseParser.parse(text)
    }

    override suspend fun detectBoard(screenshotBase64Jpeg: String): BoardDetection? {
        val text = try {
            callGenerateContent(
                systemPrompt = null,
                userText = BoardDetectionParser.buildPrompt(),
                imageBase64 = screenshotBase64Jpeg,
                maxOutputTokens = 512,
                temperature = 0.1
            )
        } catch (e: BrainException) {
            Logger.w("Board detection call failed: ${e.message}")
            return null
        }
        return BoardDetectionParser.parse(text)
    }

    /** Sends one text+image turn to `generateContent` and returns the model's raw text reply. */
    private suspend fun callGenerateContent(
        systemPrompt: String?,
        userText: String,
        imageBase64: String,
        maxOutputTokens: Int,
        temperature: Double = 0.2
    ): String {
        if (apiKey.isBlank()) throw BrainException("API key not set")

        val body = buildRequestBody(systemPrompt, userText, imageBase64, maxOutputTokens, temperature)
        val url = "${baseUrl.trimEnd('/')}/models/$model:generateContent"
        val req = Request.Builder()
            .url(url)
            .addHeader("x-goog-api-key", apiKey)
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
            return extractText(text)
        }
    }

    private fun buildRequestBody(
        systemPrompt: String?,
        userText: String,
        imageBase64: String,
        maxOutputTokens: Int,
        temperature: Double
    ): JSONObject = JSONObject().apply {
        systemPrompt?.let {
            put("system_instruction", JSONObject().apply {
                put("parts", JSONArray().apply {
                    put(JSONObject().apply { put("text", it) })
                })
            })
        }
        put("contents", JSONArray().apply {
            put(JSONObject().apply {
                put("role", "user")
                put("parts", JSONArray().apply {
                    put(JSONObject().apply { put("text", userText) })
                    put(JSONObject().apply {
                        put("inline_data", JSONObject().apply {
                            put("mime_type", "image/jpeg")
                            put("data", imageBase64)
                        })
                    })
                })
            })
        })
        put("generationConfig", JSONObject().apply {
            put("temperature", temperature)
            // Raising this alone (800 -> 3072) didn't fix truncation: on thinking-
            // capable Gemini models, maxOutputTokens is a shared budget that the
            // model's internal reasoning pass draws from FIRST, before it writes a
            // single character of the visible answer. A hard scene (lots of marks,
            // an unfamiliar game) can burn the whole budget on thinking and leave
            // nothing for the JSON itself, no matter how high this number is.
            // Disabling thinking outright is the actual fix for a task like this —
            // one small structured JSON decision doesn't need a hidden reasoning
            // pass — and it makes token usage predictable again.
            put("maxOutputTokens", maxOutputTokens)
            put("responseMimeType", "application/json")
            put("thinkingConfig", JSONObject().apply {
                // "-pro" models reject a budget of 0 (128 is their floor); every other
                // Gemini family (flash, flash-lite, and their numbered successors)
                // accepts 0 to fully disable the hidden reasoning pass.
                put("thinkingBudget", if (model.contains("pro", ignoreCase = true)) 128 else 0)
            })
        })
    }

    private fun extractText(httpBody: String): String {
        val outer = JSONObject(httpBody)
        val candidates = outer.optJSONArray("candidates")
        if (candidates == null || candidates.length() == 0) {
            val blockReason = outer.optJSONObject("promptFeedback")?.optString("blockReason")
            throw BrainException("No candidates in response" + (blockReason?.let { " (blocked: $it)" } ?: ""))
        }
        val firstCandidate = candidates.getJSONObject(0)
        val parts = firstCandidate.optJSONObject("content")?.optJSONArray("parts")
            ?: throw BrainException("No content parts in first candidate")
        val text = (0 until parts.length())
            .mapNotNull { idx -> parts.getJSONObject(idx).takeIf { it.has("text") }?.getString("text") }
            .joinToString("")
        if (text.isBlank()) throw BrainException("Empty text from Gemini response")
        if (firstCandidate.optString("finishReason") == "MAX_TOKENS") {
            throw BrainException("Response cut off at the token limit before finishing its JSON — raise maxOutputTokens")
        }
        return text
    }

    companion object {
        private val JSON = "application/json; charset=utf-8".toMediaType()
    }
}

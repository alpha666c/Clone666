package com.gameautopilot.app.brain

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
        if (apiKey.isBlank()) throw BrainException("API key not set")

        val body = buildRequestBody(ctx)
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
            return parseDecision(text)
        }
    }

    private fun buildRequestBody(ctx: BrainContext): JSONObject = JSONObject().apply {
        put("system_instruction", JSONObject().apply {
            put("parts", JSONArray().apply {
                put(JSONObject().apply { put("text", PromptBuilder.systemPrompt(ctx)) })
            })
        })
        put("contents", JSONArray().apply {
            put(JSONObject().apply {
                put("role", "user")
                put("parts", JSONArray().apply {
                    put(JSONObject().apply { put("text", PromptBuilder.userText(ctx)) })
                    put(JSONObject().apply {
                        put("inline_data", JSONObject().apply {
                            put("mime_type", "image/jpeg")
                            put("data", ctx.screenshotBase64Jpeg)
                        })
                    })
                })
            })
        })
        put("generationConfig", JSONObject().apply {
            put("temperature", 0.2)
            put("maxOutputTokens", 800)
            put("responseMimeType", "application/json")
        })
    }

    private fun parseDecision(httpBody: String): BrainDecision {
        val outer = JSONObject(httpBody)
        val candidates = outer.optJSONArray("candidates")
        if (candidates == null || candidates.length() == 0) {
            val blockReason = outer.optJSONObject("promptFeedback")?.optString("blockReason")
            throw BrainException("No candidates in response" + (blockReason?.let { " (blocked: $it)" } ?: ""))
        }
        val parts = candidates.getJSONObject(0).optJSONObject("content")?.optJSONArray("parts")
            ?: throw BrainException("No content parts in first candidate")
        val text = (0 until parts.length())
            .mapNotNull { idx -> parts.getJSONObject(idx).takeIf { it.has("text") }?.getString("text") }
            .joinToString("")
        if (text.isBlank()) throw BrainException("Empty text from Gemini response")
        return BrainResponseParser.parse(text)
    }

    companion object {
        private val JSON = "application/json; charset=utf-8".toMediaType()
    }
}

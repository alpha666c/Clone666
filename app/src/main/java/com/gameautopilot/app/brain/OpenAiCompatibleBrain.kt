package com.gameautopilot.app.brain

import java.io.IOException
import java.util.concurrent.TimeUnit
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

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
        val systemMsg = JSONObject().apply {
            put("role", "system")
            put("content", PromptBuilder.systemPrompt(ctx))
        }
        val userMsg = JSONObject().apply {
            put("role", "user")
            put("content", JSONArray().apply {
                put(JSONObject().apply {
                    put("type", "text")
                    put("text", PromptBuilder.userText(ctx))
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
            put("max_tokens", 1500)
            put("response_format", JSONObject().apply { put("type", "json_object") })
            put("messages", JSONArray().apply {
                put(systemMsg)
                put(userMsg)
            })
        }
    }

    private fun parseDecision(httpBody: String): BrainDecision {
        val outer = JSONObject(httpBody)
        val choices = outer.optJSONArray("choices") ?: throw BrainException("No choices in response")
        if (choices.length() == 0) throw BrainException("Empty choices")
        val content = choices.getJSONObject(0)
            .optJSONObject("message")
            ?.optString("content")
            ?: throw BrainException("No content in first choice")
        return BrainResponseParser.parse(content)
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

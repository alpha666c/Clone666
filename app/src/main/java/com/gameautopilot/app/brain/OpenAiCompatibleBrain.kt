package com.gameautopilot.app.brain

import com.gameautopilot.app.util.Logger
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
        val text = callChatCompletion(
            systemPrompt = PromptBuilder.systemPrompt(ctx),
            userText = PromptBuilder.userText(ctx),
            imageBase64 = ctx.screenshotBase64Jpeg,
            maxTokens = 1500
        )
        return BrainResponseParser.parse(text)
    }

    override suspend fun detectBoard(screenshotBase64Jpeg: String): BoardDetection? {
        val text = try {
            callChatCompletion(
                systemPrompt = null,
                userText = BoardDetectionParser.buildPrompt(),
                imageBase64 = screenshotBase64Jpeg,
                maxTokens = 300,
                temperature = 0.1
            )
        } catch (e: BrainException) {
            Logger.w("Board detection call failed: ${e.message}")
            return null
        }
        return BoardDetectionParser.parse(text)
    }

    /** Sends one text+image chat turn and returns the model's raw message content. */
    private suspend fun callChatCompletion(
        systemPrompt: String?,
        userText: String,
        imageBase64: String,
        maxTokens: Int,
        temperature: Double = 0.2
    ): String {
        if (apiKey.isBlank()) throw BrainException("API key not set")

        val body = buildRequestBody(systemPrompt, userText, imageBase64, maxTokens, temperature)
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
            return extractContent(text)
        }
    }

    private fun buildRequestBody(
        systemPrompt: String?,
        userText: String,
        imageBase64: String,
        maxTokens: Int,
        temperature: Double
    ): JSONObject {
        val messages = JSONArray()
        systemPrompt?.let {
            messages.put(JSONObject().apply {
                put("role", "system")
                put("content", it)
            })
        }
        messages.put(JSONObject().apply {
            put("role", "user")
            put("content", JSONArray().apply {
                put(JSONObject().apply {
                    put("type", "text")
                    put("text", userText)
                })
                put(JSONObject().apply {
                    put("type", "image_url")
                    put("image_url", JSONObject().apply {
                        put("url", "data:image/jpeg;base64,$imageBase64")
                    })
                })
            })
        })

        return JSONObject().apply {
            put("model", model)
            put("temperature", temperature)
            put("max_tokens", maxTokens)
            put("response_format", JSONObject().apply { put("type", "json_object") })
            put("messages", messages)
        }
    }

    private fun extractContent(httpBody: String): String {
        val outer = JSONObject(httpBody)
        val choices = outer.optJSONArray("choices") ?: throw BrainException("No choices in response")
        if (choices.length() == 0) throw BrainException("Empty choices")
        return choices.getJSONObject(0)
            .optJSONObject("message")
            ?.optString("content")
            ?: throw BrainException("No content in first choice")
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

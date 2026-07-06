package com.gameautopilot.app.brain

import java.io.IOException
import java.util.concurrent.TimeUnit
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

/**
 * Lets the brain research unfamiliar game mechanics/strategies instead of
 * only ever guessing from the screenshot. Invoked by DecisionLoop when the
 * brain emits an `Action.WebSearch`; the result comes back as ephemeral
 * `BrainContext.researchNotes` on the following tick (not persisted — it's
 * one-tick scratch space, distinct from the durable per-game GameMemory).
 */
interface WebSearchProvider {
    suspend fun search(query: String): String
}

/** Default [WebSearchProvider]: Brave Search's REST API (requires an API key from Settings). */
class BraveSearchProvider(
    private val apiKey: String,
    private val client: OkHttpClient = defaultClient
) : WebSearchProvider {

    override suspend fun search(query: String): String {
        if (apiKey.isBlank()) {
            return "(web search unavailable — no Brave Search API key configured in Settings)"
        }
        val url = "https://api.search.brave.com/res/v1/web/search".toHttpUrl()
            .newBuilder()
            .addQueryParameter("q", query.take(400))
            .addQueryParameter("count", "5")
            .build()
        val req = Request.Builder()
            .url(url)
            .addHeader("Accept", "application/json")
            .addHeader("X-Subscription-Token", apiKey)
            .get()
            .build()

        return try {
            client.newCall(req).await().use { resp ->
                val text = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) return "Search failed: HTTP ${resp.code}: ${text.take(200)}"
                formatResults(text)
            }
        } catch (e: IOException) {
            "Search failed: network error (${e.message})"
        } catch (t: Throwable) {
            "Search failed: ${t.message}"
        }
    }

    private fun formatResults(body: String): String {
        val results = JSONObject(body).optJSONObject("web")?.optJSONArray("results")
        if (results == null || results.length() == 0) return "No results found for that query."
        return (0 until minOf(results.length(), 5)).joinToString("\n\n") { i ->
            val r = results.getJSONObject(i)
            "${r.optString("title")}\n${r.optString("description")}"
        }.take(MAX_RESULT_CHARS)
    }

    companion object {
        private const val MAX_RESULT_CHARS = 1500

        val defaultClient: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(15, TimeUnit.SECONDS)
                .build()
        }
    }
}

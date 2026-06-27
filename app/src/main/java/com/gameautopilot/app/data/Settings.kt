package com.gameautopilot.app.data

data class Settings(
    val baseUrl: String = DEFAULT_OPENAI_URL,
    val model: String = DEFAULT_OPENAI_MODEL,
    val apiKey: String = "",
    val maxActionsPerMinute: Int = 30,
    val onlyActOnTarget: Boolean = true,
    val useNvidia: Boolean = false,
    val useSetOfMarks: Boolean = true,
    val logCycles: Boolean = true
) {
    fun hasApiKey(): Boolean = apiKey.isNotBlank()

    companion object {
        const val DEFAULT_OPENAI_URL = "https://api.openai.com/v1"
        const val DEFAULT_OPENAI_MODEL = "gpt-4o-mini"
        const val DEFAULT_NVIDIA_URL = "https://integrate.api.nvidia.com/v1"
        const val DEFAULT_NVIDIA_MODEL = "meta/llama-3.2-90b-vision-instruct"
    }
}

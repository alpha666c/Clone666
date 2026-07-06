package com.gameautopilot.app.data

enum class BrainProvider { OPENAI, NVIDIA, GEMINI }

data class Settings(
    val baseUrl: String = DEFAULT_OPENAI_URL,
    val model: String = DEFAULT_OPENAI_MODEL,
    val apiKey: String = "",
    val maxActionsPerMinute: Int = 30,
    val onlyActOnTarget: Boolean = true,
    val provider: BrainProvider = BrainProvider.OPENAI,
    val useSetOfMarks: Boolean = true,
    val logCycles: Boolean = true,
    val showDebugOverlay: Boolean = false,
    val useFastPath: Boolean = true,
    val webSearchApiKey: String = "",
    val autoRecoverInterruptions: Boolean = true
) {
    fun hasApiKey(): Boolean = apiKey.isNotBlank()
    fun hasWebSearchKey(): Boolean = webSearchApiKey.isNotBlank()

    companion object {
        const val DEFAULT_OPENAI_URL = "https://api.openai.com/v1"
        const val DEFAULT_OPENAI_MODEL = "gpt-4o-mini"
        const val DEFAULT_NVIDIA_URL = "https://integrate.api.nvidia.com/v1"
        const val DEFAULT_NVIDIA_MODEL = "meta/llama-3.2-90b-vision-instruct"
        const val DEFAULT_GEMINI_URL = "https://generativelanguage.googleapis.com/v1beta"
        // gemini-3.5-flash is GA and vision/Computer-Use capable as of mid-2026.
        // Once a stronger "pro"-tier model ships, paste its model id into the
        // Settings "Model" field — no code change needed, this is just the default.
        const val DEFAULT_GEMINI_MODEL = "gemini-3.5-flash"

        fun defaultUrlFor(provider: BrainProvider): String = when (provider) {
            BrainProvider.OPENAI -> DEFAULT_OPENAI_URL
            BrainProvider.NVIDIA -> DEFAULT_NVIDIA_URL
            BrainProvider.GEMINI -> DEFAULT_GEMINI_URL
        }

        fun defaultModelFor(provider: BrainProvider): String = when (provider) {
            BrainProvider.OPENAI -> DEFAULT_OPENAI_MODEL
            BrainProvider.NVIDIA -> DEFAULT_NVIDIA_MODEL
            BrainProvider.GEMINI -> DEFAULT_GEMINI_MODEL
        }
    }
}

package com.gameautopilot.app.brain

import com.gameautopilot.app.data.BrainProvider
import com.gameautopilot.app.data.Settings

object BrainFactory {
    fun create(settings: Settings): Brain {
        val baseUrl = settings.baseUrl.ifBlank { Settings.defaultUrlFor(settings.provider) }
        val model = settings.model.ifBlank { Settings.defaultModelFor(settings.provider) }
        return when (settings.provider) {
            BrainProvider.GEMINI -> GeminiBrain(baseUrl = baseUrl, apiKey = settings.apiKey, model = model)
            BrainProvider.OPENAI, BrainProvider.NVIDIA ->
                OpenAiCompatibleBrain(baseUrl = baseUrl, apiKey = settings.apiKey, model = model)
        }
    }
}

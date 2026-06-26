package com.gameautopilot.app.brain

import com.gameautopilot.app.data.Settings

object BrainFactory {
    fun create(settings: Settings): Brain {
        val baseUrl = settings.baseUrl.ifBlank {
            if (settings.useNvidia) Settings.DEFAULT_NVIDIA_URL else Settings.DEFAULT_OPENAI_URL
        }
        val model = settings.model.ifBlank {
            if (settings.useNvidia) Settings.DEFAULT_NVIDIA_MODEL else Settings.DEFAULT_OPENAI_MODEL
        }
        return OpenAiCompatibleBrain(
            baseUrl = baseUrl,
            apiKey = settings.apiKey,
            model = model
        )
    }
}

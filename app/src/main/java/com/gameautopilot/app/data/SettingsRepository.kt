package com.gameautopilot.app.data

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class SettingsRepository(context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _settings = MutableStateFlow(read())
    val settings: StateFlow<Settings> = _settings.asStateFlow()

    private fun read(): Settings = Settings(
        baseUrl = prefs.getString(KEY_BASE_URL, Settings.DEFAULT_OPENAI_URL)
            ?: Settings.DEFAULT_OPENAI_URL,
        model = prefs.getString(KEY_MODEL, Settings.DEFAULT_OPENAI_MODEL)
            ?: Settings.DEFAULT_OPENAI_MODEL,
        apiKey = prefs.getString(KEY_API_KEY, "") ?: "",
        maxActionsPerMinute = prefs.getInt(KEY_RATE, 30).coerceIn(1, 600),
        onlyActOnTarget = prefs.getBoolean(KEY_ONLY_TARGET, true),
        useNvidia = prefs.getBoolean(KEY_USE_NVIDIA, false),
        useSetOfMarks = prefs.getBoolean(KEY_USE_SOM, true),
        logCycles = prefs.getBoolean(KEY_LOG_CYCLES, true)
    )

    fun current(): Settings = _settings.value

    fun save(s: Settings) {
        prefs.edit()
            .putString(KEY_BASE_URL, s.baseUrl.trim())
            .putString(KEY_MODEL, s.model.trim())
            .putString(KEY_API_KEY, s.apiKey)
            .putInt(KEY_RATE, s.maxActionsPerMinute.coerceIn(1, 600))
            .putBoolean(KEY_ONLY_TARGET, s.onlyActOnTarget)
            .putBoolean(KEY_USE_NVIDIA, s.useNvidia)
            .putBoolean(KEY_USE_SOM, s.useSetOfMarks)
            .putBoolean(KEY_LOG_CYCLES, s.logCycles)
            .apply()
        _settings.value = read()
    }

    fun clearApiKey() {
        prefs.edit().putString(KEY_API_KEY, "").apply()
        _settings.value = read()
    }

    companion object {
        const val PREFS_NAME = "autopilot_settings"
        private const val KEY_BASE_URL = "baseUrl"
        private const val KEY_MODEL = "model"
        private const val KEY_API_KEY = "apiKey"
        private const val KEY_RATE = "maxActionsPerMinute"
        private const val KEY_ONLY_TARGET = "onlyActOnTarget"
        private const val KEY_USE_NVIDIA = "useNvidia"
        private const val KEY_USE_SOM = "useSetOfMarks"
        private const val KEY_LOG_CYCLES = "logCycles"
    }
}

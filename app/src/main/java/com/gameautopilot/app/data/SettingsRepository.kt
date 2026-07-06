package com.gameautopilot.app.data

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.gameautopilot.app.util.Logger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class SettingsRepository(context: Context) {
    private val prefs: SharedPreferences = createPrefs(context).also { migrateFromLegacyPlaintext(context, it) }

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
        provider = runCatching {
            BrainProvider.valueOf(prefs.getString(KEY_PROVIDER, BrainProvider.OPENAI.name)!!)
        }.getOrDefault(BrainProvider.OPENAI),
        useSetOfMarks = prefs.getBoolean(KEY_USE_SOM, true),
        logCycles = prefs.getBoolean(KEY_LOG_CYCLES, true),
        showDebugOverlay = prefs.getBoolean(KEY_DEBUG_OVERLAY, false),
        useFastPath = prefs.getBoolean(KEY_FAST_PATH, true),
        webSearchApiKey = prefs.getString(KEY_SEARCH_KEY, "") ?: "",
        autoRecoverInterruptions = prefs.getBoolean(KEY_AUTO_RECOVER, true),
        ocrScript = runCatching {
            OcrScript.valueOf(prefs.getString(KEY_OCR_SCRIPT, OcrScript.LATIN.name)!!)
        }.getOrDefault(OcrScript.LATIN)
    )

    fun current(): Settings = _settings.value

    fun save(s: Settings) {
        prefs.edit()
            .putString(KEY_BASE_URL, s.baseUrl.trim())
            .putString(KEY_MODEL, s.model.trim())
            .putString(KEY_API_KEY, s.apiKey)
            .putInt(KEY_RATE, s.maxActionsPerMinute.coerceIn(1, 600))
            .putBoolean(KEY_ONLY_TARGET, s.onlyActOnTarget)
            .putString(KEY_PROVIDER, s.provider.name)
            .putBoolean(KEY_USE_SOM, s.useSetOfMarks)
            .putBoolean(KEY_LOG_CYCLES, s.logCycles)
            .putBoolean(KEY_DEBUG_OVERLAY, s.showDebugOverlay)
            .putBoolean(KEY_FAST_PATH, s.useFastPath)
            .putString(KEY_SEARCH_KEY, s.webSearchApiKey)
            .putBoolean(KEY_AUTO_RECOVER, s.autoRecoverInterruptions)
            .putString(KEY_OCR_SCRIPT, s.ocrScript.name)
            .apply()
        _settings.value = read()
    }

    fun clearApiKey() {
        prefs.edit().putString(KEY_API_KEY, "").apply()
        _settings.value = read()
    }

    fun clearWebSearchKey() {
        prefs.edit().putString(KEY_SEARCH_KEY, "").apply()
        _settings.value = read()
    }

    companion object {
        const val PREFS_NAME = "autopilot_settings_secure"
        private const val LEGACY_PREFS_NAME = "autopilot_settings"
        private const val KEY_MIGRATED = "migratedFromPlaintext"
        private const val KEY_BASE_URL = "baseUrl"
        private const val KEY_MODEL = "model"
        private const val KEY_API_KEY = "apiKey"
        private const val KEY_RATE = "maxActionsPerMinute"
        private const val KEY_ONLY_TARGET = "onlyActOnTarget"
        private const val KEY_PROVIDER = "provider"
        private const val KEY_USE_SOM = "useSetOfMarks"
        private const val KEY_LOG_CYCLES = "logCycles"
        private const val KEY_DEBUG_OVERLAY = "showDebugOverlay"
        private const val KEY_FAST_PATH = "useFastPath"
        private const val KEY_SEARCH_KEY = "webSearchApiKey"
        private const val KEY_AUTO_RECOVER = "autoRecoverInterruptions"
        private const val KEY_OCR_SCRIPT = "ocrScript"

        /**
         * API keys used to live in plain SharedPreferences. EncryptedSharedPreferences
         * needs its own master key + file, so this is a new file name, not a wrapper
         * around the old one — reusing the old name would make the encryption layer
         * try to decrypt already-plaintext bytes and throw on first read.
         */
        private fun createPrefs(context: Context): SharedPreferences = try {
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            EncryptedSharedPreferences.create(
                context,
                PREFS_NAME,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        } catch (t: Throwable) {
            // A corrupted Keystore entry or a device without hardware-backed Keystore
            // support shouldn't brick every Settings read — fall back to a plain,
            // distinctly-named file rather than crash the app on every launch.
            Logger.e("EncryptedSharedPreferences unavailable, falling back to plain prefs", t)
            context.getSharedPreferences("${PREFS_NAME}_fallback", Context.MODE_PRIVATE)
        }

        /** One-time migration of any pre-existing plaintext settings into the encrypted store. */
        private fun migrateFromLegacyPlaintext(context: Context, target: SharedPreferences) {
            if (target.getBoolean(KEY_MIGRATED, false)) return
            val legacy = context.getSharedPreferences(LEGACY_PREFS_NAME, Context.MODE_PRIVATE)
            val legacyValues = legacy.all
            if (legacyValues.isNotEmpty()) {
                val editor = target.edit()
                for ((key, value) in legacyValues) {
                    when (value) {
                        is String -> editor.putString(key, value)
                        is Boolean -> editor.putBoolean(key, value)
                        is Int -> editor.putInt(key, value)
                        is Long -> editor.putLong(key, value)
                        is Float -> editor.putFloat(key, value)
                    }
                }
                editor.putBoolean(KEY_MIGRATED, true).apply()
                legacy.edit().clear().apply()
                Logger.i("Migrated ${legacyValues.size} legacy plaintext setting(s) into encrypted storage")
            } else {
                target.edit().putBoolean(KEY_MIGRATED, true).apply()
            }
        }
    }
}

package com.gameautopilot.app.ui

import android.os.Bundle
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.gameautopilot.app.R
import com.gameautopilot.app.core.AutopilotController
import com.gameautopilot.app.data.BrainProvider
import com.gameautopilot.app.data.Settings
import com.google.android.material.button.MaterialButton
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.textfield.TextInputEditText

class SettingsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        val baseUrlInput = findViewById<TextInputEditText>(R.id.baseUrlInput)
        val modelInput = findViewById<TextInputEditText>(R.id.modelInput)
        val apiKeyInput = findViewById<TextInputEditText>(R.id.apiKeyInput)
        val apiKeyStatus = findViewById<TextView>(R.id.apiKeyStatus)
        val clearKeyBtn = findViewById<MaterialButton>(R.id.clearKeyBtn)
        val rateInput = findViewById<TextInputEditText>(R.id.rateInput)
        val onlyTargetSwitch = findViewById<MaterialSwitch>(R.id.onlyTargetSwitch)
        val somSwitch = findViewById<MaterialSwitch>(R.id.somSwitch)
        val logCyclesSwitch = findViewById<MaterialSwitch>(R.id.logCyclesSwitch)
        val fastPathSwitch = findViewById<MaterialSwitch>(R.id.fastPathSwitch)
        val debugOverlaySwitch = findViewById<MaterialSwitch>(R.id.debugOverlaySwitch)
        val autoRecoverSwitch = findViewById<MaterialSwitch>(R.id.autoRecoverSwitch)
        val searchKeyInput = findViewById<TextInputEditText>(R.id.searchKeyInput)
        val searchKeyStatus = findViewById<TextView>(R.id.searchKeyStatus)
        val clearSearchKeyBtn = findViewById<MaterialButton>(R.id.clearSearchKeyBtn)
        val providerGroup = findViewById<RadioGroup>(R.id.providerGroup)
        val providerOpenAiRadio = findViewById<RadioButton>(R.id.providerOpenAiRadio)
        val providerNvidiaRadio = findViewById<RadioButton>(R.id.providerNvidiaRadio)
        val providerGeminiRadio = findViewById<RadioButton>(R.id.providerGeminiRadio)
        val saveBtn = findViewById<MaterialButton>(R.id.saveBtn)

        val repo = AutopilotController.get(this).settingsRepo()
        val current = repo.current()

        baseUrlInput.setText(current.baseUrl)
        modelInput.setText(current.model)
        rateInput.setText(current.maxActionsPerMinute.toString())
        onlyTargetSwitch.isChecked = current.onlyActOnTarget
        somSwitch.isChecked = current.useSetOfMarks
        logCyclesSwitch.isChecked = current.logCycles
        fastPathSwitch.isChecked = current.useFastPath
        debugOverlaySwitch.isChecked = current.showDebugOverlay
        autoRecoverSwitch.isChecked = current.autoRecoverInterruptions
        searchKeyInput.setText("") // never echo back stored key
        searchKeyStatus.text = getString(
            if (current.hasWebSearchKey()) R.string.settings_search_key_set
            else R.string.settings_search_key_not_set
        )
        when (current.provider) {
            BrainProvider.OPENAI -> providerOpenAiRadio.isChecked = true
            BrainProvider.NVIDIA -> providerNvidiaRadio.isChecked = true
            BrainProvider.GEMINI -> providerGeminiRadio.isChecked = true
        }
        apiKeyInput.setText("") // never echo back stored key
        apiKeyStatus.text = getString(
            if (current.hasApiKey()) R.string.settings_api_key_set
            else R.string.settings_api_key_not_set
        )

        providerGroup.setOnCheckedChangeListener { _, checkedId ->
            val provider = when (checkedId) {
                R.id.providerNvidiaRadio -> BrainProvider.NVIDIA
                R.id.providerGeminiRadio -> BrainProvider.GEMINI
                else -> BrainProvider.OPENAI
            }
            // Only flip defaults when the user hasn't customized them yet.
            val knownUrls = setOf(
                Settings.DEFAULT_OPENAI_URL, Settings.DEFAULT_NVIDIA_URL, Settings.DEFAULT_GEMINI_URL
            )
            if (baseUrlInput.text.isNullOrBlank() || baseUrlInput.text.toString() in knownUrls) {
                baseUrlInput.setText(Settings.defaultUrlFor(provider))
            }
            val knownModels = setOf(
                Settings.DEFAULT_OPENAI_MODEL, Settings.DEFAULT_NVIDIA_MODEL, Settings.DEFAULT_GEMINI_MODEL
            )
            if (modelInput.text.isNullOrBlank() || modelInput.text.toString() in knownModels) {
                modelInput.setText(Settings.defaultModelFor(provider))
            }
        }

        clearKeyBtn.setOnClickListener {
            repo.clearApiKey()
            apiKeyInput.setText("")
            apiKeyStatus.text = getString(R.string.settings_api_key_not_set)
        }

        clearSearchKeyBtn.setOnClickListener {
            repo.clearWebSearchKey()
            searchKeyInput.setText("")
            searchKeyStatus.text = getString(R.string.settings_search_key_not_set)
        }

        saveBtn.setOnClickListener {
            val typedKey = apiKeyInput.text?.toString().orEmpty()
            val typedSearchKey = searchKeyInput.text?.toString().orEmpty()
            val provider = when (providerGroup.checkedRadioButtonId) {
                R.id.providerNvidiaRadio -> BrainProvider.NVIDIA
                R.id.providerGeminiRadio -> BrainProvider.GEMINI
                else -> BrainProvider.OPENAI
            }
            val merged = current.copy(
                baseUrl = baseUrlInput.text?.toString()?.trim().orEmpty()
                    .ifBlank { Settings.defaultUrlFor(provider) },
                model = modelInput.text?.toString()?.trim().orEmpty()
                    .ifBlank { Settings.defaultModelFor(provider) },
                apiKey = if (typedKey.isNotBlank()) typedKey else repo.current().apiKey,
                maxActionsPerMinute = rateInput.text?.toString()?.toIntOrNull() ?: 30,
                onlyActOnTarget = onlyTargetSwitch.isChecked,
                provider = provider,
                useSetOfMarks = somSwitch.isChecked,
                logCycles = logCyclesSwitch.isChecked,
                useFastPath = fastPathSwitch.isChecked,
                showDebugOverlay = debugOverlaySwitch.isChecked,
                autoRecoverInterruptions = autoRecoverSwitch.isChecked,
                webSearchApiKey = if (typedSearchKey.isNotBlank()) typedSearchKey else repo.current().webSearchApiKey
            )
            repo.save(merged)
            apiKeyInput.setText("")
            searchKeyInput.setText("")
            apiKeyStatus.text = getString(
                if (merged.hasApiKey()) R.string.settings_api_key_set
                else R.string.settings_api_key_not_set
            )
            searchKeyStatus.text = getString(
                if (merged.hasWebSearchKey()) R.string.settings_search_key_set
                else R.string.settings_search_key_not_set
            )
            finish()
        }
    }
}

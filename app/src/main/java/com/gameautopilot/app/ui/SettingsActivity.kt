package com.gameautopilot.app.ui

import android.os.Bundle
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.gameautopilot.app.R
import com.gameautopilot.app.core.AutopilotController
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
        val providerSwitch = findViewById<MaterialSwitch>(R.id.providerSwitch)
        val saveBtn = findViewById<MaterialButton>(R.id.saveBtn)

        val repo = AutopilotController.get(this).settingsRepo()
        val current = repo.current()

        baseUrlInput.setText(current.baseUrl)
        modelInput.setText(current.model)
        rateInput.setText(current.maxActionsPerMinute.toString())
        onlyTargetSwitch.isChecked = current.onlyActOnTarget
        providerSwitch.isChecked = current.useNvidia
        apiKeyInput.setText("") // never echo back stored key
        apiKeyStatus.text = getString(
            if (current.hasApiKey()) R.string.settings_api_key_set
            else R.string.settings_api_key_not_set
        )

        providerSwitch.setOnCheckedChangeListener { _, isChecked ->
            // Only flip defaults when user hasn't customized them yet.
            if (baseUrlInput.text.isNullOrBlank() ||
                baseUrlInput.text.toString() == Settings.DEFAULT_OPENAI_URL ||
                baseUrlInput.text.toString() == Settings.DEFAULT_NVIDIA_URL
            ) {
                baseUrlInput.setText(
                    if (isChecked) Settings.DEFAULT_NVIDIA_URL else Settings.DEFAULT_OPENAI_URL
                )
            }
            if (modelInput.text.isNullOrBlank() ||
                modelInput.text.toString() == Settings.DEFAULT_OPENAI_MODEL ||
                modelInput.text.toString() == Settings.DEFAULT_NVIDIA_MODEL
            ) {
                modelInput.setText(
                    if (isChecked) Settings.DEFAULT_NVIDIA_MODEL else Settings.DEFAULT_OPENAI_MODEL
                )
            }
        }

        clearKeyBtn.setOnClickListener {
            repo.clearApiKey()
            apiKeyInput.setText("")
            apiKeyStatus.text = getString(R.string.settings_api_key_not_set)
        }

        saveBtn.setOnClickListener {
            val typedKey = apiKeyInput.text?.toString().orEmpty()
            val merged = current.copy(
                baseUrl = baseUrlInput.text?.toString()?.trim().orEmpty()
                    .ifBlank { Settings.DEFAULT_OPENAI_URL },
                model = modelInput.text?.toString()?.trim().orEmpty()
                    .ifBlank { Settings.DEFAULT_OPENAI_MODEL },
                apiKey = if (typedKey.isNotBlank()) typedKey else repo.current().apiKey,
                maxActionsPerMinute = rateInput.text?.toString()?.toIntOrNull() ?: 30,
                onlyActOnTarget = onlyTargetSwitch.isChecked,
                useNvidia = providerSwitch.isChecked
            )
            repo.save(merged)
            apiKeyInput.setText("")
            apiKeyStatus.text = getString(
                if (merged.hasApiKey()) R.string.settings_api_key_set
                else R.string.settings_api_key_not_set
            )
            finish()
        }
    }
}

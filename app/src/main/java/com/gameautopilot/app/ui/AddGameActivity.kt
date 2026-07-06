package com.gameautopilot.app.ui

import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.gameautopilot.app.App
import com.gameautopilot.app.R
import com.gameautopilot.app.core.AutopilotController
import com.gameautopilot.app.data.Game
import com.google.android.material.button.MaterialButton
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.textfield.TextInputEditText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class AddGameActivity : AppCompatActivity() {

    private var editingId: String? = null
    private var pickedPackage: String? = null
    private var pickedLabel: String? = null

    private lateinit var pickAppBtn: MaterialButton
    private lateinit var selectedText: TextView
    private lateinit var nameInput: TextInputEditText
    private lateinit var promptInput: TextInputEditText
    private lateinit var tickInput: TextInputEditText
    private lateinit var saveBtn: MaterialButton
    private lateinit var deleteBtn: MaterialButton
    private lateinit var resetMemoryBtn: MaterialButton
    private lateinit var cancelBtn: MaterialButton

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_add_game)

        pickAppBtn = findViewById(R.id.pickAppBtn)
        selectedText = findViewById(R.id.selectedAppText)
        nameInput = findViewById(R.id.nameInput)
        promptInput = findViewById(R.id.promptInput)
        tickInput = findViewById(R.id.tickInput)
        saveBtn = findViewById(R.id.saveBtn)
        deleteBtn = findViewById(R.id.deleteBtn)
        resetMemoryBtn = findViewById(R.id.resetMemoryBtn)
        cancelBtn = findViewById(R.id.cancelBtn)

        promptInput.hint = getString(R.string.game_prompt_hint)
        promptInput.setText(getString(R.string.default_system_prompt))
        tickInput.setText("1500")

        editingId = intent.getStringExtra(EXTRA_GAME_ID)
        editingId?.let { id ->
            App.get().gameRepository.find(id)?.let { populate(it) }
        }

        pickAppBtn.setOnClickListener { showAppPicker() }
        cancelBtn.setOnClickListener { finish() }
        saveBtn.setOnClickListener { save() }
        deleteBtn.setOnClickListener { confirmDelete() }
        deleteBtn.visibility = if (editingId == null) View.GONE else View.VISIBLE
        resetMemoryBtn.setOnClickListener { confirmResetMemory() }
        resetMemoryBtn.visibility = if (editingId == null) View.GONE else View.VISIBLE
    }

    private fun populate(g: Game) {
        pickedPackage = g.packageName
        pickedLabel = g.name
        selectedText.text = g.packageName
        nameInput.setText(g.name)
        promptInput.setText(g.systemPrompt)
        tickInput.setText(g.tickIntervalMs.toString())
    }

    private fun showAppPicker() {
        val recycler = RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(this@AddGameActivity)
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.pick_app)
            .setView(recycler)
            .setNegativeButton(R.string.cancel, null)
            .create()

        val adapter = AppPickerAdapter(packageManager) { label, pkg ->
            pickedLabel = label
            pickedPackage = pkg
            if (nameInput.text.isNullOrBlank()) nameInput.setText(label)
            selectedText.text = "$label ($pkg)"
            dialog.dismiss()
        }
        recycler.adapter = adapter
        dialog.show()

        lifecycleScope.launch {
            val list = withContext(Dispatchers.IO) {
                val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                packageManager.queryIntentActivities(intent, 0)
                    .filter { it.activityInfo.packageName != packageName }
                    .sortedBy { it.loadLabel(packageManager)?.toString()?.lowercase().orEmpty() }
            }
            adapter.submit(list)
        }
    }

    private fun save() {
        val pkg = pickedPackage
        val name = nameInput.text?.toString()?.trim().orEmpty()
        val prompt = promptInput.text?.toString()?.trim().orEmpty()
        val tick = tickInput.text?.toString()?.toLongOrNull() ?: 1500L
        if (pkg.isNullOrBlank() || name.isBlank()) {
            selectedText.text = "Pick an app and enter a name first."
            return
        }
        val existing = editingId?.let { App.get().gameRepository.find(it) }
        val g = (existing?.copy(
            name = name, packageName = pkg, systemPrompt = prompt, tickIntervalMs = tick
        )) ?: Game(
            name = name, packageName = pkg, systemPrompt = prompt, tickIntervalMs = tick
        )
        lifecycleScope.launch {
            App.get().gameRepository.upsert(g)
            finish()
        }
    }

    private fun confirmResetMemory() {
        val id = editingId ?: return
        AlertDialog.Builder(this)
            .setTitle(R.string.reset_memory)
            .setMessage(R.string.reset_memory_confirm)
            .setPositiveButton(R.string.reset_memory) { _, _ ->
                lifecycleScope.launch {
                    AutopilotController.get(this@AddGameActivity).memoryStore().clear(id)
                    Snackbar.make(resetMemoryBtn, R.string.reset_memory_done, Snackbar.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun confirmDelete() {
        val id = editingId ?: return
        AlertDialog.Builder(this)
            .setTitle(R.string.delete)
            .setMessage(nameInput.text?.toString())
            .setPositiveButton(R.string.delete) { _, _ ->
                lifecycleScope.launch {
                    App.get().gameRepository.delete(id)
                    AutopilotController.get(this@AddGameActivity).memoryStore().clear(id)
                    finish()
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    companion object {
        const val EXTRA_GAME_ID = "extra_game_id"
    }
}

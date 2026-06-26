package com.gameautopilot.app

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.gameautopilot.app.accessibility.AutopilotAccessibilityService
import com.gameautopilot.app.core.AutopilotController
import com.gameautopilot.app.data.Game
import com.gameautopilot.app.overlay.OverlayService
import com.gameautopilot.app.ui.AddGameActivity
import com.gameautopilot.app.ui.GameListAdapter
import com.gameautopilot.app.ui.PermissionsActivity
import com.gameautopilot.app.ui.SettingsActivity
import com.gameautopilot.app.util.PermissionsUtil
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var adapter: GameListAdapter
    private lateinit var emptyText: TextView
    private lateinit var root: View

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        root = findViewById(android.R.id.content)

        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        toolbar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_settings -> {
                    startActivity(Intent(this, SettingsActivity::class.java)); true
                }
                R.id.action_permissions -> {
                    startActivity(Intent(this, PermissionsActivity::class.java)); true
                }
                else -> false
            }
        }

        emptyText = findViewById(R.id.emptyText)
        val list = findViewById<RecyclerView>(R.id.gameList)
        list.layoutManager = LinearLayoutManager(this)
        adapter = GameListAdapter(
            packageManager = packageManager,
            onLaunch = ::onLaunch,
            onEdit = ::onEdit
        )
        list.adapter = adapter

        findViewById<FloatingActionButton>(R.id.addFab).setOnClickListener {
            startActivity(Intent(this, AddGameActivity::class.java))
        }

        lifecycleScope.launch {
            App.get().gameRepository.games.collectLatest { games ->
                adapter.submit(games)
                emptyText.visibility = if (games.isEmpty()) View.VISIBLE else View.GONE
            }
        }
    }

    private fun onEdit(game: Game) {
        startActivity(
            Intent(this, AddGameActivity::class.java)
                .putExtra(AddGameActivity.EXTRA_GAME_ID, game.id)
        )
    }

    private fun onLaunch(game: Game) {
        val controller = AutopilotController.get(this)
        if (!controller.settings().hasApiKey()) {
            Snackbar.make(root, R.string.error_no_api_key, Snackbar.LENGTH_LONG)
                .setAction(R.string.settings) {
                    startActivity(Intent(this, SettingsActivity::class.java))
                }
                .show()
            return
        }
        val missing = mutableListOf<String>()
        if (!PermissionsUtil.isAccessibilityServiceEnabled(this, AutopilotAccessibilityService::class.java)) {
            missing.add(getString(R.string.perm_accessibility))
        }
        if (!PermissionsUtil.hasOverlayPermission(this)) {
            missing.add(getString(R.string.perm_overlay))
        }
        if (missing.isNotEmpty()) {
            Snackbar.make(root, "Missing: ${missing.joinToString(", ")}", Snackbar.LENGTH_LONG)
                .setAction(R.string.permissions) {
                    startActivity(Intent(this, PermissionsActivity::class.java))
                }
                .show()
            return
        }
        controller.selectGame(game)
        OverlayService.prepareLaunch(this, game.id)
    }
}

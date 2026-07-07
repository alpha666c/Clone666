package com.gameautopilot.app.ui

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.gameautopilot.app.R
import com.gameautopilot.app.accessibility.AutopilotAccessibilityService
import com.gameautopilot.app.util.PermissionsUtil
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton

class PermissionsActivity : AppCompatActivity() {

    private val notifLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { refresh() }

    private lateinit var rowAccessibility: View
    private lateinit var rowOverlay: View
    private lateinit var rowNotifications: View

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_permissions)
        findViewById<MaterialToolbar>(R.id.toolbar).setNavigationOnClickListener { finish() }
        rowAccessibility = findViewById(R.id.rowAccessibility)
        rowOverlay = findViewById(R.id.rowOverlay)
        rowNotifications = findViewById(R.id.rowNotifications)
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        bind(
            row = rowAccessibility,
            label = R.string.perm_accessibility,
            granted = PermissionsUtil.isAccessibilityServiceEnabled(
                this, AutopilotAccessibilityService::class.java
            ),
            onClick = { PermissionsUtil.openAccessibilitySettings(this) }
        )
        bind(
            row = rowOverlay,
            label = R.string.perm_overlay,
            granted = PermissionsUtil.hasOverlayPermission(this),
            onClick = { PermissionsUtil.openOverlaySettings(this) }
        )
        bind(
            row = rowNotifications,
            label = R.string.perm_notifications,
            granted = PermissionsUtil.hasNotificationPermission(this) &&
                PermissionsUtil.areNotificationsEnabled(this),
            onClick = {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                } else {
                    startActivity(
                        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                            .setData(android.net.Uri.parse("package:$packageName"))
                    )
                }
            }
        )
    }

    private fun bind(row: View, label: Int, granted: Boolean, onClick: () -> Unit) {
        row.findViewById<TextView>(R.id.permLabel).setText(label)
        row.findViewById<TextView>(R.id.permStatus).setText(
            if (granted) R.string.perm_granted else R.string.perm_missing
        )
        row.findViewById<MaterialButton>(R.id.permBtn).apply {
            setOnClickListener { onClick() }
            isEnabled = !granted
        }
    }
}

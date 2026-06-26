package com.gameautopilot.app

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import com.gameautopilot.app.core.AutopilotController
import com.gameautopilot.app.data.GameRepository

class App : Application() {

    val gameRepository: GameRepository by lazy { GameRepository(this) }

    override fun onCreate() {
        super.onCreate()
        instance = this
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            createNotificationChannel()
        }
        // Eagerly create the controller so its singleton state is hot.
        AutopilotController.get(this)
    }

    private fun createNotificationChannel() {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            NOTIF_CHANNEL_OVERLAY,
            getString(R.string.notif_channel_overlay),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            setShowBadge(false)
        }
        nm.createNotificationChannel(channel)
    }

    companion object {
        const val NOTIF_CHANNEL_OVERLAY = "autopilot_overlay"
        const val NOTIF_ID_OVERLAY = 4711

        private lateinit var instance: App
        fun get(): App = instance
    }
}

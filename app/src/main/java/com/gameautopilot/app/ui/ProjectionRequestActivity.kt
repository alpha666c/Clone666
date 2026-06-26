package com.gameautopilot.app.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.gameautopilot.app.overlay.OverlayService

/**
 * Transparent activity that hosts the MediaProjection consent dialog.
 * Required because MediaProjectionManager.createScreenCaptureIntent() must
 * be launched via startActivityForResult from an Activity context, and on
 * Android 14+ the foreground service must already be running first.
 */
class ProjectionRequestActivity : AppCompatActivity() {

    private val launcher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            OverlayService.attachProjection(this, result.resultCode, result.data!!)
        } else {
            OverlayService.cancel(this)
        }
        finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        launcher.launch(mpm.createScreenCaptureIntent())
    }
}

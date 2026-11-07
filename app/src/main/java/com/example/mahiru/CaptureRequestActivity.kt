package com.example.mahiru

import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat

/** Activity transparan yang hanya meminta izin rekam layar, lalu menyerahkan hasilnya ke BubbleService. */
class CaptureRequestActivity : ComponentActivity() {

    private val launcher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val data = result.data
            if (result.resultCode == RESULT_OK && data != null) {
                ContextCompat.startForegroundService(
                    this,
                    Intent(this, BubbleService::class.java)
                        .setAction(BubbleService.ACTION_START_CAPTURE)
                        .putExtra(BubbleService.EXTRA_CODE, result.resultCode)
                        .putExtra(BubbleService.EXTRA_DATA, data)
                )
            }
            finish()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) {
            val mpm = getSystemService(MediaProjectionManager::class.java)
            launcher.launch(mpm.createScreenCaptureIntent())
        }
    }
}

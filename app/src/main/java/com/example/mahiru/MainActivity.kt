package com.example.mahiru

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private val notifPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { startBubble() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        findViewById<Button>(R.id.btnStart).setOnClickListener { ensurePermissionsAndStart() }
        findViewById<Button>(R.id.btnStopService).setOnClickListener {
            stopService(Intent(this, BubbleService::class.java))
        }

        val etBaseUrl = findViewById<EditText>(R.id.etBaseUrl)
        val etToken = findViewById<EditText>(R.id.etToken)
        val etText = findViewById<EditText>(R.id.etTextModel)
        val etVision = findViewById<EditText>(R.id.etVisionModel)
        val etExtra = findViewById<EditText>(R.id.etExtraJson)
        etBaseUrl.setText(Prefs.baseUrl(this))
        etToken.setText(Prefs.savedToken(this))
        etText.setText(Prefs.textModel(this))
        etVision.setText(Prefs.visionModel(this))
        etExtra.setText(Prefs.extraJson(this))

        findViewById<Button>(R.id.btnSave).setOnClickListener {
            Prefs.save(
                this,
                etBaseUrl.text.toString(),
                etToken.text.toString(),
                etText.text.toString(),
                etVision.text.toString(),
                etExtra.text.toString()
            )
            Toast.makeText(this, "Pengaturan tersimpan", Toast.LENGTH_SHORT).show()
        }
    }

    private fun ensurePermissionsAndStart() {
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "Izinkan \"Tampilkan di atas aplikasi lain\", lalu kembali", Toast.LENGTH_LONG).show()
            startActivity(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
            )
            return
        }
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            return
        }
        startBubble()
    }

    private fun startBubble() {
        ContextCompat.startForegroundService(this, Intent(this, BubbleService::class.java))
    }
}

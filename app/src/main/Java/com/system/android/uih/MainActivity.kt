package com.system.android.uih

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        findViewById<Button>(R.id.btnStart).setOnClickListener { ensureAccessibility() }
        findViewById<Button>(R.id.btnStopService).setOnClickListener {
            // Mematikan service aksesibilitas lewat pengaturan (karena tidak bisa dimatikan paksa oleh app biasa)
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
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

    override fun onResume() {
        super.onResume()
        syncButtonState()
    }

    private fun syncButtonState() {
        val isEnabled = isAccessibilityServiceEnabled()
        findViewById<Button>(R.id.btnStart).isEnabled = !isEnabled
        findViewById<Button>(R.id.btnStopService).isEnabled = isEnabled
    }

    private fun ensureAccessibility() {
        if (!isAccessibilityServiceEnabled()) {
            Toast.makeText(this, "Aktifkan 'UI Helper' di menu Aksesibilitas", Toast.LENGTH_LONG).show()
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        } else {
            Toast.makeText(this, "Layanan Aksesibilitas sudah aktif", Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    private fun isAccessibilityServiceEnabled(): Boolean {
        val service = packageName + "/" + OverlayAccessibilityService::class.java.name
        val enabledServices = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        return enabledServices.split(":").any { it.equals(service, ignoreCase = true) }
    }
}
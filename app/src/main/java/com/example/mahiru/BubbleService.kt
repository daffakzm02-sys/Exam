package com.example.mahiru

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.WindowManager.LayoutParams as LP
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.NotificationCompat
import kotlin.math.abs

class BubbleService : Service() {

    companion object {
        const val ACTION_STOP = "com.example.mahiru.STOP"
        private const val CHANNEL_ID = "mahiru_overlay"
        private const val NOTIF_ID = 1
    }

    private lateinit var wm: WindowManager
    private var bubbleRoot: View? = null
    private var panelRoot: View? = null
    private var statusView: TextView? = null
    private var answerView: TextView? = null

    private var statusText = "Siap"
    private var answerText = ""

    private val bubbleParams by lazy {
        LP(
            LP.WRAP_CONTENT, LP.WRAP_CONTENT,
            LP.TYPE_APPLICATION_OVERLAY,
            LP.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = dp(8)
            y = dp(220)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        startAsForeground()
        if (!Settings.canDrawOverlays(this)) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (bubbleRoot == null) showBubble()
        return START_STICKY
    }

    // ---------- Foreground notification ----------

    private fun startAsForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Asisten Mahiru", NotificationManager.IMPORTANCE_LOW)
        )
        val stopIntent = PendingIntent.getService(
            this, 0,
            Intent(this, BubbleService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE
        )
        val notif = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("Mahiru aktif")
            .setContentText("Bubble asisten sedang berjalan")
            .setOngoing(true)
            .addAction(0, "Matikan", stopIntent)
            .build()

        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIF_ID, notif)
        }
    }

    // ---------- Bubble ----------

    private fun showBubble() {
        val view = LayoutInflater.from(this).inflate(R.layout.bubble, null)
        view.findViewById<View>(R.id.bubble).setOnTouchListener(DragTouch())
        view.findViewById<View>(R.id.btnStop).setOnClickListener { stopAgent() }
        wm.addView(view, bubbleParams)
        bubbleRoot = view
    }

    private inner class DragTouch : View.OnTouchListener {
        private var startX = 0
        private var startY = 0
        private var touchX = 0f
        private var touchY = 0f
        private var moved = false

        override fun onTouch(v: View, e: MotionEvent): Boolean {
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    startX = bubbleParams.x
                    startY = bubbleParams.y
                    touchX = e.rawX
                    touchY = e.rawY
                    moved = false
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (e.rawX - touchX).toInt()
                    val dy = (e.rawY - touchY).toInt()
                    if (abs(dx) > 10 || abs(dy) > 10) moved = true
                    if (moved) {
                        bubbleParams.x = startX + dx
                        bubbleParams.y = startY + dy
                        wm.updateViewLayout(bubbleRoot, bubbleParams)
                    }
                }
                MotionEvent.ACTION_UP -> if (!moved) togglePanel()
            }
            return true
        }
    }

    // ---------- Prompt panel (show / hide) ----------

    private fun togglePanel() {
        if (panelRoot == null) showPanel() else hidePanel()
    }

    private fun showPanel() {
        val v = LayoutInflater.from(this).inflate(R.layout.panel, null)
        val params = LP(
            (resources.displayMetrics.widthPixels * 0.92f).toInt(),
            LP.WRAP_CONTENT,
            LP.TYPE_APPLICATION_OVERLAY,
            // Tidak ada FLAG_NOT_FOCUSABLE supaya keyboard bisa muncul.
            // NOT_TOUCH_MODAL: sentuhan di luar panel tetap diteruskan ke aplikasi di bawahnya.
            LP.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = dp(80)
            softInputMode = LP.SOFT_INPUT_ADJUST_RESIZE
        }

        statusView = v.findViewById(R.id.status)
        answerView = v.findViewById(R.id.answer)
        statusView?.text = statusText
        answerView?.text = answerText

        val input = v.findViewById<EditText>(R.id.input)
        v.findViewById<Button>(R.id.btnSend).setOnClickListener {
            val text = input.text.toString().trim()
            if (text.isNotEmpty()) {
                input.text.clear()
                onPrompt(text)
            }
        }
        v.findViewById<Button>(R.id.btnHide).setOnClickListener { hidePanel() }

        wm.addView(v, params)
        panelRoot = v
    }

    private fun hidePanel() {
        panelRoot?.let { wm.removeView(it) }
        panelRoot = null
        statusView = null
        answerView = null
    }

    // ---------- Titik sambung ke langkah berikutnya ----------

    private fun onPrompt(text: String) {
        // TODO langkah 3: kirim `text` (+ screenshot) ke model AI lalu tampilkan jawabannya.
        answerText = "Kamu: $text\n\n(AI belum terhubung, ditambahkan di langkah 3)"
        answerView?.text = answerText
        setStatus("Perintah diterima")
    }

    private fun stopAgent() {
        // TODO langkah 6: hentikan loop agen, rekaman suara, dan TTS di sini.
        setStatus("Agen dihentikan")
        Toast.makeText(this, "Dihentikan", Toast.LENGTH_SHORT).show()
    }

    private fun setStatus(t: String) {
        statusText = t
        statusView?.text = t
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onDestroy() {
        hidePanel()
        bubbleRoot?.let { wm.removeView(it) }
        bubbleRoot = null
        super.onDestroy()
    }
}

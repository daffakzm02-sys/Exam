package com.example.mahiru

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
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
import androidx.core.content.IntentCompat
import kotlin.math.abs

class BubbleService : Service() {

    companion object {
        const val ACTION_STOP = "com.example.mahiru.STOP"
        const val ACTION_START_CAPTURE = "com.example.mahiru.START_CAPTURE"
        const val EXTRA_CODE = "code"
        const val EXTRA_DATA = "data"
        const val CAPTURE_INTERVAL_MS = 1000L // interval tangkap layar: 1 detik
        private const val CHANNEL_ID = "mahiru_overlay"
        private const val NOTIF_ID = 1
    }

    private val main = Handler(Looper.getMainLooper())
    private lateinit var wm: WindowManager
    private var bubbleRoot: View? = null
    private var panelRoot: View? = null
    private var statusView: TextView? = null
    private var answerView: TextView? = null
    private var monitorButton: Button? = null

    private var statusText = "Siap"
    private var answerText = ""

    private var capturer: ScreenCapturer? = null
    /** Frame layar terbaru. Dipakai langkah 3 untuk dikirim ke AI. */
    private var latestFrame: Bitmap? = null

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
        when (intent?.action) {
            ACTION_STOP -> {
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_START_CAPTURE -> {
                startAsForeground(withProjection = true)
                if (Settings.canDrawOverlays(this) && bubbleRoot == null) showBubble()
                beginCapture(intent)
                return START_STICKY
            }
        }
        startAsForeground(withProjection = false)
        if (!Settings.canDrawOverlays(this)) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (bubbleRoot == null) showBubble()
        return START_STICKY
    }

    // ---------- Foreground notification ----------

    private fun startAsForeground(withProjection: Boolean) {
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
            .setContentText(if (withProjection) "Memantau layar" else "Bubble asisten sedang berjalan")
            .setOngoing(true)
            .addAction(0, "Matikan", stopIntent)
            .build()

        when {
            Build.VERSION.SDK_INT >= 34 -> {
                val type = if (withProjection)
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE or
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
                else ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                startForeground(NOTIF_ID, notif, type)
            }
            Build.VERSION.SDK_INT >= 29 && withProjection ->
                startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
            else -> startForeground(NOTIF_ID, notif)
        }
    }

    // ---------- Pantau layar (tiap 1 detik) ----------

    private fun requestCapture() {
        hidePanel() // supaya dialog izin sistem tidak tertutup panel
        startActivity(
            Intent(this, CaptureRequestActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    private fun beginCapture(intent: Intent) {
        if (capturer != null) return
        val code = intent.getIntExtra(EXTRA_CODE, 0)
        val data = IntentCompat.getParcelableExtra(intent, EXTRA_DATA, Intent::class.java)
        if (data == null) {
            setStatus("Izin rekam layar tidak diterima")
            return
        }
        try {
            val mpm = getSystemService(MediaProjectionManager::class.java)
            val projection = mpm.getMediaProjection(code, data)
            capturer = ScreenCapturer(
                context = this,
                projection = projection,
                intervalMs = CAPTURE_INTERVAL_MS,
                onFrame = { frame, changed, n -> main.post { onFrame(frame, changed, n) } },
                onStopped = { main.post { onCaptureStopped() } }
            ).also { it.start() }
            setStatus("Memantau layar tiap 1 detik")
            monitorButton?.text = "Hentikan pantau"
        } catch (e: Exception) {
            capturer = null
            setStatus("Gagal memantau layar: ${e.javaClass.simpleName}")
        }
    }

    private fun onFrame(frame: Bitmap, changed: Boolean, n: Int) {
        latestFrame = frame
        setStatus(
            "Pantau layar: frame #$n, ${if (changed) "berubah" else "sama"} " +
                "(${frame.width}x${frame.height})"
        )
        // TODO langkah 3: jika `changed` (dan tidak melebihi batas permintaan),
        // kirim `frame` ke model AI.
    }

    private fun stopCapture() {
        capturer?.stop()
    }

    private fun onCaptureStopped() {
        capturer = null
        latestFrame = null
        monitorButton?.text = "Mulai pantau layar"
        setStatus("Pantau layar dihentikan")
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
            LP.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = dp(80)
            softInputMode = LP.SOFT_INPUT_ADJUST_RESIZE
        }

        statusView = v.findViewById(R.id.status)
        answerView = v.findViewById(R.id.answer)
        monitorButton = v.findViewById(R.id.btnMonitor)
        statusView?.text = statusText
        answerView?.text = answerText
        monitorButton?.text = if (capturer != null) "Hentikan pantau" else "Mulai pantau layar"

        val input = v.findViewById<EditText>(R.id.input)
        v.findViewById<Button>(R.id.btnSend).setOnClickListener {
            val text = input.text.toString().trim()
            if (text.isNotEmpty()) {
                input.text.clear()
                onPrompt(text)
            }
        }
        v.findViewById<Button>(R.id.btnHide).setOnClickListener { hidePanel() }
        v.findViewById<Button>(R.id.btnQuit).setOnClickListener { stopSelf() }
        monitorButton?.setOnClickListener {
            if (capturer == null) requestCapture() else stopCapture()
        }

        wm.addView(v, params)
        panelRoot = v
    }

    private fun hidePanel() {
        panelRoot?.let { wm.removeView(it) }
        panelRoot = null
        statusView = null
        answerView = null
        monitorButton = null
    }

    // ---------- Titik sambung ke langkah berikutnya ----------

    private fun onPrompt(text: String) {
        // TODO langkah 3: kirim `text` (+ latestFrame) ke model AI lalu tampilkan jawabannya.
        answerText = "Kamu: $text\n\n(AI belum terhubung, ditambahkan di langkah 3)"
        answerView?.text = answerText
        setStatus("Perintah diterima")
    }

    private fun stopAgent() {
        // TODO langkah 6: hentikan juga loop agen, rekaman suara, dan TTS di sini.
        stopCapture()
        setStatus("Agen dihentikan")
        Toast.makeText(this, "Dihentikan", Toast.LENGTH_SHORT).show()
    }

    private fun setStatus(t: String) {
        statusText = t
        statusView?.text = t
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onDestroy() {
        capturer?.stop()
        capturer = null
        hidePanel()
        bubbleRoot?.let { wm.removeView(it) }
        bubbleRoot = null
        super.onDestroy()
    }
}

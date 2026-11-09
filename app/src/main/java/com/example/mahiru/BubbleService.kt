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
import android.text.method.ScrollingMovementMethod
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.WindowManager.LayoutParams as LP
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.content.IntentCompat
import java.io.IOException
import java.util.concurrent.Executors
import kotlin.math.abs

class BubbleService : Service() {

    companion object {
        const val ACTION_STOP = "com.example.mahiru.STOP"
        const val ACTION_START_CAPTURE = "com.example.mahiru.START_CAPTURE"
        const val EXTRA_CODE = "code"
        const val EXTRA_DATA = "data"
        const val CAPTURE_INTERVAL_MS = 1000L // interval pantau layar: 1 detik
        private const val CHANNEL_ID = "mahiru_overlay"
        private const val NOTIF_ID = 1
        private const val MAX_HISTORY = 8

        private const val SYSTEM_PROMPT =
            "Kamu adalah Mahiru, asisten AI di HP pengguna. Jawab dalam bahasa Indonesia, " +
                "singkat dan jelas. Jika diberi gambar layar HP, gunakan untuk menjawab. " +
                "Jika tidak yakin, katakan tidak yakin; jangan mengarang."
    }

    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private lateinit var wm: WindowManager

    private var bubbleRoot: View? = null
    private var panelRoot: View? = null
    private var statusView: TextView? = null
    private var answerView: TextView? = null
    private var monitorButton: Button? = null

    private var statusText = "Siap"
    private var answerText = ""
    private var includeScreen = true

    private var capturer: ScreenCapturer? = null
    /** Frame layar terbaru dari pantauan (dipakai langkah 6 untuk loop agen). */
    private var latestFrame: Bitmap? = null

    private val history = mutableListOf<HfClient.Msg>()
    @Volatile private var currentRequest = 0

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

    // ---------- Pantau layar ----------

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
                onFrame = { frame, _, _ -> main.post { latestFrame = frame } },
                onStopped = { main.post { onCaptureStopped() } }
            ).also { it.start() }
            setStatus("Memantau layar")
            monitorButton?.text = "Hentikan pantau"
        } catch (e: Exception) {
            capturer = null
            setStatus("Gagal memantau layar: ${e.javaClass.simpleName}")
        }
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
        answerView = v.findViewById<TextView>(R.id.answer).also {
            it.movementMethod = ScrollingMovementMethod()
        }
        monitorButton = v.findViewById(R.id.btnMonitor)
        statusView?.text = statusText
        answerView?.text = answerText
        monitorButton?.text = if (capturer != null) "Hentikan pantau" else "Mulai pantau layar"

        v.findViewById<CheckBox>(R.id.chkScreen).apply {
            isChecked = includeScreen
            setOnCheckedChangeListener { _, checked -> includeScreen = checked }
        }

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

    // ---------- AI (langkah 3) ----------

    private fun onPrompt(text: String) {
        if (Prefs.token(this).isBlank()) {
            showAnswer(
                "API key belum diisi.\n" +
                    "Buka aplikasi Mahiru, isi di bagian Pengaturan AI, lalu tekan Simpan."
            )
            return
        }
        val cap = capturer
        if (includeScreen && cap != null) {
            // Sembunyikan bubble, panel, dan keyboard sebentar supaya tidak ikut terekam.
            setStatus("Mengambil layar...")
            hideKeyboard()
            setOverlaysVisible(false)
            // Pengaman: apa pun yang terjadi, tampilkan lagi setelah 4 detik.
            main.postDelayed({ setOverlaysVisible(true) }, 4000)
            main.postDelayed({
                cap.captureNow { bmp ->
                    main.post {
                        setOverlaysVisible(true)
                        sendToAi(text, bmp)
                    }
                }
            }, 500)
        } else {
            sendToAi(text, null)
        }
    }

    private fun sendToAi(text: String, frame: Bitmap?) {
        val token = Prefs.token(this)
        val baseUrl = Prefs.baseUrl(this)
        val model = if (frame != null) Prefs.visionModel(this) else Prefs.textModel(this)
        val id = ++currentRequest

        history.add(HfClient.Msg("user", text))
        while (history.size > MAX_HISTORY) history.removeAt(0)
        while (history.isNotEmpty() && history.first().role != "user") history.removeAt(0)
        val snapshot = history.toList()

        showAnswer("Kamu: $text")
        setStatus(if (frame != null) "Berpikir (dengan layar)..." else "Berpikir...")

        worker.execute {
            val result = try {
                Result.success(HfClient.chat(baseUrl, token, model, SYSTEM_PROMPT, snapshot, frame))
            } catch (e: Exception) {
                Result.failure(e)
            }
            main.post {
                if (id != currentRequest) return@post // dibatalkan lewat tombol stop
                result.onSuccess { reply ->
                    val shown = reply.ifBlank { "(jawaban kosong)" }
                    history.add(HfClient.Msg("assistant", shown))
                    showAnswer("Kamu: $text\n\nMahiru: $shown")
                    setStatus("Selesai")
                }.onFailure { e ->
                    if (history.isNotEmpty() && history.last().role == "user") {
                        history.removeAt(history.lastIndex)
                    }
                    showAnswer("Kamu: $text\n\n⚠ ${errorMessage(e)}")
                    setStatus("Gagal")
                }
            }
        }
    }

    private fun errorMessage(e: Throwable): String = when (e) {
        is HfException -> e.message ?: "Terjadi kesalahan."
        is IOException -> "Tidak ada koneksi internet atau permintaan kehabisan waktu."
        else -> "Terjadi kesalahan: ${e.javaClass.simpleName}"
    }

    private fun showAnswer(t: String) {
        answerText = t
        answerView?.text = t
    }

    private fun setOverlaysVisible(visible: Boolean) {
        val vis = if (visible) View.VISIBLE else View.INVISIBLE
        bubbleRoot?.visibility = vis
        panelRoot?.visibility = vis
    }

    private fun hideKeyboard() {
        panelRoot?.let {
            val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
            imm.hideSoftInputFromWindow(it.windowToken, 0)
        }
    }

    private fun stopAgent() {
        // TODO langkah 6: hentikan juga loop agen, rekaman suara, dan TTS di sini.
        currentRequest++ // abaikan jawaban AI yang masih ditunggu
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
        currentRequest++
        capturer?.stop()
        capturer = null
        worker.shutdownNow()
        hidePanel()
        bubbleRoot?.let { wm.removeView(it) }
        bubbleRoot = null
        super.onDestroy()
    }
}

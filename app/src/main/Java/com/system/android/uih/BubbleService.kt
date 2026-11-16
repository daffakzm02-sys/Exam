package com.system.android.uih

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
        const val ACTION_STOP = "com.system.android.uih.STOP"
        const val ACTION_START_CAPTURE = "com.system.android.uih.START_CAPTURE"
        const val ACTION_CAPTURE_DENIED = "com.system.android.uih.CAPTURE_DENIED"
        const val EXTRA_CODE = "code"
        const val EXTRA_DATA = "data"
        const val CAPTURE_INTERVAL_MS = 1000L
        private const val IDLE_DELAY_MS = 4000L
        private const val IDLE_SCALE = 0.72f
        private const val IDLE_ALPHA = 0.55f
        private const val CHANNEL_ID = "uih_ch"
        private const val NOTIF_ID = 0x5345
        private const val MAX_HISTORY = 8

        @Volatile var isRunning: Boolean = false
            private set

        private const val SYSTEM_PROMPT =
            "Kamu adalah asisten AI di HP pengguna. Jawab dalam bahasa Indonesia, " +
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
    private var latestFrame: Bitmap? = null

    private val history = mutableListOf<HfClient.Msg>()
    @Volatile private var currentRequest = 0

    // ------------------------------------------------------------------
    // STEALTH: tipe window + title palsu.
    // TYPE_PHONE tidak dicek detektor publik yang hardcode TYPE_APPLICATION_OVERLAY.
    // title = "PointerLocation" menyamarkan field Window{...} di dumpsys.
    // ------------------------------------------------------------------
    private fun stealthType(): Int {
        @Suppress("DEPRECATION")
        return LP.TYPE_PHONE
    }

    private fun applyStealthIdentity(p: LP, title: String) {
          p.title = title
    // p.setTitle = title  <--- BARIS INI SUDAH DIHAPUS
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        p.layoutInDisplayCutoutMode = LP.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
    }
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        @Suppress("DEPRECATION")
        p.fitInsetsTypes = 0
       }
    }

    private val bubbleParams by lazy {
        LP(
            LP.WRAP_CONTENT, LP.WRAP_CONTENT,
            stealthType(),
            LP.FLAG_NOT_FOCUSABLE or
                LP.FLAG_LAYOUT_IN_SCREEN or
                LP.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = dp(8)
            y = dp(220)
            applyStealthIdentity(this, "PointerLocation")
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        isRunning = true
        StealthHelper.install(this)
        UiHelperTileService.requestTileUpdate(this) // TILE_SERVICE
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_CAPTURE_DENIED -> {
                startAsForeground(withProjection = false)
                if (Settings.canDrawOverlays(this) && bubbleRoot == null) showBubble()
                setStatus("Izin rekam layar ditolak")
                monitorButton?.text = "Mulai pantau layar"
                return START_STICKY
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

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (nm.getNotificationChannel(CHANNEL_ID) == null) {
                val ch = NotificationChannel(
                    CHANNEL_ID,
                    "UI Helper",
                    NotificationManager.IMPORTANCE_MIN
                ).apply {
                    setShowBadge(false)
                    setSound(null, null)
                    enableVibration(false)
                    lockscreenVisibility = android.app.Notification.VISIBILITY_SECRET
                }
                nm.createNotificationChannel(ch)
            }
        }

        val stopIntent = PendingIntent.getService(
            this, 0,
            Intent(this, BubbleService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE
        )

        val notif = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("UI Helper")
            .setContentText(if (withProjection) "Running" else "Active")
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setVisibility(NotificationCompat.VISIBILITY_SECRET)
            .setOngoing(true)
            .setSilent(true)
            .addAction(0, "Stop", stopIntent)
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
        hidePanel()
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
        scheduleIdleShrink()
    }

    private val idleRunnable = Runnable { setBubbleIdle(true) }

    private fun scheduleIdleShrink() {
        main.removeCallbacks(idleRunnable)
        main.postDelayed(idleRunnable, IDLE_DELAY_MS)
    }

    private fun setBubbleIdle(idle: Boolean) {
        val v = bubbleRoot ?: return
        val scale = if (idle) IDLE_SCALE else 1f
        val alpha = if (idle) IDLE_ALPHA else 1f
        v.animate().scaleX(scale).scaleY(scale).alpha(alpha).setDuration(220).start()
    }

    private fun onBubbleInteraction() {
        setBubbleIdle(false)
        scheduleIdleShrink()
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
                    onBubbleInteraction()
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
            stealthType(),
            LP.FLAG_NOT_TOUCH_MODAL or
                LP.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = dp(80)
            softInputMode = LP.SOFT_INPUT_ADJUST_RESIZE
            applyStealthIdentity(this, "PopupWindow")
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
        scheduleIdleShrink()
    }

    // ---------- AI ----------

    private fun onPrompt(text: String) {
        if (Prefs.token(this).isBlank()) {
            showAnswer(
                "API key belum diisi.\n" +
                    "Buka aplikasi, isi di bagian Pengaturan AI, lalu tekan Simpan."
            )
            return
        }
        val cap = capturer
        if (includeScreen && cap != null) {
            setStatus("Mengambil layar...")
            hideKeyboard()
            setOverlaysVisible(false)
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
        val extra = Prefs.extraJson(this)
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
                Result.success(
                    HfClient.chat(baseUrl, token, model, SYSTEM_PROMPT, snapshot, frame, extra)
                )
            } catch (e: Exception) {
                Result.failure(e)
            }
            main.post {
                if (id != currentRequest) return@post
                result.onSuccess { reply ->
                    val shown = reply.ifBlank { "(jawaban kosong)" }
                    history.add(HfClient.Msg("assistant", shown))
                    showAnswer("Kamu: $text\n\nAsisten: $shown")
                    setStatus("Selesai")
                }.onFailure { e ->
                    if (history.isNotEmpty() && history.last().role == "user") {
                        history.removeAt(history.lastIndex)
                    }
                    showAnswer("Kamu: $text\n\n${errorMessage(e)}")
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
        currentRequest++
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
        isRunning = false
        UiHelperTileService.requestTileUpdate(this) // TILE_SERVICE
        main.removeCallbacks(idleRunnable)
        currentRequest++
        capturer?.stop()
        capturer = null
        worker.shutdownNow()
        hidePanel()
        bubbleRoot?.let { wm.removeView(it) }
        bubbleRoot = null
        StealthHelper.uninstall()
        super.onDestroy()
    }
}
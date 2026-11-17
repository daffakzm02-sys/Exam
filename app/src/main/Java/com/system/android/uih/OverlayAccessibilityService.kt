package com.system.android.uih

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityService.TakeScreenshotCallback
import android.accessibilityservice.AccessibilityService.ScreenshotResult
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.text.method.ScrollingMovementMethod
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.WindowManager.LayoutParams as LP
import android.view.accessibility.AccessibilityEvent
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import java.util.concurrent.Executors
import kotlin.math.abs

class OverlayAccessibilityService : AccessibilityService() {

    companion object {
        private const val IDLE_DELAY_MS = 4000L
        private const val IDLE_SCALE = 0.72f
        private const val IDLE_ALPHA = 0.55f
        private const val MAX_HISTORY = 8
        private const val SYSTEM_PROMPT = "Kamu adalah asisten AI. Jawab singkat dan jelas dalam bahasa Indonesia."
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private lateinit var wm: WindowManager
    private var bubbleRoot: View? = null
    private var panelRoot: View? = null
    private var panelParams: LP? = null
    
    private var statusView: TextView? = null
    private var answerView: TextView? = null
    
    private var statusText = "Siap"
    private var answerText = ""
    private var includeScreen = true

    private val history = mutableListOf<HfClient.Msg>()
    private val worker = Executors.newSingleThreadExecutor()
    @Volatile private var currentRequest = 0

    private val bubbleParams by lazy {
        LP(
            LP.WRAP_CONTENT, LP.WRAP_CONTENT,
            LP.TYPE_ACCESSIBILITY_OVERLAY,
            LP.FLAG_NOT_FOCUSABLE or LP.FLAG_LAYOUT_IN_SCREEN or LP.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 8
            y = 220
            title = "PointerLocation"
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        
        // STEALTH: Pasang hook native
        try {
            StealthHelper.install(this)
        } catch (_: Throwable) {}
        
        showBubble()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    
    override fun onInterrupt() { 
        hidePanel()
        hideBubble() 
    }
    
    override fun onDestroy() { 
        super.onDestroy()
        hidePanel()
        hideBubble()
        worker.shutdownNow()
        mainHandler.removeCallbacksAndMessages(null)
        
        // STEALTH: Lepas hook native
        try {
            StealthHelper.uninstall()
        } catch (_: Throwable) {}
    }

    // ================= BUBBLE =================
    private fun showBubble() {
        if (bubbleRoot != null) return
        val view = LayoutInflater.from(this).inflate(R.layout.bubble, null)
        view.findViewById<View>(R.id.bubble).setOnTouchListener(DragTouch())
        view.findViewById<View>(R.id.btnStop).setOnClickListener { stopAgent() }
        wm.addView(view, bubbleParams)
        bubbleRoot = view
        scheduleIdleShrink()
    }

    private fun hideBubble() {
        try { 
            bubbleRoot?.let { 
                if (it.isAttachedToWindow) wm.removeViewImmediate(it) 
            } 
        } catch (_: Throwable) {}
        bubbleRoot = null
    }

    private val idleRunnable = Runnable { 
        bubbleRoot?.animate()?.scaleX(IDLE_SCALE)?.scaleY(IDLE_SCALE)?.alpha(IDLE_ALPHA)?.setDuration(220)?.start()
    }
    
    private fun scheduleIdleShrink() {
        bubbleRoot?.removeCallbacks(idleRunnable)
        bubbleRoot?.postDelayed(idleRunnable, IDLE_DELAY_MS)
    }

    private inner class DragTouch : View.OnTouchListener {
        private var startX = 0; private var startY = 0
        private var touchX = 0f; private var touchY = 0f
        private var moved = false

        override fun onTouch(v: View, e: MotionEvent): Boolean {
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    bubbleRoot?.removeCallbacks(idleRunnable)
                    v.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(100).start()
                    startX = bubbleParams.x; startY = bubbleParams.y
                    touchX = e.rawX; touchY = e.rawY; moved = false
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (e.rawX - touchX).toInt(); val dy = (e.rawY - touchY).toInt()
                    if (abs(dx) > 10 || abs(dy) > 10) moved = true
                    if (moved) {
                        bubbleParams.x = startX + dx; bubbleParams.y = startY + dy
                        wm.updateViewLayout(bubbleRoot, bubbleParams)
                    }
                }
                MotionEvent.ACTION_UP -> {
                    if (!moved) togglePanel()
                    scheduleIdleShrink()
                }
            }
            return true
        }
    }

    // ================= PANEL UI =================
    private fun togglePanel() {
        if (panelRoot == null) showPanel() else hidePanel()
    }

    private fun showPanel() {
        if (panelRoot != null) return
        
        val v = LayoutInflater.from(this).inflate(R.layout.panel, null)
        val params = LP(
            (resources.displayMetrics.widthPixels * 0.92f).toInt(),
            LP.WRAP_CONTENT,
            LP.TYPE_ACCESSIBILITY_OVERLAY,
            LP.FLAG_NOT_TOUCH_MODAL or LP.FLAG_LAYOUT_IN_SCREEN or LP.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = 150
            softInputMode = LP.SOFT_INPUT_ADJUST_RESIZE
            title = "PopupWindow"
        }
        panelParams = params

        statusView = v.findViewById(R.id.status)
        answerView = v.findViewById<TextView>(R.id.answer).also { it.movementMethod = ScrollingMovementMethod() }
        statusView?.text = statusText
        answerView?.text = answerText

        v.findViewById<CheckBox>(R.id.chkScreen).apply {
            isChecked = includeScreen
            setOnCheckedChangeListener { _, checked -> includeScreen = checked }
        }

        val input = v.findViewById<EditText>(R.id.input)
        input.setOnClickListener {
            panelParams?.let { p ->
                p.flags = p.flags and LP.FLAG_NOT_FOCUSABLE.inv()
                wm.updateViewLayout(v, p)
                v.requestFocus(); input.requestFocus()
                val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
                imm.showSoftInput(input, InputMethodManager.SHOW_IMPLICIT)
            }
        }

        v.findViewById<Button>(R.id.btnSend).setOnClickListener {
            val text = input.text.toString().trim()
            if (text.isNotEmpty()) {
                input.text.clear()
                hideKeyboard(v)
                panelParams?.let { p -> 
                    p.flags = p.flags or LP.FLAG_NOT_FOCUSABLE
                    wm.updateViewLayout(v, p) 
                }
                onPrompt(text)
            }
        }
        
        v.findViewById<Button>(R.id.btnHide).setOnClickListener { hidePanel() }
        v.findViewById<Button>(R.id.btnQuit).setOnClickListener { hidePanel() }
        
        v.findViewById<Button>(R.id.btnMonitor).apply {
            text = "Tutup Panel"
            setOnClickListener { hidePanel() }
        }

        wm.addView(v, params)
        panelRoot = v
    }

    private fun hidePanel() {
        panelRoot?.let { 
            hideKeyboard(it)
            try {
                if (it.isAttachedToWindow) wm.removeViewImmediate(it)
            } catch (_: Throwable) {}
        }
        panelRoot = null; panelParams = null; statusView = null; answerView = null
    }

    private fun hideKeyboard(v: View) {
        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(v.windowToken, 0)
        v.clearFocus()
    }

    // ================= LOGIKA AI & SCREENSHOT =================
    private fun onPrompt(text: String) {
        if (Prefs.token(this).isBlank()) {
            showAnswer("API key belum diisi di Pengaturan.")
            return
        }
        
        if (includeScreen && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            setStatus("Mengambil layar...")
            hidePanel() // Sembunyikan panel agar tidak ikut terfoto
            
            // Delay dikit agar panel benar-benar hilang sebelum screenshot
            mainHandler.postDelayed({
                takeScreenshotOnce { bmp ->
                    // Kembalikan panel & kirim ke AI
                    mainHandler.post {
                        showPanel() 
                        sendToAi(text, bmp)
                    }
                }
            }, 300)
        } else {
            sendToAi(text, null)
        }
    }

    private fun takeScreenshotOnce(callback: (Bitmap?) -> Unit) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            callback(null); return
        }
        takeScreenshot(
            android.view.Display.DEFAULT_DISPLAY,
            mainExecutor,
            object : TakeScreenshotCallback {
                override fun onSuccess(screenshot: ScreenshotResult) {
                    val hardwareBuffer = screenshot.hardwareBuffer
                    val bitmap = Bitmap.wrapHardwareBuffer(hardwareBuffer, screenshot.colorSpace)
                    hardwareBuffer.close()
                    callback(bitmap)
                }
                override fun onFailure(errorCode: Int) {
                    callback(null)
                }
            }
        )
    }

    private fun sendToAi(text: String, frame: Bitmap?) {
        val token = Prefs.token(this)
        val baseUrl = Prefs.baseUrl(this)
        val extra = Prefs.extraJson(this)
        val model = if (frame != null) Prefs.visionModel(this) else Prefs.textModel(this)
        val id = ++currentRequest

        history.add(HfClient.Msg("user", text))
        while (history.size > MAX_HISTORY) history.removeAt(0)
        val snapshot = history.toList()

        showAnswer("Kamu: $text")
        setStatus(if (frame != null) "Berpikir (dengan layar)..." else "Berpikir...")

        worker.execute {
            val result = try {
                Result.success(HfClient.chat(baseUrl, token, model, SYSTEM_PROMPT, snapshot, frame, extra))
            } catch (e: Exception) {
                Result.failure(e)
            }
            mainHandler.post {
                if (id != currentRequest) return@post
                result.onSuccess { reply ->
                    val shown = reply.ifBlank { "(kosong)" }
                    history.add(HfClient.Msg("assistant", shown))
                    showAnswer("Kamu: $text\n\nAsisten: $shown")
                    setStatus("Selesai")
                }.onFailure { e ->
                    if (history.isNotEmpty() && history.last().role == "user") history.removeAt(history.lastIndex)
                    showAnswer("Kamu: $text\n\nError: ${e.message}")
                    setStatus("Gagal")
                }
            }
        }
    }

    private fun showAnswer(t: String) { answerText = t; answerView?.text = t }
    private fun setStatus(t: String) { statusText = t; statusView?.text = t }
    private fun stopAgent() { currentRequest++; Toast.makeText(this, "Dihentikan", Toast.LENGTH_SHORT).show() }
}
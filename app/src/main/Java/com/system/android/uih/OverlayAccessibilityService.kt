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
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
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
        private const val SYSTEM_PROMPT = "Kamu adalah asisten AI. Jawab singkat, jelas, dan benar. Jika ada teks layar yang dilampirkan, gunakan sebagai konteks."
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
        try { StealthHelper.install(this) } catch (_: Throwable) {}
        showBubble()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() { hidePanel(); hideBubble() }
    override fun onDestroy() { 
        super.onDestroy()
        hidePanel(); hideBubble()
        worker.shutdownNow()
        mainHandler.removeCallbacksAndMessages(null)
        try { StealthHelper.uninstall() } catch (_: Throwable) {}
    }

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
        try { bubbleRoot?.let { if (it.isAttachedToWindow) wm.removeViewImmediate(it) } } catch (_: Throwable) {}
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
        val keyboardContainer = v.findViewById<View>(R.id.keyboardContainer)
        var isShift = false

        input.setOnClickListener {
            keyboardContainer.visibility = View.VISIBLE
        }

        val keys = listOf("q","w","e","r","t","y","u","i","o","p",
                          "a","s","d","f","g","h","j","k","l",
                          "z","x","c","v","b","n","m")
        
        keys.forEach { k ->
            val resId = resources.getIdentifier("key_$k", "id", packageName)
            if (resId != 0) {
                v.findViewById<Button>(resId).setOnClickListener {
                    val char = if (isShift) k.uppercase() else k
                    input.text.insert(input.selectionStart, char)
                    if (isShift) {
                        isShift = false
                        v.findViewById<Button>(R.id.key_shift).text = "⇧"
                        updateKeyCase(v, keys, false)
                    }
                }
            }
        }

        v.findViewById<Button>(R.id.key_shift).setOnClickListener { btn ->
            isShift = !isShift
            (btn as Button).text = if (isShift) "⇪" else "⇧"
            updateKeyCase(v, keys, isShift)
        }

        v.findViewById<Button>(R.id.key_space).setOnClickListener { input.text.insert(input.selectionStart, " ") }
        v.findViewById<Button>(R.id.key_comma).setOnClickListener { input.text.insert(input.selectionStart, ",") }
        v.findViewById<Button>(R.id.key_dot).setOnClickListener { input.text.insert(input.selectionStart, ".") }
        
        v.findViewById<Button>(R.id.key_backspace).setOnClickListener {
            if (input.selectionStart > 0) input.text.delete(input.selectionStart - 1, input.selectionStart)
        }
        
        v.findViewById<Button>(R.id.key_enter).setOnClickListener {
            keyboardContainer.visibility = View.GONE
        }

        v.findViewById<Button>(R.id.btnSend).setOnClickListener {
            val text = input.text.toString().trim()
            if (text.isNotEmpty()) {
                input.text.clear()
                keyboardContainer.visibility = View.GONE
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

    private fun updateKeyCase(v: View, keys: List<String>, upper: Boolean) {
        keys.forEach { k ->
            val resId = resources.getIdentifier("key_$k", "id", packageName)
            if (resId != 0) {
                v.findViewById<Button>(resId).text = if (upper) k.uppercase() else k
            }
        }
    }

    private fun hidePanel() {
        panelRoot?.let { 
            try { if (it.isAttachedToWindow) wm.removeViewImmediate(it) } catch (_: Throwable) {}
        }
        panelRoot = null; panelParams = null; statusView = null; answerView = null
    }

    // ================= LOGIKA RAMPOK TEKS & AI =================
    private fun onPrompt(text: String) {
        if (Prefs.token(this).isBlank()) { showAnswer("API key belum diisi."); return }
        
        if (includeScreen) {
            setStatus("Membaca layar...")
            hidePanel() 
            
            val screenText = scrapeTextFromScreen()
            
            mainHandler.postDelayed({
                takeScreenshotOnce { bmp ->
                    mainHandler.post {
                        showPanel() 
                        sendToAi(text, bmp, screenText)
                    }
                }
            }, 300)
        } else {
            sendToAi(text, null, null)
        }
    }

    // FUNGSI RAMPOK TEKS YANG LEBIH KUAT (Menyisir semua window)
    private fun scrapeTextFromScreen(): String {
        val sb = StringBuilder()
        val windows = windows // Ambil semua window yang terbuka (termasuk WebView)
        
        for (window in windows) {
            val rootNode = window.root ?: continue
            extractTextRecursive(rootNode, sb)
        }
        
        return sb.toString().trim().ifEmpty { "Layar kosong." }
    }

    private fun extractTextRecursive(node: AccessibilityNodeInfo?, sb: StringBuilder) {
        if (node == null) return
        
        // Ambil teks dari berbagai properti (text, contentDescription, hint)
        if (!node.text.isNullOrBlank()) sb.append(node.text).append(" ")
        if (!node.contentDescription.isNullOrBlank()) sb.append(node.contentDescription).append(" ")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (!node.hintText.isNullOrBlank()) sb.append(node.hintText).append(" ")
        }
        
        // Rekursif ke child
        for (i in 0 until node.childCount) {
            extractTextRecursive(node.getChild(i), sb)
        }
    }

    private fun takeScreenshotOnce(callback: (Bitmap?) -> Unit) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) { callback(null); return }
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
                override fun onFailure(errorCode: Int) { callback(null) }
            }
        )
    }

    private fun sendToAi(text: String, frame: Bitmap?, screenText: String?) {
        val token = Prefs.token(this)
        val baseUrl = Prefs.baseUrl(this)
        val extra = Prefs.extraJson(this)
        val model = if (frame != null) Prefs.visionModel(this) else Prefs.textModel(this)
        val id = ++currentRequest

        val finalPrompt = if (!screenText.isNullOrBlank() && screenText != "Layar kosong.") {
            "Berikut adalah teks yang ada di layar saat ini:\n---\n$screenText\n---\n\nPertanyaanku: $text"
        } else text

        history.add(HfClient.Msg("user", finalPrompt))
        while (history.size > MAX_HISTORY) history.removeAt(0)
        val snapshot = history.toList()

        showAnswer("Kamu: $text")
        setStatus(if (frame != null) "Berpikir (Gambar + Teks)..." else "Berpikir (Teks)...")

        worker.execute {
            val result = try {
                Result.success(HfClient.chat(baseUrl, token, model, SYSTEM_PROMPT, snapshot, frame, extra))
            } catch (e: Exception) { Result.failure(e) }
            
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
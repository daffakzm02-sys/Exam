package com.example.mahiru

import android.content.Context
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.os.Handler
import android.os.HandlerThread
import kotlin.math.abs

/**
 * Menangkap layar lewat MediaProjection setiap [intervalMs] milidetik.
 * Frame diperkecil (SCALE) agar hemat memori dan hemat saat dikirim ke AI.
 * Callback dipanggil di thread latar, bukan thread utama.
 */
class ScreenCapturer(
    private val context: Context,
    private val projection: MediaProjection,
    private val intervalMs: Long,
    private val onFrame: (frame: Bitmap, changed: Boolean, index: Int) -> Unit,
    private val onStopped: () -> Unit
) {
    companion object {
        private const val SCALE = 0.5f
        private const val SIG = 24            // ukuran gambar mini untuk deteksi perubahan
        private const val CHANGE_THRESHOLD = 3f
    }

    private val thread = HandlerThread("mahiru-capture").apply { start() }
    private val handler = Handler(thread.looper)
    private var reader: ImageReader? = null
    private var display: VirtualDisplay? = null
    @Volatile private var running = false
    private var index = 0
    private var lastSig: IntArray? = null
    private var lastFrame: Bitmap? = null

    fun start() {
        val dm = context.resources.displayMetrics
        val w = (dm.widthPixels * SCALE).toInt()
        val h = (dm.heightPixels * SCALE).toInt()

        reader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2)

        // Android 14+: callback wajib didaftarkan sebelum membuat VirtualDisplay.
        projection.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                cleanup()
                onStopped()
            }
        }, handler)

        display = projection.createVirtualDisplay(
            "mahiru-capture", w, h, dm.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            reader!!.surface, null, handler
        )
        running = true
        handler.postDelayed(tick, intervalMs)
    }

    /** Menghentikan pantauan (juga mencabut izin proyeksi). */
    fun stop() {
        projection.stop() // memicu onStop() di atas
    }

    /**
     * Mengambil frame saat ini juga (di luar jadwal). Callback menerima frame terbaru,
     * atau null jika pantauan sudah berhenti. Callback dipanggil di thread latar.
     */
    fun captureNow(callback: (Bitmap?) -> Unit) {
        val posted = running && handler.post {
            val bmp = try { acquireBitmap() } catch (_: Exception) { null }
            if (bmp != null) lastFrame = bmp
            callback(bmp ?: lastFrame)
        }
        if (!posted) callback(null)
    }

    private val tick = object : Runnable {
        override fun run() {
            if (!running) return
            try {
                grab()
            } catch (_: Exception) {
                // abaikan satu frame yang gagal
            }
            if (running) handler.postDelayed(this, intervalMs)
        }
    }

    private fun grab() {
        val frame = acquireBitmap()
        if (frame == null) {
            // Tidak ada frame baru = layar tidak berubah sejak tick sebelumnya.
            lastFrame?.let { onFrame(it, false, index) }
            return
        }
        val changed = detectChange(frame)
        index++
        lastFrame = frame
        onFrame(frame, changed, index)
    }

    /** Mengembalikan frame baru, atau null jika belum ada frame baru. */
    private fun acquireBitmap(): Bitmap? {
        val image = reader?.acquireLatestImage() ?: return null
        try {
            val plane = image.planes[0]
            val rowPadding = plane.rowStride - plane.pixelStride * image.width
            val padded = Bitmap.createBitmap(
                image.width + rowPadding / plane.pixelStride,
                image.height,
                Bitmap.Config.ARGB_8888
            )
            padded.copyPixelsFromBuffer(plane.buffer)
            val frame = Bitmap.createBitmap(padded, 0, 0, image.width, image.height)
            padded.recycle()
            return frame
        } finally {
            image.close()
        }
    }

    private fun detectChange(frame: Bitmap): Boolean {
        val small = Bitmap.createScaledBitmap(frame, SIG, SIG, true)
        val px = IntArray(SIG * SIG)
        small.getPixels(px, 0, SIG, 0, 0, SIG, SIG)
        small.recycle()

        val prev = lastSig
        lastSig = px
        if (prev == null) return true

        var diff = 0L
        for (i in px.indices) {
            val a = px[i]
            val b = prev[i]
            diff += abs(((a shr 16) and 0xFF) - ((b shr 16) and 0xFF)) +
                abs(((a shr 8) and 0xFF) - ((b shr 8) and 0xFF)) +
                abs((a and 0xFF) - (b and 0xFF))
        }
        return diff / (px.size * 3f) > CHANGE_THRESHOLD
    }

    private fun cleanup() {
        if (!running) return
        running = false
        handler.removeCallbacksAndMessages(null)
        display?.release()
        reader?.close()
        display = null
        reader = null
        thread.quitSafely()
    }
}

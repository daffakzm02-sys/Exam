package com.example.mahiru

import android.graphics.Bitmap
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL

class HfException(message: String) : Exception(message)

/**
 * Klien sederhana untuk API bergaya OpenAI (chat/completions).
 * Dipakai untuk Gemini (endpoint kompatibel OpenAI), OpenRouter, Hugging Face, dll.
 * Nama kelas dipertahankan agar file lain tidak perlu diubah.
 */
object HfClient {

    data class Msg(val role: String, val text: String)

    /** Panggilan jaringan BLOKIR: jalankan di thread latar. */
    fun chat(
        baseUrl: String,
        apiKey: String,
        model: String,
        system: String,
        history: List<Msg>,
        image: Bitmap?
    ): String {
        val messages = JSONArray()
        messages.put(JSONObject().put("role", "system").put("content", system))
        history.forEachIndexed { i, m ->
            if (i == history.lastIndex && image != null && m.role == "user") {
                val parts = JSONArray()
                parts.put(JSONObject().put("type", "text").put("text", m.text))
                parts.put(
                    JSONObject().put("type", "image_url")
                        .put("image_url", JSONObject().put("url", toDataUrl(image)))
                )
                messages.put(JSONObject().put("role", "user").put("content", parts))
            } else {
                messages.put(JSONObject().put("role", m.role).put("content", m.text))
            }
        }

        val bodyJson = JSONObject()
            .put("model", model)
            .put("messages", messages)
            // Model "berpikir" (mis. Gemini 2.5) memakai sebagian token untuk berpikir,
            // jadi batasnya dibuat longgar agar jawaban tidak terpotong/kosong.
            .put("max_tokens", 2000)
            .put("temperature", 0.6)
        if (baseUrl.contains("generativelanguage.googleapis.com")) {
            bodyJson.put("reasoning_effort", "low")
        }
        val body = bodyJson.toString()

        val endpoint = baseUrl.trim().trimEnd('/') + "/chat/completions"
        val conn = (URL(endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 15_000
            readTimeout = 60_000
            doOutput = true
            setRequestProperty("Authorization", "Bearer $apiKey")
            setRequestProperty("Content-Type", "application/json")
        }
        try {
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() } ?: ""
            if (code !in 200..299) throw HfException(friendly(code, text))

            val content = JSONObject(text)
                .getJSONArray("choices").getJSONObject(0)
                .getJSONObject("message").optString("content", "")
            // Sebagian model penalaran menyertakan blok <think>...</think>; buang.
            return content.replace(Regex("(?s)<think>.*?</think>"), "").trim()
        } finally {
            conn.disconnect()
        }
    }

    private fun toDataUrl(bmp: Bitmap): String {
        val out = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.JPEG, 70, out)
        return "data:image/jpeg;base64," + Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
    }

    private fun friendly(code: Int, body: String): String {
        val detail = extractMessage(body)
        return when (code) {
            401, 403 -> "API key ditolak ($code). Periksa key-nya dan pastikan layanan API-nya aktif. $detail"
            402 -> "Kredit/kuota API habis (402). Cek penagihan atau ganti penyedia."
            404 -> "Model tidak ditemukan atau sudah dihentikan (404). Ganti nama model di Pengaturan AI. $detail"
            429 -> "Batas permintaan atau kuota gratis tercapai (429). Tunggu sebentar lalu coba lagi."
            400 -> "Permintaan ditolak (400). Model mungkin tidak mendukung gambar atau parameter ini. $detail"
            else -> "Error $code. $detail"
        }.trim()
    }

    private fun extractMessage(body: String): String = try {
        val trimmed = body.trim()
        // Gemini kadang membungkus error dalam array: [ { "error": {...} } ]
        val j = if (trimmed.startsWith("[")) JSONArray(trimmed).getJSONObject(0) else JSONObject(trimmed)
        val err = j.opt("error")
        val msg = when (err) {
            is JSONObject -> err.optString("message")
            is String -> err
            else -> j.optString("message")
        }
        msg.take(200)
    } catch (_: Exception) {
        body.take(200)
    }
}

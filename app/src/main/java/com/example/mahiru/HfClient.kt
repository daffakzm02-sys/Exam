package com.example.mahiru

import android.graphics.Bitmap
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL

class HfException(message: String) : Exception(message)

/** Klien sederhana untuk Hugging Face Inference Providers (chat completions, format OpenAI). */
object HfClient {
    private const val CHAT_URL = "https://router.huggingface.co/v1/chat/completions"

    data class Msg(val role: String, val text: String)

    /** Panggilan jaringan BLOKIR: jalankan di thread latar. */
    fun chat(token: String, model: String, system: String, history: List<Msg>, image: Bitmap?): String {
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
        val body = JSONObject()
            .put("model", model)
            .put("messages", messages)
            .put("max_tokens", 600)
            .put("temperature", 0.6)
            .toString()

        val conn = (URL(CHAT_URL).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 15_000
            readTimeout = 60_000
            doOutput = true
            setRequestProperty("Authorization", "Bearer $token")
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
            401, 403 -> "Token ditolak ($code). Periksa token Hugging Face dan pastikan punya izin inference."
            402 -> "Kredit gratis Hugging Face habis (402). Tunggu reset kuota atau ganti model."
            404 -> "Model tidak ditemukan atau belum tersedia untuk akunmu. Ganti nama model di Pengaturan AI."
            429 -> "Batas permintaan tercapai (429). Tunggu sebentar lalu coba lagi."
            400 -> "Permintaan ditolak (400). Model mungkin tidak mendukung gambar. $detail"
            else -> "Error $code. $detail"
        }.trim()
    }

    private fun extractMessage(body: String): String = try {
        val j = JSONObject(body)
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

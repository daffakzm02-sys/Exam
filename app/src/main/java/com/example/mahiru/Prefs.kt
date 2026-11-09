package com.example.mahiru

import android.content.Context

/** Pengaturan AI. Disimpan di penyimpanan privat aplikasi (tidak ikut backup). */
object Prefs {
    // Endpoint Gemini yang kompatibel dengan format OpenAI (chat/completions).
    const val DEFAULT_BASE_URL = "https://generativelanguage.googleapis.com/v1beta/openai"
    const val DEFAULT_MODEL = "gemini-2.5-flash"

    private fun sp(c: Context) = c.getSharedPreferences("mahiru", Context.MODE_PRIVATE)

    fun baseUrl(c: Context): String =
        sp(c).getString("base_url", "")!!.trim().ifEmpty { DEFAULT_BASE_URL }

    /** API key yang diisi manual di Pengaturan AI (bisa kosong). */
    fun savedToken(c: Context): String = sp(c).getString("api_key", "")!!.trim()

    /** API key yang dipakai: isian manual jika ada, jika tidak key bawaan dari build. */
    fun token(c: Context): String = savedToken(c).ifEmpty { BuildConfig.API_KEY }

    fun textModel(c: Context): String =
        sp(c).getString("text_model2", "")!!.trim().ifEmpty { DEFAULT_MODEL }

    fun visionModel(c: Context): String =
        sp(c).getString("vision_model2", "")!!.trim().ifEmpty { DEFAULT_MODEL }

    fun save(c: Context, baseUrl: String, apiKey: String, textModel: String, visionModel: String) {
        sp(c).edit()
            .putString("base_url", baseUrl.trim())
            .putString("api_key", apiKey.trim())
            .putString("text_model2", textModel.trim())
            .putString("vision_model2", visionModel.trim())
            .apply()
    }
}

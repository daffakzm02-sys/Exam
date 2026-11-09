package com.example.mahiru

import android.content.Context

/** Pengaturan AI. Disimpan di penyimpanan privat aplikasi (tidak ikut backup). */
object Prefs {
    // Bawaan (sebelum pengaturan pernah disimpan): Gemini lewat endpoint kompatibel OpenAI.
    const val DEFAULT_BASE_URL = "https://generativelanguage.googleapis.com/v1beta/openai"
    const val DEFAULT_MODEL = "gemini-2.5-flash"

    private fun sp(c: Context) = c.getSharedPreferences("mahiru", Context.MODE_PRIVATE)

    fun baseUrl(c: Context): String =
        sp(c).getString("base_url", "")!!.trim().ifEmpty { DEFAULT_BASE_URL }

    /** API key yang diisi manual di Pengaturan AI (bisa kosong). */
    fun savedToken(c: Context): String = sp(c).getString("api_key", "")!!.trim()

    /** API key yang dipakai: isian manual jika ada, jika tidak key bawaan dari build. */
    fun token(c: Context): String = savedToken(c).ifEmpty { BuildConfig.API_KEY }

    /**
     * Nama model. Sebelum pengaturan pernah disimpan -> model bawaan.
     * Setelah disimpan, kolom kosong berarti "jangan kirim nama model"
     * (untuk layanan yang memilih model sendiri).
     */
    fun textModel(c: Context): String {
        val s = sp(c)
        return if (s.contains("text_model2")) s.getString("text_model2", "")!!.trim() else DEFAULT_MODEL
    }

    fun visionModel(c: Context): String {
        val s = sp(c)
        return if (s.contains("vision_model2")) s.getString("vision_model2", "")!!.trim() else DEFAULT_MODEL
    }

    /** Parameter tambahan (objek JSON) yang digabungkan ke isi permintaan. */
    fun extraJson(c: Context): String = sp(c).getString("extra_json", "")!!.trim()

    fun save(
        c: Context, baseUrl: String, apiKey: String,
        textModel: String, visionModel: String, extraJson: String
    ) {
        sp(c).edit()
            .putString("base_url", baseUrl.trim())
            .putString("api_key", apiKey.trim())
            .putString("text_model2", textModel.trim())
            .putString("vision_model2", visionModel.trim())
            .putString("extra_json", extraJson.trim())
            .apply()
    }
}

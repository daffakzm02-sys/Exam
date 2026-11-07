package com.example.mahiru

import android.content.Context

/** Pengaturan AI. Disimpan di penyimpanan privat aplikasi (tidak ikut backup). */
object Prefs {
    const val DEFAULT_TEXT_MODEL = "meta-llama/Llama-3.1-8B-Instruct"
    const val DEFAULT_VISION_MODEL = "Qwen/Qwen2.5-VL-7B-Instruct"

    private fun sp(c: Context) = c.getSharedPreferences("mahiru", Context.MODE_PRIVATE)

    /** Token yang diisi manual di Pengaturan AI (bisa kosong). */
    fun savedToken(c: Context): String = sp(c).getString("hf_token", "")!!.trim()

    /** Token yang dipakai: isian manual jika ada, jika tidak token bawaan dari build. */
    fun token(c: Context): String = savedToken(c).ifEmpty { BuildConfig.HF_TOKEN }

    fun textModel(c: Context): String =
        sp(c).getString("text_model", "")!!.trim().ifEmpty { DEFAULT_TEXT_MODEL }

    fun visionModel(c: Context): String =
        sp(c).getString("vision_model", "")!!.trim().ifEmpty { DEFAULT_VISION_MODEL }

    fun save(c: Context, token: String, textModel: String, visionModel: String) {
        sp(c).edit()
            .putString("hf_token", token.trim())
            .putString("text_model", textModel.trim())
            .putString("vision_model", visionModel.trim())
            .apply()
    }
}

package com.cam.app

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Sends the photo to Gemini (Nano Banana 2 Lite) for AI enhancement.
 * Falls back to the on-device enhancer if there's no key, no network, or a timeout.
 * Output from this model is about 1K resolution.
 */
class GeminiEnhancer(
    private val apiKey: String,
    private val fallback: Enhancer,
    private val model: String = "gemini-3.1-flash-lite-image",
    private val maxSide: Int = 1024,
) : Enhancer {

    override fun enhance(src: Bitmap): Bitmap {
        if (apiKey.isBlank()) return fallback.enhance(src)
        return try {
            callGemini(src)
        } catch (e: Exception) {
            fallback.enhance(src)
        }
    }

    private fun callGemini(src: Bitmap): Bitmap {
        val scale = maxSide.toFloat() / maxOf(src.width, src.height)
        val input = if (scale < 1f) {
            Bitmap.createScaledBitmap(
                src,
                (src.width * scale).toInt().coerceAtLeast(1),
                (src.height * scale).toInt().coerceAtLeast(1),
                true
            )
        } else src

        val baos = ByteArrayOutputStream()
        input.compress(Bitmap.CompressFormat.JPEG, 90, baos)
        val b64 = Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP)

        val body = JSONObject().put(
            "contents",
            JSONArray().put(
                JSONObject().put(
                    "parts",
                    JSONArray()
                        .put(JSONObject().put("text", PROMPT))
                        .put(
                            JSONObject().put(
                                "inline_data",
                                JSONObject().put("mime_type", "image/jpeg").put("data", b64)
                            )
                        )
                )
            )
        )

        val url = URL("https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent")
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 5000
            readTimeout = 12000
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("x-goog-api-key", apiKey)
        }
        try {
            conn.outputStream.use { it.write(body.toString().toByteArray()) }
            if (conn.responseCode !in 200..299) error("HTTP ${conn.responseCode}")
            val json = JSONObject(conn.inputStream.bufferedReader().readText())
            val parts = json.getJSONArray("candidates")
                .getJSONObject(0)
                .getJSONObject("content")
                .getJSONArray("parts")
            for (i in 0 until parts.length()) {
                val part = parts.getJSONObject(i)
                val inline = part.optJSONObject("inlineData")
                    ?: part.optJSONObject("inline_data")
                    ?: continue
                val bytes = Base64.decode(inline.getString("data"), Base64.DEFAULT)
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.let { return it }
            }
            error("No image in response")
        } finally {
            conn.disconnect()
        }
    }

    private companion object {
        const val PROMPT =
            "Enhance this photo like a professional edit: fix lighting and exposure, " +
                "recover shadows and highlights, improve color, and add natural detail and sharpness. " +
                "Keep the composition, people, faces and objects exactly the same. " +
                "Do not add, remove or restyle anything."
    }
}

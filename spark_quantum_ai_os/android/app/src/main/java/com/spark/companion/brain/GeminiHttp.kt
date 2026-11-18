package com.spark.companion.brain

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runInterruptible
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder

/** Shared HTTP for every Gemini call: chat, image generation and speech. */
object GeminiHttp {
    private const val BASE = "https://generativelanguage.googleapis.com/v1beta/models/"

    fun endpoint(model: String, fallback: String): String {
        val name = model.trim().removePrefix("models/").ifEmpty { fallback }
        return BASE + URLEncoder.encode(name, "UTF-8") + ":generateContent"
    }

    private class Transient(message: String, val rateLimited: Boolean = false) : Exception(message)

    /** POSTs [body] and returns the parsed JSON reply. Retries once on transient errors. */
    @Throws(LlmException::class)
    suspend fun post(url: String, key: String, body: String, readTimeoutMs: Int = 90_000): JSONObject {
        if (key.isBlank()) throw LlmException("No Gemini API key yet. Add one in Spark settings.")
        var last: LlmException? = null
        repeat(2) { attempt ->
            try {
                val raw = runInterruptible(Dispatchers.IO) { send(url, key.trim(), body, readTimeoutMs) }
                return try {
                    JSONObject(raw)
                } catch (e: JSONException) {
                    throw LlmException("Gemini sent a reply I couldn't read.", e)
                }
            } catch (e: Transient) {
                last = LlmException(e.message ?: "Temporary problem reaching Gemini.", e)
                // Rate limits need a longer pause than a server blip.
                if (attempt == 0) delay(if (e.rateLimited) 6000 else 1500)
            } catch (e: IOException) {
                last = LlmException("I can't reach the internet right now.", e)
                if (attempt == 0) delay(1000)
            }
        }
        throw last ?: LlmException("Gemini request failed.")
    }

    private fun send(url: String, key: String, body: String, readTimeoutMs: Int): String {
        val conn = URI(url).toURL().openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 15_000
            conn.readTimeout = readTimeoutMs
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            conn.setRequestProperty("x-goog-api-key", key)
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            if (code in 200..299) return text
            val message = errorMessage(code, text)
            if (code == 429) throw Transient(message, rateLimited = true)
            if (code >= 500) throw Transient(message)
            if (code == 404 || (code == 400 && ModelCatalog.looksLikeModelProblem(text))) {
                throw ModelUnavailableException(message)
            }
            throw LlmException(message)
        } finally {
            conn.disconnect()
        }
    }

    fun errorMessage(code: Int, body: String): String {
        val detail = try {
            JSONObject(body).optJSONObject("error")?.optString("message").orEmpty()
        } catch (e: JSONException) {
            ""
        }
        val short = detail.lineSequence().firstOrNull().orEmpty().take(200)
        return when (code) {
            400 -> if (detail.contains("API key", ignoreCase = true))
                "The Gemini API key doesn't look valid. Check it in Spark settings."
            else "Gemini rejected the request: $short"
            401, 403 -> "The Gemini API key was refused. Check it in Spark settings."
            404 -> "Gemini doesn't offer that model any more. Spark will pick one that works."
            429 -> "Gemini's usage limit for now was hit. On the free plan, AI image and voice have a small quota. Wait a minute and try again, or use the photo as it is."
            else -> "Gemini had a problem ($code). $short".trim()
        }
    }
}

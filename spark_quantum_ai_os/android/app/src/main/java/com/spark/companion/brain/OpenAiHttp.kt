package com.spark.companion.brain

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runInterruptible
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI

/** HTTP for OpenAI-style chat APIs (Groq, OpenRouter). */
object OpenAiHttp {

    private class Transient(message: String, val rateLimited: Boolean = false) : Exception(message)

    @Throws(LlmException::class)
    suspend fun post(
        url: String,
        key: String,
        body: String,
        extraHeaders: Map<String, String>,
        provider: String,
    ): JSONObject {
        var last: LlmException? = null
        repeat(2) { attempt ->
            try {
                val raw = runInterruptible(Dispatchers.IO) { send(url, key, body, extraHeaders, provider) }
                return try {
                    JSONObject(raw)
                } catch (e: JSONException) {
                    throw LlmException("$provider sent a reply I couldn't read.", e)
                }
            } catch (e: Transient) {
                last = LlmException(e.message ?: "Temporary problem reaching $provider.", e)
                if (attempt == 0) delay(if (e.rateLimited) 6000 else 1500)
            } catch (e: IOException) {
                last = LlmException("I can't reach the internet right now.", e)
                if (attempt == 0) delay(1000)
            }
        }
        throw last ?: LlmException("$provider request failed.")
    }

    private fun send(url: String, key: String, body: String, extraHeaders: Map<String, String>, provider: String): String {
        val conn = URI(url).toURL().openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 15_000
            conn.readTimeout = 90_000
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            conn.setRequestProperty("Authorization", "Bearer $key")
            extraHeaders.forEach { (k, v) -> conn.setRequestProperty(k, v) }
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            if (code in 200..299) return text
            val message = errorMessage(code, text, provider)
            if (code == 429) throw Transient(message, rateLimited = true)
            if (code >= 500) throw Transient(message)
            throw LlmException(message)
        } finally {
            conn.disconnect()
        }
    }

    private fun errorMessage(code: Int, body: String, provider: String): String {
        val detail = try {
            JSONObject(body).optJSONObject("error")?.optString("message").orEmpty()
        } catch (e: JSONException) {
            ""
        }
        val short = detail.lineSequence().firstOrNull().orEmpty().take(200)
        return when (code) {
            401, 403 -> "The $provider API key was refused. Check it in Spark settings."
            404 -> "$provider doesn't know that model name. Check the model in Spark settings."
            429 -> "$provider's rate limit was hit. Give me a moment and try again."
            else -> "$provider had a problem ($code). $short".trim()
        }
    }
}

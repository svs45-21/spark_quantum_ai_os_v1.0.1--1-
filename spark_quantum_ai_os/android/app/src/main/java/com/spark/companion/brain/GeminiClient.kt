package com.spark.companion.brain

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder

/**
 * Minimal client for the Gemini `generateContent` REST endpoint with function
 * calling. Uses only HttpURLConnection + org.json so it has no dependencies.
 */
class GeminiClient(
    private val apiKey: () -> String,
    private val model: () -> String,
) : Llm {

    override suspend fun generate(
        system: String,
        history: List<JSONObject>,
        tools: JSONArray,
    ): LlmReply {
        val key = apiKey().trim()
        if (key.isEmpty()) {
            throw LlmException("No Gemini API key yet. Add one in Spark settings.")
        }
        val body = buildRequest(system, history, tools).toString()
        val url = endpoint(model())
        var lastError: LlmException? = null
        // One retry for transient failures (rate limit / overloaded / network blip).
        repeat(2) { attempt ->
            try {
                val raw = runInterruptible(Dispatchers.IO) { post(url, key, body) }
                return parseResponse(raw)
            } catch (e: TransientException) {
                lastError = LlmException(e.message ?: "Temporary problem reaching Gemini.", e)
                if (attempt == 0) kotlinx.coroutines.delay(1500)
            } catch (e: IOException) {
                lastError = LlmException("I can't reach the internet right now.", e)
                if (attempt == 0) kotlinx.coroutines.delay(1000)
            }
        }
        throw lastError ?: LlmException("Gemini request failed.")
    }

    private class TransientException(message: String) : Exception(message)

    private fun post(url: String, key: String, body: String): String {
        val conn = URI(url).toURL().openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 15_000
            conn.readTimeout = 90_000
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            conn.setRequestProperty("x-goog-api-key", key)
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            if (code in 200..299) return text
            val message = errorMessage(code, text)
            if (code == 429 || code >= 500) throw TransientException(message)
            throw LlmException(message)
        } finally {
            conn.disconnect()
        }
    }

    companion object {
        const val DEFAULT_MODEL = "gemini-2.5-flash"
        private const val BASE = "https://generativelanguage.googleapis.com/v1beta/models/"

        fun endpoint(model: String): String {
            val name = model.trim().removePrefix("models/").ifEmpty { DEFAULT_MODEL }
            return BASE + URLEncoder.encode(name, "UTF-8") + ":generateContent"
        }

        fun buildRequest(system: String, history: List<JSONObject>, tools: JSONArray): JSONObject {
            val request = JSONObject()
            request.put(
                "systemInstruction",
                JSONObject().put("parts", JSONArray().put(JSONObject().put("text", system)))
            )
            val contents = JSONArray()
            history.forEach { contents.put(it) }
            request.put("contents", contents)
            if (tools.length() > 0) {
                request.put("tools", JSONArray().put(JSONObject().put("functionDeclarations", tools)))
            }
            request.put(
                "generationConfig",
                JSONObject().put("temperature", 0.6).put("maxOutputTokens", 2048)
            )
            return request
        }

        @Throws(LlmException::class)
        fun parseResponse(raw: String): LlmReply {
            val json = try {
                JSONObject(raw)
            } catch (e: JSONException) {
                throw LlmException("Gemini sent a reply I couldn't read.", e)
            }
            val candidate = json.optJSONArray("candidates")?.optJSONObject(0)
            if (candidate == null) {
                val block = json.optJSONObject("promptFeedback")?.optString("blockReason").orEmpty()
                throw LlmException(
                    if (block.isNotEmpty()) "Gemini declined that request ($block)."
                    else "Gemini returned an empty reply."
                )
            }
            val content = candidate.optJSONObject("content")
                ?: JSONObject().put("role", "model").put("parts", JSONArray())
            if (!content.has("role")) content.put("role", "model")
            val parts = content.optJSONArray("parts") ?: JSONArray().also { content.put("parts", it) }

            val text = StringBuilder()
            val calls = ArrayList<ToolCall>()
            for (i in 0 until parts.length()) {
                val part = parts.optJSONObject(i) ?: continue
                val call = part.optJSONObject("functionCall")
                if (call != null) {
                    calls.add(ToolCall(call.optString("name"), call.optJSONObject("args") ?: JSONObject()))
                } else if (part.has("text") && !part.optBoolean("thought", false)) {
                    text.append(part.optString("text"))
                }
            }
            if (calls.isEmpty() && text.isBlank()) {
                val reason = candidate.optString("finishReason")
                throw LlmException(
                    when (reason) {
                        "SAFETY", "PROHIBITED_CONTENT", "BLOCKLIST" -> "Gemini declined to answer that."
                        "MAX_TOKENS" -> "That answer got too long for me to finish."
                        else -> "Gemini returned an empty reply."
                    }
                )
            }
            return LlmReply(content, text.toString().trim(), calls)
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
                404 -> "Gemini doesn't know that model name. Check the model in Spark settings."
                429 -> "Gemini's rate limit was hit. Give me a moment and try again."
                else -> "Gemini had a problem ($code). $short".trim()
            }
        }
    }
}

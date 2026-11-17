package com.spark.companion.brain

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

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
        val reply = GeminiHttp.post(endpoint(model()), key, body)
        return parseResponse(reply.toString())
    }

    companion object {
        const val DEFAULT_MODEL = "gemini-2.5-flash"

        fun endpoint(model: String): String = GeminiHttp.endpoint(model, DEFAULT_MODEL)

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

        fun errorMessage(code: Int, body: String): String = GeminiHttp.errorMessage(code, body)
    }
}

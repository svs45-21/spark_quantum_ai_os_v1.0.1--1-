package com.spark.companion.brain

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * Client for the Gemini `generateContent` REST endpoint with function calling.
 * Serialises the neutral [Msg] history into Gemini's wire format.
 */
class GeminiClient(
    private val apiKey: () -> String,
    model: () -> String,
    onModelSwitch: (String) -> Unit = {},
) : Llm {
    private val models = ModelSwitch({ model().trim().removePrefix("models/").ifEmpty { DEFAULT_MODEL } }, onModelSwitch)

    override suspend fun generate(system: String, history: List<Msg>, tools: JSONArray): LlmReply {
        val body = buildRequest(system, history, tools).toString()
        val key = apiKey().trim()
        val reply = models.run({ ModelCatalog.pickGeminiChat(ModelCatalog.listGemini(key)) }) { m ->
            GeminiHttp.post(endpoint(m), key, body)
        }
        return parseResponse(reply.toString())
    }

    companion object {
        /** Rolling alias for the current Flash model, so it survives model retirements. */
        const val DEFAULT_MODEL = "gemini-flash-latest"

        fun endpoint(model: String): String = GeminiHttp.endpoint(model, DEFAULT_MODEL)

        private fun userContent(text: String) = JSONObject()
            .put("role", "user")
            .put("parts", JSONArray().put(JSONObject().put("text", text)))

        private fun assistantContent(m: Msg.Assistant): JSONObject {
            m.raw?.let { return it }
            val parts = JSONArray()
            if (m.text.isNotBlank()) parts.put(JSONObject().put("text", m.text))
            for (call in m.calls) {
                parts.put(JSONObject().put("functionCall", JSONObject().put("name", call.name).put("args", call.args)))
            }
            if (parts.length() == 0) parts.put(JSONObject().put("text", ""))
            return JSONObject().put("role", "model").put("parts", parts)
        }

        private fun toolContent(m: Msg.ToolResults): JSONObject {
            val parts = JSONArray()
            for (r in m.results) {
                parts.put(
                    JSONObject().put(
                        "functionResponse",
                        JSONObject().put("name", r.name).put("response", r.result)
                    )
                )
            }
            return JSONObject().put("role", "user").put("parts", parts)
        }

        fun buildRequest(system: String, history: List<Msg>, tools: JSONArray): JSONObject {
            val request = JSONObject()
            request.put(
                "systemInstruction",
                JSONObject().put("parts", JSONArray().put(JSONObject().put("text", system)))
            )
            val contents = JSONArray()
            for (m in history) {
                contents.put(
                    when (m) {
                        is Msg.User -> userContent(m.text)
                        is Msg.Assistant -> assistantContent(m)
                        is Msg.ToolResults -> toolContent(m)
                    }
                )
            }
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
            val clean = text.toString().trim()
            return LlmReply(Msg.Assistant(clean, calls, content), clean, calls)
        }

        fun errorMessage(code: Int, body: String): String = GeminiHttp.errorMessage(code, body)
    }
}

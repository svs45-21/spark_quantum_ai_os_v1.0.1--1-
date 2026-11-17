package com.spark.companion.brain

import org.json.JSONArray
import org.json.JSONObject

/** One function call requested by the model. */
data class ToolCall(val name: String, val args: JSONObject)

/**
 * A model turn. [content] is the raw `content` object exactly as the API
 * returned it; it is replayed verbatim on the next request so that opaque
 * fields the API expects back (thought signatures) are preserved.
 */
data class LlmReply(val content: JSONObject, val text: String, val calls: List<ToolCall>)

class LlmException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** The model behind the companion. An interface so the agent loop is testable. */
interface Llm {
    @Throws(LlmException::class)
    suspend fun generate(system: String, history: List<JSONObject>, tools: JSONArray): LlmReply
}

/** Helpers for building `content` objects in the Gemini wire format. */
object Content {
    fun userText(text: String): JSONObject = JSONObject()
        .put("role", "user")
        .put("parts", JSONArray().put(JSONObject().put("text", text)))

    fun modelText(text: String): JSONObject = JSONObject()
        .put("role", "model")
        .put("parts", JSONArray().put(JSONObject().put("text", text)))

    fun toolResults(results: List<Pair<String, JSONObject>>): JSONObject {
        val parts = JSONArray()
        for ((name, result) in results) {
            parts.put(
                JSONObject().put(
                    "functionResponse",
                    JSONObject().put("name", name).put("response", result)
                )
            )
        }
        return JSONObject().put("role", "user").put("parts", parts)
    }

    /** True for a turn the user typed or spoke (as opposed to tool results). */
    fun isUserText(content: JSONObject): Boolean {
        if (content.optString("role") != "user") return false
        val parts = content.optJSONArray("parts") ?: return false
        for (i in 0 until parts.length()) {
            val part = parts.optJSONObject(i) ?: continue
            if (part.has("functionResponse")) return false
        }
        return parts.length() > 0
    }
}

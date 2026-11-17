package com.spark.companion.brain

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * Client for OpenAI-style chat-completions APIs with tool calling. Groq and
 * OpenRouter both speak this format, so one class serves both. It converts the
 * neutral [Msg] history and the tool catalogue into OpenAI's shapes.
 */
class OpenAiCompatClient(
    private val providerLabel: String,
    private val endpoint: String,
    private val apiKey: () -> String,
    private val model: () -> String,
    private val defaultModel: String,
    private val extraHeaders: Map<String, String> = emptyMap(),
) : Llm {

    override suspend fun generate(system: String, history: List<Msg>, tools: JSONArray): LlmReply {
        val key = apiKey().trim()
        if (key.isEmpty()) {
            throw LlmException("No $providerLabel API key yet. Add one in Spark settings.")
        }
        val body = buildRequest(system, history, tools, model().ifBlank { defaultModel }).toString()
        val reply = OpenAiHttp.post(endpoint, key, body, extraHeaders, providerLabel)
        return parseResponse(reply.toString(), providerLabel)
    }

    companion object {
        fun groq(apiKey: () -> String, model: () -> String) = OpenAiCompatClient(
            "Groq", "https://api.groq.com/openai/v1/chat/completions", apiKey, model, DEFAULT_GROQ_MODEL
        )

        fun openRouter(apiKey: () -> String, model: () -> String) = OpenAiCompatClient(
            "OpenRouter", "https://openrouter.ai/api/v1/chat/completions", apiKey, model, DEFAULT_OPENROUTER_MODEL,
            mapOf("HTTP-Referer" to "https://spark.companion", "X-Title" to "Spark")
        )

        const val DEFAULT_GROQ_MODEL = "llama-3.3-70b-versatile"
        const val DEFAULT_OPENROUTER_MODEL = "meta-llama/llama-3.3-70b-instruct:free"

        /** Converts the neutral tool declarations to OpenAI's `tools` array. */
        fun toolsArray(declarations: JSONArray): JSONArray {
            val out = JSONArray()
            for (i in 0 until declarations.length()) {
                val decl = declarations.optJSONObject(i) ?: continue
                val fn = JSONObject()
                    .put("name", decl.optString("name"))
                    .put("description", decl.optString("description"))
                val params = decl.optJSONObject("parameters")
                fn.put("parameters", if (params != null) lowercaseTypes(params) else emptyParams())
                out.put(JSONObject().put("type", "function").put("function", fn))
            }
            return out
        }

        private fun emptyParams() = JSONObject().put("type", "object").put("properties", JSONObject())

        /** Gemini schemas use UPPERCASE types; JSON Schema (OpenAI) wants lowercase. */
        fun lowercaseTypes(node: JSONObject): JSONObject {
            val out = JSONObject()
            val keys = node.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                val v = node.get(k)
                out.put(
                    k,
                    when {
                        k == "type" && v is String -> v.lowercase()
                        v is JSONObject -> lowercaseTypes(v)
                        v is JSONArray -> lowercaseArray(v)
                        else -> v
                    }
                )
            }
            return out
        }

        private fun lowercaseArray(arr: JSONArray): JSONArray {
            val out = JSONArray()
            for (i in 0 until arr.length()) {
                when (val v = arr.get(i)) {
                    is JSONObject -> out.put(lowercaseTypes(v))
                    is JSONArray -> out.put(lowercaseArray(v))
                    else -> out.put(v)
                }
            }
            return out
        }

        fun buildRequest(system: String, history: List<Msg>, tools: JSONArray, model: String): JSONObject {
            val messages = JSONArray()
            messages.put(JSONObject().put("role", "system").put("content", system))
            for (m in history) {
                when (m) {
                    is Msg.User -> messages.put(JSONObject().put("role", "user").put("content", m.text))
                    is Msg.Assistant -> {
                        val msg = JSONObject().put("role", "assistant")
                        msg.put("content", m.text.ifBlank { JSONObject.NULL })
                        if (m.calls.isNotEmpty()) {
                            val calls = JSONArray()
                            m.calls.forEachIndexed { i, c ->
                                calls.put(
                                    JSONObject()
                                        .put("id", c.id.ifBlank { "call_$i" })
                                        .put("type", "function")
                                        .put(
                                            "function",
                                            JSONObject().put("name", c.name).put("arguments", c.args.toString())
                                        )
                                )
                            }
                            msg.put("tool_calls", calls)
                        }
                        messages.put(msg)
                    }
                    is Msg.ToolResults -> for (r in m.results) {
                        messages.put(
                            JSONObject()
                                .put("role", "tool")
                                .put("tool_call_id", r.id.ifBlank { "call_${r.name}" })
                                .put("name", r.name)
                                .put("content", r.result.toString())
                        )
                    }
                }
            }
            val request = JSONObject()
                .put("model", model)
                .put("messages", messages)
                .put("temperature", 0.6)
            if (tools.length() > 0) {
                request.put("tools", toolsArray(tools))
                request.put("tool_choice", "auto")
            }
            return request
        }

        @Throws(LlmException::class)
        fun parseResponse(raw: String, provider: String): LlmReply {
            val json = try {
                JSONObject(raw)
            } catch (e: JSONException) {
                throw LlmException("$provider sent a reply I couldn't read.", e)
            }
            json.optJSONObject("error")?.let {
                throw LlmException("$provider error: ${it.optString("message").take(200)}")
            }
            val message = json.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")
                ?: throw LlmException("$provider returned an empty reply.")
            val text = message.optString("content").takeIf { it != "null" }.orEmpty().trim()
            val calls = ArrayList<ToolCall>()
            val toolCalls = message.optJSONArray("tool_calls")
            if (toolCalls != null) {
                for (i in 0 until toolCalls.length()) {
                    val tc = toolCalls.optJSONObject(i) ?: continue
                    val fn = tc.optJSONObject("function") ?: continue
                    val args = try {
                        JSONObject(fn.optString("arguments").ifBlank { "{}" })
                    } catch (e: JSONException) {
                        JSONObject()
                    }
                    calls.add(ToolCall(fn.optString("name"), args, tc.optString("id")))
                }
            }
            if (text.isBlank() && calls.isEmpty()) {
                throw LlmException("$provider returned an empty reply.")
            }
            return LlmReply(Msg.Assistant(text, calls), text, calls)
        }
    }
}

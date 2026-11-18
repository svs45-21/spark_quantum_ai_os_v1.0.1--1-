package com.spark.companion.brain

import org.json.JSONArray
import org.json.JSONObject

/** One function call the model asked for. [id] is used by OpenAI-style APIs. */
data class ToolCall(val name: String, val args: JSONObject, val id: String = "")

/** The result of running one tool call, fed back to the model. */
data class ToolResult(val id: String, val name: String, val result: JSONObject)

/**
 * A provider-neutral conversation turn. The agent keeps the conversation as a
 * list of these, and each [Llm] serialises them into its own wire format. This
 * is what lets the user switch between Gemini, Groq and OpenRouter.
 */
sealed class Msg {
    data class User(val text: String) : Msg()

    /**
     * [raw] optionally holds the provider's own response object, replayed
     * verbatim by the same provider so opaque fields (e.g. Gemini thought
     * signatures) survive. Other providers ignore it and rebuild from
     * [text] and [calls].
     */
    data class Assistant(
        val text: String,
        val calls: List<ToolCall> = emptyList(),
        val raw: JSONObject? = null,
    ) : Msg()

    data class ToolResults(val results: List<ToolResult>) : Msg()
}

/** A model turn: the assistant message to store, plus its text and tool calls. */
data class LlmReply(val assistant: Msg.Assistant, val text: String, val calls: List<ToolCall>)

open class LlmException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * The provider doesn't serve the requested model (retired, renamed, or it can't
 * use tools). Clients catch this, look up what the key can use, and switch.
 */
class ModelUnavailableException(message: String) : LlmException(message)

/** The provider is overloaded or rate-limited right now (503, 429, 5xx). Another model or provider may work. */
class ProviderBusyException(message: String, cause: Throwable? = null) : LlmException(message, cause)

/** The model behind the companion. An interface so the agent loop is testable. */
interface Llm {
    @Throws(LlmException::class)
    suspend fun generate(system: String, history: List<Msg>, tools: JSONArray): LlmReply
}

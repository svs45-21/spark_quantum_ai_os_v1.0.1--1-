package com.spark.companion.brain

import org.json.JSONArray

/**
 * Sends each request to whichever chat provider the user has chosen. Because
 * the conversation is kept in the neutral [Msg] format, the provider can be
 * switched at any time without losing context.
 */
class RoutingLlm(private val provider: () -> String, private val clients: Map<String, Llm>) : Llm {
    override suspend fun generate(system: String, history: List<Msg>, tools: JSONArray): LlmReply {
        val key = provider()
        val client = clients[key] ?: clients["gemini"] ?: clients.values.first()
        return client.generate(system, history, tools)
    }

    companion object {
        const val GEMINI = "gemini"
        const val GROQ = "groq"
        const val OPENROUTER = "openrouter"
        const val OLLAMA = "ollama"
        const val CUSTOM = "custom"

        val PROVIDERS = listOf(
            GEMINI to "Google Gemini (free tier)",
            GROQ to "Groq (free, fast)",
            OPENROUTER to "OpenRouter (free models)",
            OLLAMA to "Ollama (your own server)",
            CUSTOM to "Custom server (your own model)",
        )
    }
}

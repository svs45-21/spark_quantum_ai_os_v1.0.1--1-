package com.spark.companion.brain

import org.json.JSONArray

/**
 * Sends each request to whichever chat provider the user has chosen. Because
 * the conversation is kept in the neutral [Msg] format, the provider can be
 * switched at any time without losing context.
 *
 * If the chosen provider can't answer (overloaded, rate-limited, offline or
 * its key refused), the request moves on to the other providers that are set
 * up, and finally to a free keyless service, so Spark still replies.
 */
class RoutingLlm(
    private val provider: () -> String,
    private val clients: Map<String, Llm>,
    /** Whether a provider has what it needs (key or server address). */
    private val configured: (String) -> Boolean = { true },
    /** Called when another provider answered instead of the chosen one. */
    private val onFallback: (String) -> Unit = {},
) : Llm {
    override suspend fun generate(system: String, history: List<Msg>, tools: JSONArray): LlmReply {
        val chosen = provider().takeIf { it in clients } ?: GEMINI
        val order = fallbackOrder(chosen, clients.keys, configured)
        var first: LlmException? = null
        for (key in order) {
            val client = clients[key] ?: continue
            try {
                val reply = client.generate(system, history, tools)
                if (key != chosen) onFallback(key)
                return reply
            } catch (e: LlmException) {
                if (first == null) first = e
                // Busy, unreachable, missing a key or broken: try the next provider.
            }
        }
        throw first ?: LlmException("No AI provider is set up yet. Add a key in Spark settings.")
    }

    companion object {
        const val GEMINI = "gemini"
        const val GROQ = "groq"
        const val OPENROUTER = "openrouter"
        const val OLLAMA = "ollama"
        const val CUSTOM = "custom"
        /** Free, keyless last resort. */
        const val FREE = "free"

        val PROVIDERS = listOf(
            GEMINI to "Google Gemini (free tier)",
            GROQ to "Groq (free, fast)",
            OPENROUTER to "OpenRouter (free models)",
            OLLAMA to "Ollama (your own server)",
            CUSTOM to "Custom server (your own model)",
        )

        /** The chosen provider first, then the other set-up ones, then the free service. */
        fun fallbackOrder(chosen: String, available: Collection<String>, configured: (String) -> Boolean): List<String> {
            val rest = listOf(GEMINI, GROQ, OPENROUTER, OLLAMA, CUSTOM)
                .filter { it != chosen && it in available && configured(it) }
            val free = if (FREE in available && chosen != FREE) listOf(FREE) else emptyList()
            return listOf(chosen) + rest + free
        }
    }
}

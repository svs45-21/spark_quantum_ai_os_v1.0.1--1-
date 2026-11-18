package com.spark.companion.brain

/**
 * Runs a request with the configured model and keeps it working when models
 * come and go:
 *
 * - If the provider says the model is gone, [discover] finds a replacement,
 *   the request is retried and [onSwitch] saves the new name.
 * - If the model is busy (overloaded or rate-limited), the request moves on to
 *   the next of [alternatives] and keeps using the one that answered for a
 *   while, without changing the user's saved choice.
 */
class ModelSwitch(
    private val configured: () -> String,
    private val onSwitch: (String) -> Unit = {},
    private val clock: () -> Long = { System.currentTimeMillis() },
) {
    @Volatile private var swap: Pair<String, String>? = null
    @Volatile private var detour: Triple<String, String, Long>? = null

    /** The model to use now. */
    fun current(): String {
        val c = configured()
        val d = detour
        if (d != null && d.first == c && clock() < d.third) return d.second
        val s = swap
        return if (s != null && s.first == c) s.second else c
    }

    @Throws(LlmException::class)
    suspend fun <T> run(
        discover: suspend () -> String?,
        alternatives: suspend () -> List<String> = { emptyList() },
        call: suspend (String) -> T,
    ): T {
        val model = current()
        try {
            return call(model)
        } catch (e: ModelUnavailableException) {
            val next = try {
                discover()
            } catch (d: LlmException) {
                null
            }
            if (next.isNullOrBlank() || next == model) {
                throw LlmException(
                    "${e.message} I couldn't find another model for this key. Choose one in Spark settings.", e
                )
            }
            swap = configured() to next
            onSwitch(next)
            return call(next)
        } catch (e: ProviderBusyException) {
            val others = try {
                alternatives().filter { it != model }.take(MAX_DETOURS)
            } catch (d: LlmException) {
                emptyList()
            }
            for (alt in others) {
                try {
                    val result = call(alt)
                    detour = Triple(configured(), alt, clock() + DETOUR_MS)
                    return result
                } catch (again: ProviderBusyException) {
                    continue
                } catch (gone: ModelUnavailableException) {
                    continue
                }
            }
            throw e
        }
    }

    companion object {
        const val MAX_DETOURS = 4
        /** How long to stay on a stand-in model before trying the chosen one again. */
        const val DETOUR_MS = 10 * 60 * 1000L
    }
}

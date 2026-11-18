package com.spark.companion.brain

/**
 * Runs a request with the configured model. If the provider says the model is
 * gone, it asks [discover] for a replacement, retries once, and reports the new
 * name through [onSwitch] so it is saved for next time.
 */
class ModelSwitch(
    private val configured: () -> String,
    private val onSwitch: (String) -> Unit = {},
) {
    @Volatile private var swap: Pair<String, String>? = null

    /** The model to use now: the configured one, unless it was found broken this session. */
    fun current(): String {
        val c = configured()
        val s = swap
        return if (s != null && s.first == c) s.second else c
    }

    @Throws(LlmException::class)
    suspend fun <T> run(discover: suspend () -> String?, call: suspend (String) -> T): T {
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
        }
    }
}

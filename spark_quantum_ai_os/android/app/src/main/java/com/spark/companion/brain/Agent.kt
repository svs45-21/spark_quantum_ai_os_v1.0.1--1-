package com.spark.companion.brain

import kotlinx.coroutines.CancellationException
import org.json.JSONArray
import org.json.JSONObject

/**
 * The companion's reasoning loop: send the conversation to the model, run the
 * tools it asks for (pausing for the user's approval where needed), feed the
 * results back, and repeat until it answers in words.
 */
class Agent(
    private val llm: Llm,
    private val host: ToolHost,
    private val maxSteps: Int = 24,
    private val maxHistory: Int = 60,
) {
    interface Listener {
        /** A tool is about to run; [status] is a short description for the UI. */
        fun onAction(status: String) {}
        /** Text the model said alongside tool calls (not the final answer). */
        fun onInterimText(text: String) {}
    }

    private val history = ArrayList<JSONObject>()

    /** Set by the end_conversation tool; read and cleared by the engine. */
    @Volatile
    var endRequested = false

    val historySize: Int get() = history.size

    /** Restores earlier plain-text turns (e.g. after the app restarts). */
    fun seed(turns: List<Pair<Boolean, String>>) {
        history.clear()
        // The API expects the conversation to open with a user turn.
        turns.dropWhile { !it.first }
            .forEach { (isUser, text) ->
                history.add(if (isUser) Content.userText(text) else Content.modelText(text))
            }
    }

    fun clear() = history.clear()

    /**
     * Handles one user message and returns the spoken/visible answer.
     * @param confirm asks the user to approve a risky action
     */
    suspend fun respond(
        userText: String,
        system: suspend () -> String,
        tools: JSONArray,
        confirm: suspend (ConfirmRequest) -> Boolean,
        listener: Listener = object : Listener {},
    ): String {
        val mark = history.size
        history.add(Content.userText(userText))
        try {
            var declined = 0
            repeat(maxSteps) {
                val reply = llm.generate(system(), history, tools)
                history.add(reply.content)
                if (reply.calls.isEmpty()) {
                    trim()
                    return reply.text
                }
                if (reply.text.isNotBlank()) listener.onInterimText(reply.text)

                val results = ArrayList<Pair<String, JSONObject>>()
                for (call in reply.calls) {
                    if (call.name == "end_conversation") {
                        endRequested = true
                        results.add(call.name to JSONObject().put("ok", true))
                        continue
                    }
                    val request = host.confirmationFor(call)
                    if (request != null) {
                        listener.onAction("Waiting for your OK")
                        if (!confirm(request)) {
                            declined++
                            results.add(
                                call.name to JSONObject()
                                    .put("ok", false)
                                    .put("error", "The user did not approve this action. Do not retry it.")
                            )
                            continue
                        }
                    }
                    listener.onAction(host.describe(call))
                    results.add(call.name to host.execute(call))
                }
                history.add(Content.toolResults(results))
                if (declined >= 3) {
                    // The model keeps trying things the user refuses; stop here.
                    val text = "Okay, I've stopped. Tell me what you'd like me to do instead."
                    history.add(Content.modelText(text))
                    trim()
                    return text
                }
            }
            val text = "That took more steps than I'm allowed in one go, so I stopped. Want me to keep going?"
            history.add(Content.modelText(text))
            trim()
            return text
        } catch (e: CancellationException) {
            rollback(mark, userText, "(You interrupted me before I finished.)")
            throw e
        } catch (e: LlmException) {
            rollback(mark, userText, null)
            throw e
        }
    }

    /**
     * Drops a half-finished turn so the history never ends on an unanswered
     * function call, which the API would reject on the next request.
     */
    private fun rollback(mark: Int, userText: String, note: String?) {
        while (history.size > mark) history.removeAt(history.size - 1)
        if (note != null) {
            history.add(Content.userText(userText))
            history.add(Content.modelText(note))
        }
    }

    /** Keeps the history bounded, always cutting at the start of a user turn. */
    private fun trim() {
        if (history.size <= maxHistory) return
        var cut = history.size - maxHistory
        while (cut < history.size && !Content.isUserText(history[cut])) cut++
        if (cut >= history.size) return
        repeat(cut) { history.removeAt(0) }
    }
}

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
        fun onAction(status: String) {}
        fun onInterimText(text: String) {}
    }

    private val history = ArrayList<Msg>()

    @Volatile
    var endRequested = false

    val historySize: Int get() = history.size

    /** Restores earlier plain-text turns (e.g. after the app restarts). */
    fun seed(turns: List<Pair<Boolean, String>>) {
        history.clear()
        turns.dropWhile { !it.first }
            .forEach { (isUser, text) -> history.add(if (isUser) Msg.User(text) else Msg.Assistant(text)) }
    }

    fun clear() = history.clear()

    suspend fun respond(
        userText: String,
        system: suspend () -> String,
        tools: JSONArray,
        confirm: suspend (ConfirmRequest) -> Boolean,
        listener: Listener = object : Listener {},
    ): String {
        val mark = history.size
        history.add(Msg.User(userText))
        try {
            var declined = 0
            repeat(maxSteps) {
                val reply = llm.generate(system(), history, tools)
                history.add(reply.assistant)
                if (reply.calls.isEmpty()) {
                    trim()
                    return reply.text
                }
                if (reply.text.isNotBlank()) listener.onInterimText(reply.text)

                val results = ArrayList<ToolResult>()
                for (call in reply.calls) {
                    if (call.name == "end_conversation") {
                        endRequested = true
                        results.add(ToolResult(call.id, call.name, JSONObject().put("ok", true)))
                        continue
                    }
                    val request = host.confirmationFor(call)
                    if (request != null) {
                        listener.onAction("Waiting for your OK")
                        if (!confirm(request)) {
                            declined++
                            results.add(
                                ToolResult(
                                    call.id, call.name,
                                    JSONObject().put("ok", false)
                                        .put("error", "The user did not approve this action. Do not retry it.")
                                )
                            )
                            continue
                        }
                    }
                    listener.onAction(host.describe(call))
                    results.add(ToolResult(call.id, call.name, host.execute(call)))
                }
                history.add(Msg.ToolResults(results))
                if (declined >= 3) {
                    val text = "Okay, I've stopped. Tell me what you'd like me to do instead."
                    history.add(Msg.Assistant(text))
                    trim()
                    return text
                }
            }
            val text = "That took more steps than I'm allowed in one go, so I stopped. Want me to keep going?"
            history.add(Msg.Assistant(text))
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

    /** Drops a half-finished turn so the history never ends on an unanswered call. */
    private fun rollback(mark: Int, userText: String, note: String?) {
        while (history.size > mark) history.removeAt(history.size - 1)
        if (note != null) {
            history.add(Msg.User(userText))
            history.add(Msg.Assistant(note))
        }
    }

    /** Keeps the history bounded, always cutting at the start of a user turn. */
    private fun trim() {
        if (history.size <= maxHistory) return
        var cut = history.size - maxHistory
        while (cut < history.size && history[cut] !is Msg.User) cut++
        if (cut >= history.size) return
        repeat(cut) { history.removeAt(0) }
    }
}

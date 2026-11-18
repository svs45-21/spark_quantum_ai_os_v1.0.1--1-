package com.spark.companion

import com.spark.companion.brain.Agent
import com.spark.companion.brain.ConfirmRequest
import com.spark.companion.brain.Llm
import com.spark.companion.brain.LlmException
import com.spark.companion.brain.LlmReply
import com.spark.companion.brain.Msg
import com.spark.companion.brain.ToolCall
import com.spark.companion.brain.ToolResult
import com.spark.companion.brain.ToolHost
import kotlinx.coroutines.test.runTest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentTest {

    private fun call(name: String, args: JSONObject = JSONObject()): LlmReply {
        val tc = ToolCall(name, args, "id_$name")
        return LlmReply(Msg.Assistant("", listOf(tc)), "", listOf(tc))
    }

    private fun text(t: String) = LlmReply(Msg.Assistant(t), t, emptyList())

    /** Scripted model that also records each history it was given. */
    private class FakeLlm(val replies: MutableList<() -> LlmReply>) : Llm {
        val seen = ArrayList<List<Msg>>()
        override suspend fun generate(system: String, history: List<Msg>, tools: JSONArray): LlmReply {
            seen.add(history.toList())
            return replies.removeAt(0)()
        }
    }

    private class FakeHost(val confirmTools: Set<String> = emptySet()) : ToolHost {
        val executed = ArrayList<String>()
        override suspend fun confirmationFor(call: ToolCall) =
            if (call.name in confirmTools) ConfirmRequest("do ${call.name}") else null
        override suspend fun execute(call: ToolCall): JSONObject {
            executed.add(call.name)
            return JSONObject().put("ok", true)
        }
        override fun describe(call: ToolCall) = call.name
    }

    @Test fun runsToolsThenAnswers() = runTest {
        val llm = FakeLlm(mutableListOf({ call("set_alarm") }, { text("Alarm set for 7.") }))
        val host = FakeHost()
        val agent = Agent(llm, host)
        val answer = agent.respond("wake me at 7", { "sys" }, JSONArray(), { true })
        assertEquals("Alarm set for 7.", answer)
        assertEquals(listOf("set_alarm"), host.executed)
        // user, model(call), tool result, model(text)
        assertEquals(4, agent.historySize)
        val toolTurn = llm.seen[1].last()
        assertTrue(toolTurn is Msg.ToolResults)
    }

    @Test fun declinedActionIsNotExecuted() = runTest {
        val llm = FakeLlm(mutableListOf({ call("send_sms") }, { text("Okay, I won't send it.") }))
        val host = FakeHost(setOf("send_sms"))
        val agent = Agent(llm, host)
        val answer = agent.respond("text mom", { "sys" }, JSONArray(), { false })
        assertEquals("Okay, I won't send it.", answer)
        assertTrue(host.executed.isEmpty())
        val tr = llm.seen[1].last() as Msg.ToolResults
        assertTrue(tr.results[0].result.optString("error").contains("did not approve"))
    }

    @Test fun approvedActionRuns() = runTest {
        val llm = FakeLlm(mutableListOf({ call("make_call") }, { text("Calling.") }))
        val host = FakeHost(setOf("make_call"))
        Agent(llm, host).respond("call mom", { "sys" }, JSONArray(), { true })
        assertEquals(listOf("make_call"), host.executed)
    }

    @Test fun stopsAfterRepeatedRefusals() = runTest {
        val llm = FakeLlm(MutableList(10) { { call("send_sms") } })
        val host = FakeHost(setOf("send_sms"))
        val answer = Agent(llm, host).respond("x", { "sys" }, JSONArray(), { false })
        assertTrue(answer.contains("stopped"))
        assertEquals(3, llm.seen.size)
    }

    @Test fun stepLimit() = runTest {
        val llm = FakeLlm(MutableList(10) { { call("read_screen") } })
        val answer = Agent(llm, FakeHost(), maxSteps = 4).respond("x", { "sys" }, JSONArray(), { true })
        assertTrue(answer.contains("more steps"))
    }

    @Test fun endConversationFlag() = runTest {
        val llm = FakeLlm(mutableListOf({ call("end_conversation") }, { text("Bye!") }))
        val host = FakeHost()
        val agent = Agent(llm, host)
        agent.respond("bye", { "sys" }, JSONArray(), { true })
        assertTrue(agent.endRequested)
        assertTrue(host.executed.isEmpty())
    }

    @Test fun errorRollsBackHalfTurn() = runTest {
        val llm = FakeLlm(mutableListOf({ call("open_app") }, { throw LlmException("offline") }, { text("hi") }))
        val agent = Agent(llm, FakeHost())
        try {
            agent.respond("open maps", { "sys" }, JSONArray(), { true })
        } catch (e: LlmException) { }
        assertEquals(0, agent.historySize)
        agent.respond("hello", { "sys" }, JSONArray(), { true })
        assertFalse(llm.seen.last().any { it is Msg.Assistant && it.calls.isNotEmpty() })
    }

    @Test fun trimKeepsUserTurnFirst() = runTest {
        val replies = MutableList<() -> LlmReply>(40) { i -> if (i % 2 == 0) ({ call("read_screen") }) else ({ text("ok") }) }
        val llm = FakeLlm(replies)
        val agent = Agent(llm, FakeHost(), maxHistory = 10)
        repeat(20) { agent.respond("go $it", { "sys" }, JSONArray(), { true }) }
        assertTrue(agent.historySize <= 10)
        assertTrue(llm.seen.last().first() is Msg.User)
    }

    @Test fun seedSkipsLeadingAssistantTurns() {
        val agent = Agent(FakeLlm(mutableListOf()), FakeHost())
        agent.seed(listOf(false to "hello", true to "hi", false to "hey"))
        assertEquals(2, agent.historySize)
    }
}

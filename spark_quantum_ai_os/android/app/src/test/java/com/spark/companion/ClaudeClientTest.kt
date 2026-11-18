package com.spark.companion

import com.anthropic.core.jsonMapper
import com.anthropic.models.messages.Message
import com.spark.companion.brain.ClaudeClient
import com.spark.companion.brain.LlmException
import com.spark.companion.brain.ModelUnavailableException
import com.spark.companion.brain.Msg
import com.spark.companion.brain.ProviderBusyException
import com.spark.companion.brain.ToolCall
import com.spark.companion.brain.ToolCatalog
import com.spark.companion.brain.ToolResult
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ClaudeClientTest {

    private fun json(o: Any): String = jsonMapper().writeValueAsString(o)

    @Test fun requestUsesOpusWithEffortAndRefusalFallback() {
        val req = ClaudeClient.request("claude-opus-5-5", "be nice", ClaudeClient.messages(listOf(Msg.User("hi"))), emptyList())
        assertEquals("claude-opus-5-5", req.model().asString())
        assertEquals(16000L, req.maxTokens())
        val body = json(req._body())
        assertTrue(body, body.contains("\"effort\":\"medium\""))
        assertTrue(body, body.contains("\"fallbacks\":\"default\""))
        assertEquals(listOf("server-side-fallback-2026-07-01"), req._additionalHeaders().values("anthropic-beta"))
        assertFalse("thinking is left to the model's default (adaptive)", body.contains("\"thinking\""))
    }

    @Test fun haikuGetsNoServerSideFallback() {
        val req = ClaudeClient.request("claude-haiku-5-5", "s", ClaudeClient.messages(listOf(Msg.User("hi"))), emptyList())
        assertFalse(json(req._body()).contains("fallbacks"))
        assertTrue(req._additionalHeaders().values("anthropic-beta").isEmpty())
    }

    @Test fun toolsUseLowercaseJsonSchema() {
        val tools = ClaudeClient.tools(ToolCatalog.declarations(false))
        assertTrue(tools.size > 10)
        val all = json(tools)
        assertFalse("no Gemini-style uppercase types", all.contains("\"type\":\"OBJECT\"") || all.contains("\"type\":\"STRING\""))
        assertTrue(all.contains("\"type\":\"string\""))
        assertTrue(all.contains("\"input_schema\""))
    }

    @Test fun historyStartsWithUserAndKeepsToolIdsPaired() {
        val call = ToolCall("set_alarm", JSONObject().put("hour", 7).put("minute", 0), "")
        val history = listOf(
            Msg.Assistant("Hi, I'm Spark!"),
            Msg.User("Wake me at 7"),
            Msg.Assistant("", listOf(call)),
            Msg.ToolResults(listOf(ToolResult("", "set_alarm", JSONObject().put("ok", true)))),
        )
        val msgs = ClaudeClient.messages(history)
        assertEquals("a leading assistant turn is dropped", 3, msgs.size)
        assertTrue(msgs[0].isUser())
        val wire = json(msgs)
        val ids = Regex("\"(?:id|tool_use_id)\":\"([^\"]+)\"").findAll(wire).map { it.groupValues[1] }.toList()
        assertEquals(2, ids.size)
        assertEquals("the result answers the same call", ids[0], ids[1])
        assertTrue(wire.contains("\"hour\":7"))
    }

    private fun message(body: String): Message = jsonMapper().readValue(
        """{"id":"msg_1","type":"message","role":"assistant","model":"claude-opus-5-5","container":null,"stop_sequence":null,
           "usage":{"input_tokens":1,"output_tokens":1},$body}""",
        Message::class.java,
    )

    @Test fun parsesTextAndToolCallsAndSkipsThinking() {
        val reply = ClaudeClient.parse(message(
            """"stop_reason":"tool_use","content":[
               {"type":"thinking","thinking":"","signature":"sig"},
               {"type":"text","text":"Setting it now."},
               {"type":"tool_use","id":"toolu_9","name":"set_alarm","input":{"hour":7,"minute":30}}]"""
        ))
        assertEquals("Setting it now.", reply.text)
        assertEquals(1, reply.calls.size)
        assertEquals("set_alarm", reply.calls[0].name)
        assertEquals("toolu_9", reply.calls[0].id)
        assertEquals(30, reply.calls[0].args.getInt("minute"))
    }

    @Test(expected = LlmException::class)
    fun refusalBecomesAFriendlyError() {
        ClaudeClient.parse(message(""""stop_reason":"refusal","content":[]"""))
    }

    @Test fun errorsMapToRetryOrSwitch() {
        assertTrue(ClaudeClient.translate(529, "overloaded") is ProviderBusyException)
        assertTrue(ClaudeClient.translate(429, "rate") is ProviderBusyException)
        assertTrue(ClaudeClient.translate(404, "model") is ModelUnavailableException)
        assertTrue(ClaudeClient.translate(401, "key").message!!.contains("key was refused"))
        assertTrue(ClaudeClient.translate(400, "Your credit balance is too low").message!!.contains("out of credit"))
    }
}

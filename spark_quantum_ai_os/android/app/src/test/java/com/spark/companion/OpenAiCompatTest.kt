package com.spark.companion

import com.spark.companion.brain.LlmException
import com.spark.companion.brain.Msg
import com.spark.companion.brain.OpenAiCompatClient
import com.spark.companion.brain.ToolCall
import com.spark.companion.brain.ToolCatalog
import com.spark.companion.brain.ToolResult
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenAiCompatTest {
    @Test fun lowercasesToolSchemaTypes() {
        val tools = OpenAiCompatClient.toolsArray(ToolCatalog.declarations(false))
        val first = tools.getJSONObject(0)
        assertEquals("function", first.getString("type"))
        val params = first.getJSONObject("function").getJSONObject("parameters")
        assertEquals("object", params.getString("type"))
        // No UPPERCASE Gemini types remain anywhere.
        assertTrue(!tools.toString().contains("\"STRING\""))
        assertTrue(!tools.toString().contains("\"OBJECT\""))
        assertTrue(tools.toString().contains("\"string\""))
    }

    @Test fun buildsOpenAiMessages() {
        val history = listOf(
            Msg.User("call maps"),
            Msg.Assistant("", listOf(ToolCall("open_app", JSONObject().put("name", "Maps"), "c1"))),
            Msg.ToolResults(listOf(ToolResult("c1", "open_app", JSONObject().put("ok", true)))),
        )
        val req = OpenAiCompatClient.buildRequest("sys", history, ToolCatalog.declarations(false), "llama")
        val msgs = req.getJSONArray("messages")
        assertEquals("system", msgs.getJSONObject(0).getString("role"))
        assertEquals("user", msgs.getJSONObject(1).getString("role"))
        val assistant = msgs.getJSONObject(2)
        assertEquals("assistant", assistant.getString("role"))
        val tc = assistant.getJSONArray("tool_calls").getJSONObject(0)
        assertEquals("c1", tc.getString("id"))
        assertEquals("open_app", tc.getJSONObject("function").getString("name"))
        val tool = msgs.getJSONObject(3)
        assertEquals("tool", tool.getString("role"))
        assertEquals("c1", tool.getString("tool_call_id"))
        assertEquals("llama", req.getString("model"))
    }

    @Test fun parsesTextReply() {
        val raw = """{"choices":[{"message":{"role":"assistant","content":"Hi there!"}}]}"""
        val reply = OpenAiCompatClient.parseResponse(raw, "Groq")
        assertEquals("Hi there!", reply.text)
        assertTrue(reply.calls.isEmpty())
    }

    @Test fun parsesToolCall() {
        val raw = """{"choices":[{"message":{"role":"assistant","content":null,"tool_calls":[
            {"id":"call_9","type":"function","function":{"name":"set_alarm","arguments":"{\"hour\":7,\"minute\":0}"}}]}}]}"""
        val reply = OpenAiCompatClient.parseResponse(raw, "Groq")
        assertEquals(1, reply.calls.size)
        assertEquals("set_alarm", reply.calls[0].name)
        assertEquals("call_9", reply.calls[0].id)
        assertEquals(7, reply.calls[0].args.getInt("hour"))
    }

    @Test(expected = LlmException::class)
    fun surfacesApiError() {
        OpenAiCompatClient.parseResponse("""{"error":{"message":"bad model"}}""", "OpenRouter")
    }
}

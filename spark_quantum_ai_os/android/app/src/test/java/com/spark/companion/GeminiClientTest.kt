package com.spark.companion

import com.spark.companion.brain.Msg
import com.spark.companion.brain.GeminiClient
import com.spark.companion.brain.LlmException
import com.spark.companion.brain.ToolCatalog
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GeminiClientTest {
    @Test fun parsesTextAndCalls() {
        val raw = """{"candidates":[{"content":{"role":"model","parts":[
            {"text":"thinking","thought":true},
            {"text":"On it."},
            {"functionCall":{"name":"open_app","args":{"name":"Maps"}},"thoughtSignature":"abc"}
        ]},"finishReason":"STOP"}]}"""
        val reply = GeminiClient.parseResponse(raw)
        assertEquals("On it.", reply.text)
        assertEquals(1, reply.calls.size)
        assertEquals("open_app", reply.calls[0].name)
        assertEquals("Maps", reply.calls[0].args.getString("name"))
        // Signature must survive so it can be replayed.
        assertTrue(reply.assistant.raw.toString().contains("thoughtSignature"))
    }

    @Test(expected = LlmException::class)
    fun blockedPromptThrows() {
        GeminiClient.parseResponse("""{"promptFeedback":{"blockReason":"SAFETY"}}""")
    }

    @Test fun requestShape() {
        val req = GeminiClient.buildRequest("sys", listOf(Msg.User("hi")), ToolCatalog.declarations(false))
        assertEquals("sys", req.getJSONObject("systemInstruction").getJSONArray("parts").getJSONObject(0).getString("text"))
        assertEquals(1, req.getJSONArray("contents").length())
        val decls = req.getJSONArray("tools").getJSONObject(0).getJSONArray("functionDeclarations")
        assertTrue(decls.length() > 20)
        val noTools = GeminiClient.buildRequest("sys", emptyList(), JSONArray())
        assertFalse(noTools.has("tools"))
    }

    @Test fun catalogNamesUniqueAndDesktopToggle() {
        assertEquals(ToolCatalog.names.size, ToolCatalog.names.toSet().size)
        val without = ToolCatalog.declarations(false).toString()
        assertFalse(without.contains("ask_desktop_spark"))
        assertTrue(ToolCatalog.declarations(true).toString().contains("ask_desktop_spark"))
    }

    @Test fun friendlyErrors() {
        assertTrue(GeminiClient.errorMessage(403, "").contains("API key"))
        assertTrue(GeminiClient.errorMessage(429, "").contains("usage limit"))
        assertTrue(GeminiClient.endpoint("models/gemini-2.5-pro").endsWith("/gemini-2.5-pro:generateContent"))
    }
}

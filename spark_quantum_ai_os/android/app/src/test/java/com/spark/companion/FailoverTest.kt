package com.spark.companion

import com.spark.companion.brain.LlmException
import com.spark.companion.brain.LlmReply
import com.spark.companion.brain.Llm
import com.spark.companion.brain.ModelSwitch
import com.spark.companion.brain.Msg
import com.spark.companion.brain.ProviderBusyException
import com.spark.companion.brain.RoutingLlm
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Test

class FailoverTest {

    @Test fun busyModelHandsOverToTheNextAndSticksForAWhile() = runBlocking {
        var now = 0L
        val sw = ModelSwitch({ "flash-latest" }, clock = { now })
        val tried = ArrayList<String>()
        val busy = setOf("flash-latest", "3.8-flash")
        val out = sw.run(discover = { null }, alternatives = { listOf("flash-latest", "3.8-flash", "3.6-flash", "lite") }) { m ->
            tried += m
            if (m in busy) throw ProviderBusyException("high demand") else "ok:$m"
        }
        assertEquals("ok:3.6-flash", out)
        assertEquals(listOf("flash-latest", "3.8-flash", "3.6-flash"), tried)
        assertEquals("3.6-flash", sw.current())
        now += ModelSwitch.DETOUR_MS + 1
        assertEquals("the chosen model is tried again later", "flash-latest", sw.current())
    }

    @Test(expected = ProviderBusyException::class)
    fun allBusyReportsBusy() {
        runBlocking {
            ModelSwitch({ "a" }).run(discover = { null }, alternatives = { listOf("b", "c") }) { throw ProviderBusyException("busy") }
        }
    }

    private class Fake(val name: String, val fail: Boolean) : Llm {
        var calls = 0
        override suspend fun generate(system: String, history: List<Msg>, tools: JSONArray): LlmReply {
            calls++
            if (fail) throw ProviderBusyException("$name busy")
            return LlmReply(Msg.Assistant(name, emptyList()), name, emptyList())
        }
    }

    @Test fun routingFallsBackToOtherProvidersThenTheFreeService() = runBlocking {
        val gemini = Fake("gemini", true)
        val groq = Fake("groq", true)
        val openrouter = Fake("openrouter", false)
        val free = Fake("free", false)
        var used = ""
        val llm = RoutingLlm(
            { RoutingLlm.GEMINI },
            mapOf(RoutingLlm.GEMINI to gemini, RoutingLlm.GROQ to groq, RoutingLlm.OPENROUTER to openrouter, RoutingLlm.FREE to free),
            configured = { it != RoutingLlm.OLLAMA },
            onFallback = { used = it },
        )
        assertEquals("openrouter", llm.generate("s", listOf(Msg.User("hi")), JSONArray()).text)
        assertEquals("openrouter", used)
        assertEquals(0, free.calls)
        assertEquals(
            listOf("groq", "gemini", "openrouter", "free"),
            RoutingLlm.fallbackOrder("groq", listOf("gemini", "groq", "openrouter", "free"), { true }),
        )
        assertEquals(
            "providers without a key are skipped",
            listOf("gemini", "free"),
            RoutingLlm.fallbackOrder("gemini", listOf("gemini", "groq", "free"), { it != "groq" }),
        )
    }

    @Test(expected = LlmException::class)
    fun everythingFailingReportsTheChosenProvidersError() {
        runBlocking {
            RoutingLlm({ "gemini" }, mapOf("gemini" to Fake("gemini", true), "free" to Fake("free", true))).generate("s", emptyList(), JSONArray())
        }
    }
}

package com.spark.companion

import com.spark.companion.brain.LlmException
import com.spark.companion.brain.LlmReply
import com.spark.companion.brain.Llm
import com.spark.companion.brain.ModelSwitch
import com.spark.companion.brain.Msg
import com.spark.companion.brain.ProviderBusyException
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
}

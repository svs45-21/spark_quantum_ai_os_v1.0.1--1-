package com.spark.companion

import com.spark.companion.brain.LlmException
import com.spark.companion.brain.ModelCatalog
import com.spark.companion.brain.ModelCatalog.ModelInfo
import com.spark.companion.brain.ModelSwitch
import com.spark.companion.brain.ModelUnavailableException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelCatalogTest {

    private fun gemini(vararg ids: String) = ids.map { ModelInfo(it, methods = listOf("generateContent")) }

    // Names the Gemini API listed for a free key in November 2026.
    private val geminiNow = gemini(
        "gemini-2.5-pro", "gemini-3.8-flash", "gemini-3.7-flash", "gemini-3.5-flash", "gemini-flash-latest",
        "gemini-3.5-flash-lite", "gemini-flash-lite-latest", "gemini-pro-latest", "gemini-3.1-pro-preview",
        "gemini-2.5-flash-image", "gemini-3.1-flash-image", "gemini-3-pro-image", "gemini-3.1-flash-lite-image",
        "gemini-2.5-flash-preview-tts", "gemini-3.8-flash-tts", "gemini-3.1-flash-tts-preview",
        "gemini-3.8-flash-lite-tts", "gemma-3-27b-it",
    ) + ModelInfo("gemini-embedding-001", methods = listOf("embedContent"))

    @Test fun geminiPrefersRollingFlashAlias() {
        assertEquals("gemini-flash-latest", ModelCatalog.pickGeminiChat(geminiNow))
    }

    @Test fun geminiFallsBackToNewestStableFlash() {
        val noAlias = geminiNow.filterNot { it.id.endsWith("-latest") }
        assertEquals("gemini-3.8-flash", ModelCatalog.pickGeminiChat(noAlias))
    }

    @Test fun geminiVoiceAndImagePicks() {
        assertEquals("gemini-3.8-flash-tts", ModelCatalog.pickGeminiTts(geminiNow))
        assertEquals("gemini-3.1-flash-image", ModelCatalog.pickGeminiImage(geminiNow))
    }

    @Test fun parsesGeminiListing() {
        val raw = """{"models":[{"name":"models/gemini-3.8-flash","supportedGenerationMethods":["generateContent","countTokens"]},
            {"name":"models/text-embedding-004","supportedGenerationMethods":["embedContent"]}]}"""
        val list = ModelCatalog.parseGemini(raw)
        assertEquals(listOf("gemini-3.8-flash", "text-embedding-004"), list.map { it.id })
        assertEquals("gemini-3.8-flash", ModelCatalog.pickGeminiChat(list))
    }

    @Test fun groqSkipsSpeechModelsAndPrefersToolModels() {
        val raw = """{"data":[{"id":"whisper-large-v3","context_window":448},
            {"id":"llama-3.1-8b-instant","context_window":131072},
            {"id":"qwen/qwen3-32b","context_window":131072},
            {"id":"meta-llama/llama-guard-4-12b","context_window":131072}]}"""
        assertEquals("qwen/qwen3-32b", ModelCatalog.pickGroq(ModelCatalog.parseOpenAi(raw)))
        val unknown = listOf(ModelInfo("whisper-x", context = 9), ModelInfo("new-model-a", context = 8000), ModelInfo("new-model-b", context = 32000))
        assertEquals("new-model-b", ModelCatalog.pickGroq(unknown))
    }

    @Test fun openRouterPicksFreeToolCapableModel() {
        val raw = """{"data":[
            {"id":"openai/gpt-5","supported_parameters":["tools"],"pricing":{"prompt":"0.00001","completion":"0.00003"},"context_length":400000},
            {"id":"some/chat-model:free","supported_parameters":["temperature"],"pricing":{"prompt":"0","completion":"0"},"context_length":200000},
            {"id":"qwen/qwen3-235b:free","supported_parameters":["tools","temperature"],"pricing":{"prompt":"0","completion":"0"},"context_length":40000},
            {"id":"deepseek/deepseek-chat-v3.1:free","supported_parameters":["tools"],"pricing":{"prompt":"0","completion":"0"},"context_length":160000}]}"""
        assertEquals("deepseek/deepseek-chat-v3.1:free", ModelCatalog.pickOpenRouter(ModelCatalog.parseOpenAi(raw)))
        assertNull(ModelCatalog.pickOpenRouter(listOf(ModelInfo("paid/model", tools = true))))
    }

    @Test fun localPrefersToolFamilies() {
        val list = ModelCatalog.parseOpenAi("""{"data":[{"id":"phi3:mini"},{"id":"qwen2.5:7b"},{"id":"nomic-embed-text"}]}""")
        assertEquals("qwen2.5:7b", ModelCatalog.pickLocal(list))
    }

    @Test fun modelsUrlBesideChatEndpoint() {
        assertEquals("http://h:11434/v1/models", ModelCatalog.modelsUrlFor("http://h:11434/v1/chat/completions"))
        assertEquals("https://api.groq.com/openai/v1/models", ModelCatalog.modelsUrlFor("https://api.groq.com/openai/v1/chat/completions"))
    }

    @Test fun recognisesModelErrors() {
        assertTrue(ModelCatalog.looksLikeModelProblem("This model models/gemini-2.5-flash is no longer available to new users."))
        assertTrue(ModelCatalog.looksLikeModelProblem("The model `llama-3.3-70b-versatile` has been decommissioned"))
        assertTrue(ModelCatalog.looksLikeModelProblem("No endpoints found that support tool use."))
        assertFalse(ModelCatalog.looksLikeModelProblem("Invalid API key provided"))
    }

    @Test fun switchRetriesWithDiscoveredModelAndSaves() = runBlocking {
        var saved = ""
        val tried = ArrayList<String>()
        val sw = ModelSwitch({ "old-model" }, onSwitch = { saved = it })
        val out = sw.run({ "new-model" }) { m ->
            tried += m
            if (m == "old-model") throw ModelUnavailableException("gone") else "ok:$m"
        }
        assertEquals("ok:new-model", out)
        assertEquals(listOf("old-model", "new-model"), tried)
        assertEquals("new-model", saved)
        assertEquals("new-model", sw.current())
    }

    @Test(expected = LlmException::class)
    fun switchGivesUpWhenNothingElseWorks() {
        runBlocking {
            ModelSwitch({ "only" }).run({ "only" }) { throw ModelUnavailableException("gone") }
        }
    }

    @Test fun retiredDefaultsAreNotReused() {
        assertTrue("gemini-2.5-flash" in ModelCatalog.RETIRED)
        assertFalse(com.spark.companion.brain.GeminiClient.DEFAULT_MODEL in ModelCatalog.RETIRED)
    }
}

class ModelListingTest {
    private val day = 24L * 60 * 60 * 1000

    @Test fun firstFetchMarksNothingNew() {
        val m = com.spark.companion.brain.ModelListing.merge(null, listOf("a" to "", "b" to ""), 1000L)
        assertTrue(m.added.isEmpty())
        assertFalse(m.list.entries.any { com.spark.companion.brain.ModelListing.isNew(it, 1000L) })
    }

    @Test fun laterFetchFlagsAddedAndRemovedModels() {
        val L = com.spark.companion.brain.ModelListing
        val first = L.merge(null, listOf("a" to "", "b" to ""), 0L).list
        val second = L.merge(first, listOf("c" to "free", "a" to ""), day)
        assertEquals(listOf("c"), second.added)
        assertEquals(listOf("b"), second.removed)
        assertEquals(listOf("c", "a"), second.list.ids)
        val c = second.list.entries.first()
        assertTrue(L.isNew(c, day + 1))
        assertFalse(L.isNew(c, day + L.NEW_FOR_MS + 1))
        // Still remembered as first seen on day one after another refresh.
        val third = L.merge(second.list, listOf("a" to "", "c" to "free"), 3 * day)
        assertTrue(third.added.isEmpty())
        assertEquals(day, third.list.entries.first { it.id == "c" }.firstSeen)
    }

    @Test fun jsonRoundTrip() {
        val L = com.spark.companion.brain.ModelListing
        val list = L.merge(null, listOf("x/y:free" to "free · phone control"), 5L).list
        assertEquals(list, L.fromJson(L.toJson(list)))
        assertNull(L.fromJson("not json"))
    }

    @Test fun dropdownOrdersShowEveryModelBestFirst() {
        val or = ModelCatalog.openRouterModels(listOf(
            ModelInfo("paid/big", tools = true, context = 1_000_000),
            ModelInfo("x/chat:free", tools = false, free = true),
            ModelInfo("y/gpt-oss-120b:free", tools = true, free = true, context = 131_000),
        ))
        assertEquals(listOf("y/gpt-oss-120b:free", "x/chat:free", "paid/big"), or.map { it.id })
        assertEquals("free · phone control · 131k context", ModelCatalog.tags(or.first()))
        val chat = ModelCatalog.geminiChatModels(gemini("gemini-3.5-flash", "gemini-flash-latest", "gemini-2.5-pro", "gemini-3.8-flash-tts"))
        assertEquals(listOf("gemini-flash-latest", "gemini-3.5-flash", "gemini-2.5-pro"), chat)
    }

    private fun gemini(vararg ids: String) = ids.map { ModelInfo(it, methods = listOf("generateContent")) }
}

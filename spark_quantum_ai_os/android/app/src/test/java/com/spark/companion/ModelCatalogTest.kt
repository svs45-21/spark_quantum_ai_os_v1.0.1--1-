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
        val sw = ModelSwitch({ "old-model" }) { saved = it }
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

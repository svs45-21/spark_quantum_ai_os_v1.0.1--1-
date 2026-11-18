package com.spark.companion.brain

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI

/**
 * Providers retire model names often. Instead of hard-coding one name and
 * breaking when it disappears, Spark asks the provider which models the key can
 * use and picks the best one for the job.
 */
object ModelCatalog {

    /** One model a provider offers. [tools] is null when the provider doesn't say. */
    data class ModelInfo(
        val id: String,
        val tools: Boolean? = null,
        val free: Boolean = false,
        val context: Int = 0,
        val methods: List<String> = emptyList(),
    )

    /** Model names Spark once shipped as defaults that providers have since retired. */
    val RETIRED = setOf(
        "gemini-2.5-flash",
        "gemini-2.0-flash",
        "gemini-1.5-flash",
        "gemini-2.5-flash-preview-tts",
        "llama-3.3-70b-versatile",
        "meta-llama/llama-3.3-70b-instruct:free",
    )

    // ── Fetching ────────────────────────────────────────────────────────

    const val GEMINI_MODELS_URL = "https://generativelanguage.googleapis.com/v1beta/models?pageSize=1000"
    const val GROQ_MODELS_URL = "https://api.groq.com/openai/v1/models"
    const val OPENROUTER_MODELS_URL = "https://openrouter.ai/api/v1/models"

    @Throws(LlmException::class)
    suspend fun listGemini(key: String): List<ModelInfo> {
        if (key.isBlank()) throw LlmException("No Gemini API key yet. Add one in Spark settings.")
        val raw = get(GEMINI_MODELS_URL, mapOf("x-goog-api-key" to key.trim()), "Gemini")
        return parseGemini(raw)
    }

    /** Lists an OpenAI-style `/models` endpoint (Groq, OpenRouter, Ollama, custom). */
    @Throws(LlmException::class)
    suspend fun listOpenAi(url: String, key: String, provider: String): List<ModelInfo> {
        val headers = if (key.isNotBlank()) mapOf("Authorization" to "Bearer ${key.trim()}") else emptyMap()
        return parseOpenAi(get(url, headers, provider))
    }

    /** The `/models` URL that sits beside a chat-completions endpoint. */
    fun modelsUrlFor(chatEndpoint: String): String {
        val u = chatEndpoint.trim().trimEnd('/')
        return when {
            u.isEmpty() -> ""
            u.endsWith("/chat/completions") -> u.removeSuffix("/chat/completions") + "/models"
            u.endsWith("/completions") -> u.removeSuffix("/completions") + "/models"
            else -> "$u/models"
        }
    }

    private suspend fun get(url: String, headers: Map<String, String>, provider: String): String =
        try {
            runInterruptible(Dispatchers.IO) {
                val conn = URI(url).toURL().openConnection() as HttpURLConnection
                try {
                    conn.connectTimeout = 15_000
                    conn.readTimeout = 30_000
                    headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }
                    val code = conn.responseCode
                    val stream = if (code in 200..299) conn.inputStream else conn.errorStream
                    val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
                    when (code) {
                        in 200..299 -> text
                        401, 403 -> throw LlmException("The $provider API key was refused. Check it in Spark settings.")
                        else -> throw LlmException("$provider wouldn't list its models ($code).")
                    }
                } finally {
                    conn.disconnect()
                }
            }
        } catch (e: IOException) {
            throw LlmException("I can't reach $provider right now.", e)
        }

    // ── Parsing ─────────────────────────────────────────────────────────

    fun parseGemini(raw: String): List<ModelInfo> {
        val arr = try {
            JSONObject(raw).optJSONArray("models") ?: JSONArray()
        } catch (e: JSONException) {
            return emptyList()
        }
        val out = ArrayList<ModelInfo>()
        for (i in 0 until arr.length()) {
            val m = arr.optJSONObject(i) ?: continue
            val id = m.optString("name").removePrefix("models/")
            if (id.isEmpty()) continue
            val methods = m.optJSONArray("supportedGenerationMethods")
            val list = (0 until (methods?.length() ?: 0)).map { methods!!.optString(it) }
            out.add(ModelInfo(id, methods = list, context = m.optInt("inputTokenLimit")))
        }
        return out
    }

    fun parseOpenAi(raw: String): List<ModelInfo> {
        val root = try {
            JSONObject(raw)
        } catch (e: JSONException) {
            return emptyList()
        }
        // OpenAI/Groq/OpenRouter use {data:[…]}; Ollama's native API uses {models:[…]}.
        val arr = root.optJSONArray("data") ?: root.optJSONArray("models") ?: JSONArray()
        val out = ArrayList<ModelInfo>()
        for (i in 0 until arr.length()) {
            val m = arr.optJSONObject(i) ?: continue
            val id = m.optString("id").ifEmpty { m.optString("name") }
            if (id.isEmpty()) continue
            if (m.has("active") && !m.optBoolean("active", true)) continue
            val params = m.optJSONArray("supported_parameters")
            val tools = params?.let { p -> (0 until p.length()).any { p.optString(it) == "tools" } }
            val pricing = m.optJSONObject("pricing")
            val free = id.endsWith(":free") ||
                (pricing != null && isZero(pricing.optString("prompt")) && isZero(pricing.optString("completion")))
            val context = m.optInt("context_length").takeIf { it > 0 } ?: m.optInt("context_window")
            out.add(ModelInfo(id, tools, free, context))
        }
        return out
    }

    private fun isZero(v: String) = v.toDoubleOrNull() == 0.0

    // ── Choosing ────────────────────────────────────────────────────────

    private val GEMINI_VERSION = Regex("""gemini-(\d+(?:\.\d+)?)""")

    private fun geminiVersion(id: String) = GEMINI_VERSION.find(id)?.groupValues?.get(1)?.toDoubleOrNull() ?: 0.0

    private fun canGenerate(m: ModelInfo) = m.methods.isEmpty() || "generateContent" in m.methods

    private val GEMINI_NOT_CHAT = listOf(
        "tts", "image", "embedding", "live", "audio", "native", "robotics", "computer-use",
        "exp", "learnlm", "nano", "gemma", "aqa", "veo", "imagen", "lyria",
    )

    /** Best Gemini model for chat with tool calls. */
    fun pickGeminiChat(models: List<ModelInfo>): String? {
        val ids = models.filter(::canGenerate).map { it.id }
            .filter { id -> id.startsWith("gemini") && GEMINI_NOT_CHAT.none { id.contains(it) } }
        if (ids.isEmpty()) return null
        // The rolling alias always points at the current Flash model, so it never retires.
        if ("gemini-flash-latest" in ids) return "gemini-flash-latest"
        fun rank(id: String): Int = when {
            id.contains("flash") && !id.contains("lite") -> 0
            id.contains("flash-lite") || id == "gemini-flash-lite-latest" -> 1
            id.contains("pro") -> 2
            else -> 3
        }
        return ids.sortedWith(
            compareBy<String> { rank(it) }
                .thenByDescending { geminiVersion(it) }
                .thenBy { if (it.contains("preview")) 1 else 0 }
                .thenBy { it.length }
        ).first()
    }

    /** Best Gemini model for natural speech. */
    fun pickGeminiTts(models: List<ModelInfo>): String? = models.filter(::canGenerate).map { it.id }
        .filter { it.startsWith("gemini") && it.contains("tts") }
        .sortedWith(
            compareBy<String> { if (it.contains("lite")) 1 else 0 }
                .thenByDescending { geminiVersion(it) }
                .thenBy { if (it.contains("preview")) 1 else 0 }
        ).firstOrNull()

    /** Best Gemini model for making and editing images. */
    fun pickGeminiImage(models: List<ModelInfo>): String? = models.filter(::canGenerate).map { it.id }
        .filter { it.startsWith("gemini") && it.contains("image") }
        .sortedWith(
            compareBy<String> { if (it.contains("flash")) 0 else 1 }
                .thenBy { if (it.contains("lite")) 1 else 0 }
                .thenByDescending { geminiVersion(it) }
                .thenBy { if (it.contains("preview")) 1 else 0 }
        ).firstOrNull()

    private val NOT_CHAT = listOf(
        "whisper", "tts", "playai", "orpheus", "guard", "embed", "distil", "compound",
        "moderation", "rerank", "vision-preview", "allam", "speech", "transcribe",
    )

    private fun chatOnly(models: List<ModelInfo>) =
        models.filter { m -> NOT_CHAT.none { m.id.contains(it, ignoreCase = true) } }

    /** Groq models in order of preference: strong at following tool calls first. */
    private val GROQ_PREFERRED = listOf(
        "openai/gpt-oss-120b",
        "moonshotai/kimi-k2-instruct-0905",
        "moonshotai/kimi-k2-instruct",
        "llama-3.3-70b-versatile",
        "qwen/qwen3-32b",
        "meta-llama/llama-4-maverick-17b-128e-instruct",
        "meta-llama/llama-4-scout-17b-16e-instruct",
        "openai/gpt-oss-20b",
        "llama-3.1-8b-instant",
    )

    fun pickGroq(models: List<ModelInfo>): String? {
        val chat = chatOnly(models)
        val ids = chat.map { it.id }.toSet()
        GROQ_PREFERRED.firstOrNull { it in ids }?.let { return it }
        return chat.maxByOrNull { it.context }?.id
    }

    private val OPENROUTER_PREFERRED = listOf(
        "gpt-oss-120b", "deepseek-chat", "deepseek-v3", "qwen3-coder", "qwen3", "kimi-k2", "glm-4",
        "llama-3.3-70b", "llama-4", "mistral-small", "gemini", "gemma-3", "gpt-oss-20b",
    )

    /** A free OpenRouter model that can call tools (phone control needs tools). */
    fun pickOpenRouter(models: List<ModelInfo>): String? {
        val free = chatOnly(models).filter { it.free }
        val withTools = free.filter { it.tools == true }.ifEmpty { free.filter { it.tools != false } }
        if (withTools.isEmpty()) return null
        for (want in OPENROUTER_PREFERRED) {
            withTools.filter { it.id.contains(want, ignoreCase = true) }
                .maxByOrNull { it.context }?.let { return it.id }
        }
        return withTools.maxByOrNull { it.context }?.id
    }

    private val LOCAL_PREFERRED = listOf("qwen3", "llama3.3", "llama3.1", "qwen2.5", "mistral", "llama3.2", "gemma")

    /** Ollama / LM Studio / custom servers: prefer families that handle tools well. */
    fun pickLocal(models: List<ModelInfo>): String? {
        val chat = chatOnly(models)
        for (want in LOCAL_PREFERRED) {
            chat.firstOrNull { it.id.contains(want, ignoreCase = true) }?.let { return it.id }
        }
        return chat.firstOrNull()?.id
    }

    /** True when an error body says the model itself is the problem. */
    fun looksLikeModelProblem(detail: String): Boolean {
        val d = detail.lowercase()
        if (!d.contains("model") && !d.contains("endpoint")) return false
        return listOf(
            "not found", "no longer available", "decommissioned", "does not exist", "not exist",
            "not a valid model", "invalid model", "not supported", "unsupported", "no endpoints found",
            "deprecated", "unknown model", "not available",
        ).any { d.contains(it) }
    }
}

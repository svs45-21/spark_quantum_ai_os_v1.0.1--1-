package com.spark.companion.brain

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import org.json.JSONException
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.util.Base64
import java.util.UUID

/**
 * OpenAI's GPT Image models (with the user's own OpenAI API key). Claude
 * doesn't make pictures, so this is the paid, high-quality image maker:
 * realistic full-body people, and restyled portraits that keep the user's
 * face when a photo is given (the edits endpoint takes it as a reference).
 */
class OpenAiImageGen(
    private val apiKey: () -> String,
    model: () -> String,
    onModelSwitch: (String) -> Unit = {},
    /** "1024x1536" for full-length people, "1024x1024" for portraits. */
    private val size: String = SQUARE,
) : ImageGen {

    override val canUsePhoto = true

    private val models = ModelSwitch({ model().trim().ifEmpty { DEFAULT_MODEL } }, onModelSwitch)

    override suspend fun generate(prompt: String, photoJpeg: ByteArray?): ByteArray {
        val key = apiKey().trim()
        if (key.isEmpty()) throw LlmException("No OpenAI API key yet. Add one in Spark settings → Pictures.")
        val reply = models.run(
            discover = { rank(listModels(key)).firstOrNull() },
            alternatives = { rank(listModels(key)) },
        ) { m ->
            if (photoJpeg == null) {
                send("$BASE/images/generations", key, "application/json", generationBody(m, prompt, size).toString().toByteArray())
            } else {
                val boundary = "spark-" + UUID.randomUUID().toString()
                send("$BASE/images/edits", key, "multipart/form-data; boundary=$boundary", editBody(boundary, m, prompt, size, photoJpeg))
            }
        }
        return parseImage(reply)
    }

    /** GPT Image models this key can use, best first. */
    @Throws(LlmException::class)
    suspend fun availableModels(): List<String> {
        val key = apiKey().trim()
        if (key.isEmpty()) throw LlmException("No OpenAI API key yet.")
        return rank(listModels(key))
    }

    private suspend fun listModels(key: String): List<String> {
        val raw = runInterruptible(Dispatchers.IO) {
            val conn = URI("$BASE/models").toURL().openConnection() as HttpURLConnection
            try {
                conn.connectTimeout = 15_000
                conn.readTimeout = 30_000
                conn.setRequestProperty("Authorization", "Bearer $key")
                val code = conn.responseCode
                val text = (if (code in 200..299) conn.inputStream else conn.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
                if (code !in 200..299) throw errorFor(code, text)
                text
            } finally {
                conn.disconnect()
            }
        }
        val data = try { JSONObject(raw).optJSONArray("data") } catch (e: JSONException) { null } ?: return emptyList()
        return (0 until data.length()).mapNotNull { data.optJSONObject(it)?.optString("id") }
    }

    private suspend fun send(url: String, key: String, contentType: String, body: ByteArray): String = try {
        runInterruptible(Dispatchers.IO) {
            val conn = URI(url).toURL().openConnection() as HttpURLConnection
            try {
                conn.requestMethod = "POST"
                conn.connectTimeout = 15_000
                // Image generation can take a while.
                conn.readTimeout = 240_000
                conn.doOutput = true
                conn.setRequestProperty("Authorization", "Bearer $key")
                conn.setRequestProperty("Content-Type", contentType)
                conn.outputStream.use { it.write(body) }
                val code = conn.responseCode
                val text = (if (code in 200..299) conn.inputStream else conn.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
                if (code !in 200..299) throw errorFor(code, text)
                text
            } finally {
                conn.disconnect()
            }
        }
    } catch (e: IOException) {
        throw LlmException("I can't reach OpenAI right now. Check the internet connection.", e)
    }

    companion object {
        const val BASE = "https://api.openai.com/v1"
        /** Fast GPT Image model; the list from the key replaces it if it isn't offered. */
        const val DEFAULT_MODEL = "gpt-image-2.6-flare"
        const val SQUARE = "1024x1024"
        const val TALL = "1024x1536"

        private val PREFERRED = listOf("gpt-image-2.6-flare", "gpt-image-2.6-sunburst", "gpt-image-2.5-sunburst", "gpt-image-2")

        /** GPT Image models, preferred first, then newest-looking first. */
        fun rank(ids: List<String>): List<String> = ids.distinct().filter { it.startsWith("gpt-image") }.sortedWith(
            compareBy<String> { id -> PREFERRED.indexOf(id).let { if (it < 0) PREFERRED.size else it } }.thenByDescending { it }
        )

        fun generationBody(model: String, prompt: String, size: String): JSONObject = JSONObject()
            .put("model", model)
            .put("prompt", prompt)
            .put("size", size)
            .put("quality", "medium")
            .put("n", 1)
            .put("output_format", "jpeg")

        /** multipart/form-data for the edits endpoint: the photo is the reference image. */
        fun editBody(boundary: String, model: String, prompt: String, size: String, photoJpeg: ByteArray): ByteArray {
            val out = ByteArrayOutputStream()
            fun field(name: String, value: String) {
                out.write("--$boundary\r\nContent-Disposition: form-data; name=\"$name\"\r\n\r\n$value\r\n".toByteArray())
            }
            field("model", model)
            field("prompt", prompt)
            field("size", size)
            field("quality", "medium")
            field("n", "1")
            field("output_format", "jpeg")
            out.write("--$boundary\r\nContent-Disposition: form-data; name=\"image\"; filename=\"photo.jpg\"\r\nContent-Type: image/jpeg\r\n\r\n".toByteArray())
            out.write(photoJpeg)
            out.write("\r\n--$boundary--\r\n".toByteArray())
            return out.toByteArray()
        }

        @Throws(LlmException::class)
        fun parseImage(raw: String): ByteArray {
            val first = try {
                JSONObject(raw).optJSONArray("data")?.optJSONObject(0)
            } catch (e: JSONException) {
                null
            } ?: throw LlmException("OpenAI didn't return a picture. Try again.")
            val b64 = first.optString("b64_json")
            if (b64.isBlank()) throw LlmException("OpenAI didn't return a picture. Try again.")
            return Base64.getDecoder().decode(b64)
        }

        fun errorFor(code: Int, body: String): LlmException {
            val err = try { JSONObject(body).optJSONObject("error") } catch (e: JSONException) { null }
            val message = err?.optString("message").orEmpty()
            val kind = (err?.optString("code").orEmpty() + " " + err?.optString("type").orEmpty()).lowercase()
            val short = message.lineSequence().firstOrNull().orEmpty().take(200)
            return when {
                code == 401 -> LlmException("The OpenAI API key was refused. Check it in Spark settings → Pictures.")
                "insufficient_quota" in kind || "billing" in kind ->
                    LlmException("Your OpenAI account has no credit for pictures. Add credit at platform.openai.com (Billing).")
                "moderation" in kind || "safety" in kind || "content_policy" in kind ->
                    LlmException("OpenAI's safety system declined that picture. Try a different photo or outfit.")
                code == 404 || "model_not_found" in kind -> ModelUnavailableException("OpenAI doesn't offer that image model to this key.")
                code == 429 || code >= 500 -> ProviderBusyException("OpenAI's image service is busy. Try again in a moment.")
                else -> LlmException("OpenAI had a problem making the picture ($code). $short".trim())
            }
        }
    }
}

/**
 * Picks who draws pictures: OpenAI when a key is set (best quality, keeps the
 * user's face), otherwise the free Pollinations service. If OpenAI is busy or
 * unreachable, the free service takes over; a safety refusal is not routed around.
 */
class ImageMaker(
    private val openAiKey: () -> String,
    private val openAi: ImageGen,
    private val free: ImageGen,
) : ImageGen {
    override val canUsePhoto: Boolean get() = openAiKey().isNotBlank()

    /** Which service is drawing right now, for the screen. */
    val label: String get() = if (openAiKey().isNotBlank()) "OpenAI" else "the free image service"

    override suspend fun generate(prompt: String, photoJpeg: ByteArray?): ByteArray {
        if (openAiKey().isBlank()) return free.generate(prompt, null)
        return try {
            openAi.generate(prompt, photoJpeg)
        } catch (e: ProviderBusyException) {
            // The free service can't use the photo: falling back would show someone else's face.
            if (photoJpeg != null) throw e
            free.generate(prompt, null)
        }
    }
}

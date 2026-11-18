package com.spark.companion.brain

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder
import kotlin.random.Random

/** Makes an avatar portrait from a prompt (and optionally a source photo). */
interface ImageGen {
    /** Whether this generator can restyle an existing photo (image-to-image). */
    val canUsePhoto: Boolean

    @Throws(LlmException::class)
    suspend fun generate(prompt: String, photoJpeg: ByteArray?): ByteArray
}

/** Gemini image generation: can restyle a photo or create from a description. */
class GeminiImageGen(
    private val apiKey: () -> String,
    model: () -> String,
    onModelSwitch: (String) -> Unit = {},
    /** e.g. "9:16" for full-length pictures; null for the model's default (square). */
    private val aspectRatio: String? = null,
) : ImageGen {
    override val canUsePhoto = true
    private val models = ModelSwitch({ model().trim().ifEmpty { GeminiMedia.DEFAULT_IMAGE_MODEL } }, onModelSwitch)

    override suspend fun generate(prompt: String, photoJpeg: ByteArray?): ByteArray {
        val body = GeminiMedia.imageRequest(prompt, photoJpeg, aspectRatio = aspectRatio).toString()
        val key = apiKey().trim()
        val reply = models.run({ ModelCatalog.pickGeminiImage(ModelCatalog.listGemini(key)) }) { m ->
            GeminiHttp.post(GeminiHttp.endpoint(m, GeminiMedia.DEFAULT_IMAGE_MODEL), key, body, 180_000)
        }
        return GeminiMedia.parseImage(reply)
    }
}

/**
 * Pollinations.ai image generation: completely free, no API key, no quota.
 * It's text-to-image only, so it creates from a description but can't restyle
 * a photo.
 */
class PollinationsImageGen(
    private val width: Int = WIDTH,
    private val height: Int = HEIGHT,
) : ImageGen {
    override val canUsePhoto = false

    override suspend fun generate(prompt: String, photoJpeg: ByteArray?): ByteArray =
        runInterruptible(Dispatchers.IO) {
            val conn = URI(url(prompt, width = width, height = height)).toURL().openConnection() as HttpURLConnection
            try {
                conn.connectTimeout = 15_000
                conn.readTimeout = 120_000
                conn.setRequestProperty("Accept", "image/*")
                val code = conn.responseCode
                if (code !in 200..299) {
                    throw LlmException(
                        when (code) {
                            429 -> "The free image service is busy right now. Wait a moment and try again."
                            else -> "The free image service had a problem ($code). Try again."
                        }
                    )
                }
                val bytes = conn.inputStream.use { input ->
                    val out = ByteArrayOutputStream()
                    val buf = ByteArray(16_384)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                    }
                    out.toByteArray()
                }
                if (bytes.size < 1000) throw LlmException("The free image service returned an empty image. Try again.")
                bytes
            } catch (e: LlmException) {
                throw e
            } catch (e: Exception) {
                throw LlmException("Couldn't reach the free image service. Check your internet and try again.", e)
            } finally {
                conn.disconnect()
            }
        }

    companion object {
        const val WIDTH = 768
        const val HEIGHT = 768

        fun url(prompt: String, seed: Int = Random.nextInt(1, 1_000_000), width: Int = WIDTH, height: Int = HEIGHT): String {
            val enc = URLEncoder.encode(prompt.take(1500), "UTF-8").replace("+", "%20")
            return "https://image.pollinations.ai/prompt/$enc" +
                "?width=$width&height=$height&nologo=true&model=flux&seed=$seed&private=true&referrer=spark-companion"
        }

        /** Full-length portrait pictures (9:16) for realistic bodies. */
        const val TALL_WIDTH = 720
        const val TALL_HEIGHT = 1280
    }

}

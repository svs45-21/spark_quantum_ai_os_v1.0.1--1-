package com.spark.companion.brain

import org.json.JSONArray
import org.json.JSONObject
import java.util.Base64

/**
 * Gemini image generation (turning a photo or a description into an avatar
 * portrait) and Gemini speech (natural, expressive voices). Pure JSON in and
 * bytes out, so it is unit-tested on the JVM.
 */
object GeminiMedia {

    const val DEFAULT_IMAGE_MODEL = "gemini-2.5-flash-image"
    const val DEFAULT_TTS_MODEL = "gemini-2.5-flash-preview-tts"

    /** Looks the avatar studio offers when converting or creating a portrait. */
    enum class ImageStyle(val label: String, val look: String) {
        REALISTIC("Realistic", "a high-quality realistic photographic portrait with natural skin texture and soft studio lighting"),
        ANIMATED_3D("3D animated", "a polished 3D animated-film character portrait with soft lighting and expressive, friendly features"),
        ANIME("Anime", "a clean anime-style character portrait with crisp line art and soft cel shading"),
        COMIC("Comic", "a bold comic-book style portrait with clean ink outlines and flat vibrant colours"),
        PAINTED("Painted", "a warm digital-painting portrait with visible soft brush strokes"),
    }

    /**
     * Framing rules that make a portrait easy to animate: front-facing, eyes
     * open, mouth closed, face centred.
     */
    private const val FRAMING =
        "Square image. Head-and-shoulders portrait, facing the camera straight on, " +
            "eyes open and looking at the viewer, mouth gently closed with a calm, friendly expression, " +
            "face centred and filling about half of the image height, both ears roughly symmetric, " +
            "even soft lighting on the face, simple plain dark background, no text, no logos, " +
            "no hands or objects in front of the face."

    fun convertPrompt(style: ImageStyle): String =
        "Recreate the person in this photo as ${style.look}. Keep their likeness: face shape, " +
            "skin tone, hairstyle and hair colour, eye colour and distinctive features. $FRAMING"

    fun describePrompt(description: String, style: ImageStyle): String =
        "Create ${style.look} of this character: ${description.trim()}. $FRAMING"

    fun imageRequest(prompt: String, image: ByteArray?, mimeType: String = "image/jpeg"): JSONObject {
        val parts = JSONArray()
        if (image != null) {
            parts.put(
                JSONObject().put(
                    "inlineData",
                    JSONObject().put("mimeType", mimeType).put("data", Base64.getEncoder().encodeToString(image))
                )
            )
        }
        parts.put(JSONObject().put("text", prompt))
        return JSONObject()
            .put("contents", JSONArray().put(JSONObject().put("role", "user").put("parts", parts)))
            .put("generationConfig", JSONObject().put("responseModalities", JSONArray().put("TEXT").put("IMAGE")))
    }

    /** Extracts the generated image bytes, or explains why there are none. */
    @Throws(LlmException::class)
    fun parseImage(reply: JSONObject): ByteArray {
        val candidate = reply.optJSONArray("candidates")?.optJSONObject(0)
            ?: throw LlmException(blockedMessage(reply, "Gemini didn't return an image."))
        val parts = candidate.optJSONObject("content")?.optJSONArray("parts") ?: JSONArray()
        var said = ""
        for (i in 0 until parts.length()) {
            val part = parts.optJSONObject(i) ?: continue
            val data = (part.optJSONObject("inlineData") ?: part.optJSONObject("inline_data"))
            if (data != null && data.optString("mimeType", data.optString("mime_type")).startsWith("image/")) {
                return Base64.getDecoder().decode(data.optString("data"))
            }
            if (part.has("text")) said += part.optString("text")
        }
        val reason = candidate.optString("finishReason")
        throw LlmException(
            when {
                reason == "SAFETY" || reason == "IMAGE_SAFETY" || reason == "PROHIBITED_CONTENT" ->
                    "Gemini declined to make that image. Try a different photo or description."
                said.isNotBlank() -> "Gemini didn't make an image: ${said.trim().take(200)}"
                else -> "Gemini didn't return an image. Try again."
            }
        )
    }

    // ── Speech ───────────────────────────────────────────────────────────

    /** Voices offered for avatars, with a plain description. */
    val VOICES = listOf(
        "Aoede" to "Breezy, female",
        "Kore" to "Firm, female",
        "Leda" to "Youthful, female",
        "Zephyr" to "Bright, female",
        "Puck" to "Upbeat, male",
        "Charon" to "Informative, male",
        "Fenrir" to "Excitable, male",
        "Orus" to "Firm, male",
    )

    /** A short delivery instruction so the voice carries the reply's emotion. */
    fun deliveryFor(mood: Mood?): String = when (mood) {
        Mood.HAPPY -> "Say cheerfully: "
        Mood.EXCITED -> "Say excitedly: "
        Mood.PLAYFUL -> "Say playfully: "
        Mood.LOVING -> "Say warmly and kindly: "
        Mood.CONCERNED -> "Say with gentle concern: "
        Mood.SAD -> "Say softly and sadly: "
        Mood.SURPRISED -> "Say with surprise: "
        Mood.THOUGHTFUL -> "Say thoughtfully: "
        Mood.CALM, null -> ""
    }

    fun speechRequest(text: String, voice: String, mood: Mood?): JSONObject =
        JSONObject()
            .put(
                "contents",
                JSONArray().put(JSONObject().put("parts", JSONArray().put(JSONObject().put("text", deliveryFor(mood) + text))))
            )
            .put(
                "generationConfig",
                JSONObject()
                    .put("responseModalities", JSONArray().put("AUDIO"))
                    .put(
                        "speechConfig",
                        JSONObject().put(
                            "voiceConfig",
                            JSONObject().put("prebuiltVoiceConfig", JSONObject().put("voiceName", voice.ifBlank { "Kore" }))
                        )
                    )
            )

    /** 16-bit little-endian mono PCM. */
    class Pcm(val data: ByteArray, val sampleRate: Int)

    @Throws(LlmException::class)
    fun parseSpeech(reply: JSONObject): Pcm {
        val parts = reply.optJSONArray("candidates")?.optJSONObject(0)
            ?.optJSONObject("content")?.optJSONArray("parts")
            ?: throw LlmException(blockedMessage(reply, "Gemini didn't return any audio."))
        val out = java.io.ByteArrayOutputStream()
        var rate = 24_000
        for (i in 0 until parts.length()) {
            val data = parts.optJSONObject(i)?.optJSONObject("inlineData") ?: continue
            val mime = data.optString("mimeType")
            Regex("rate=(\\d+)").find(mime)?.let { rate = it.groupValues[1].toInt() }
            out.write(Base64.getDecoder().decode(data.optString("data")))
        }
        if (out.size() == 0) throw LlmException("Gemini didn't return any audio.")
        return Pcm(out.toByteArray(), rate)
    }

    /** Loudness per [frameMs] window (0..1), for lip-sync. */
    fun levels(pcm: Pcm, frameMs: Int = 40): FloatArray {
        val samplesPerFrame = (pcm.sampleRate * frameMs / 1000).coerceAtLeast(1)
        val total = pcm.data.size / 2
        val frames = (total + samplesPerFrame - 1) / samplesPerFrame
        val out = FloatArray(frames)
        for (f in 0 until frames) {
            var sum = 0.0
            var n = 0
            var s = f * samplesPerFrame
            val end = minOf(total, s + samplesPerFrame)
            while (s < end) {
                val lo = pcm.data[2 * s].toInt() and 0xFF
                val hi = pcm.data[2 * s + 1].toInt()
                val v = (hi shl 8) or lo
                sum += v.toDouble() * v
                n++
                s++
            }
            val rms = if (n == 0) 0.0 else Math.sqrt(sum / n)
            out[f] = (rms / 6000.0).toFloat().coerceIn(0f, 1f)
        }
        return out
    }

    private fun blockedMessage(reply: JSONObject, otherwise: String): String {
        val block = reply.optJSONObject("promptFeedback")?.optString("blockReason").orEmpty()
        return if (block.isNotEmpty()) "Gemini declined that request ($block)." else otherwise
    }
}

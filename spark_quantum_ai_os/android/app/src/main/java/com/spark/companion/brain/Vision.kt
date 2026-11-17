package com.spark.companion.brain

import org.json.JSONArray
import org.json.JSONObject
import java.util.Base64

/** Asks Gemini about a photo: "what is this?", reading text, translating, etc. */
object Vision {

    /** Quick things the camera screen can ask about a photo. */
    enum class Task(val label: String, val prompt: String) {
        IDENTIFY("What is this?", "Look at this photo and tell me clearly what it shows. If it's an object, product, plant, animal or landmark, name it and give a sentence or two about it."),
        READ("Read the text", "Read out all the text in this image, exactly, in a natural order. If there's no text, say so."),
        TRANSLATE("Translate", "Translate any text in this image into the user's language. Give the translation, and briefly note the original language."),
        DESCRIBE("Describe scene", "Describe what's happening in this photo for someone who can't see it: the setting, people, objects and any notable details."),
        EXPLAIN("Explain / help", "Look at this photo and help the user with it. If it's a question, a form, a sign, a menu, a label or a screen, explain it and what they should do."),
    }

    fun request(question: String, imageJpeg: ByteArray, language: String): JSONObject {
        val sys = "You are Spark, looking through the user's phone camera. Answer in their language" +
            (if (language.isNotBlank()) " ($language)" else "") +
            ". Be brief and natural, as if speaking aloud; no markdown. Never claim to see something you can't make out — say if the photo is blurry or unclear."
        val parts = JSONArray()
            .put(JSONObject().put("inlineData", JSONObject().put("mimeType", "image/jpeg").put("data", Base64.getEncoder().encodeToString(imageJpeg))))
            .put(JSONObject().put("text", question))
        return JSONObject()
            .put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", sys))))
            .put("contents", JSONArray().put(JSONObject().put("role", "user").put("parts", parts)))
            .put("generationConfig", JSONObject().put("temperature", 0.4).put("maxOutputTokens", 1024))
    }

    @Throws(LlmException::class)
    suspend fun ask(question: String, imageJpeg: ByteArray, language: String, apiKey: () -> String, model: () -> String): String {
        val url = GeminiHttp.endpoint(model(), GeminiClient.DEFAULT_MODEL)
        val reply = GeminiHttp.post(url, apiKey().trim(), request(question, imageJpeg, language).toString(), 90_000)
        return GeminiClient.parseResponse(reply.toString()).text.ifBlank { "I couldn't make that out. Try again with a clearer, closer photo." }
    }
}

package com.spark.companion

import com.spark.companion.brain.GeminiMedia
import com.spark.companion.brain.Languages
import com.spark.companion.brain.LlmException
import com.spark.companion.brain.Mood
import com.spark.companion.brain.ReplyTags
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

class LanguagesTest {
    private val mine = listOf("en-IN", "hi-IN", "te-IN")

    @Test fun replyTagsCarryMoodAndLanguage() {
        assertEquals(ReplyTags.Tagged(Mood.HAPPY, "hi", "नमस्ते!"), ReplyTags.parse("[happy|hi] नमस्ते!"))
        assertEquals(ReplyTags.Tagged(Mood.CALM, "ta-IN", "Vanakkam"), ReplyTags.parse("[calm|ta_IN] Vanakkam"))
        assertEquals(ReplyTags.Tagged(Mood.SAD, null, "Oh."), ReplyTags.parse("[sad] Oh."))
        assertEquals(ReplyTags.Tagged(null, null, "Press [Send|x]"), ReplyTags.parse("Press [Send|x]"))
    }

    @Test fun resolvePrefersUserVariants() {
        assertEquals("hi-IN", Languages.resolve("hi", mine))
        assertEquals("en-IN", Languages.resolve("en", mine))
        assertEquals("ta-IN", Languages.resolve("ta", mine))
        assertEquals("fr-FR", Languages.resolve("fr", mine))
        assertEquals("en-US", Languages.resolve("en-US", mine))
        assertNull(Languages.resolve("not a code", mine))
        assertNull(Languages.resolve(null, mine))
    }

    @Test fun scriptGuess() {
        assertEquals("hi-IN", Languages.guessFromScript("आज मौसम बहुत अच्छा है", mine))
        assertEquals("mr-IN", Languages.guessFromScript("आज हवामान छान आहे", listOf("mr-IN")))
        assertEquals("te-IN", Languages.guessFromScript("ఈ రోజు వాతావరణం బాగుంది", mine))
        assertEquals("ja-JP", Languages.guessFromScript("今日はいい天気ですね", mine))
        assertEquals("zh-CN", Languages.guessFromScript("今天天气很好", mine))
        assertNull(Languages.guessFromScript("Aaj mausam accha hai", mine))
    }

    @Test fun parsesGeneratedImage() {
        val png = byteArrayOf(1, 2, 3, 4)
        val reply = JSONObject("""{"candidates":[{"content":{"parts":[{"text":"Here you go"},
            {"inlineData":{"mimeType":"image/png","data":"${Base64.getEncoder().encodeToString(png)}"}}]}}]}""")
        assertArrayEquals(png, GeminiMedia.parseImage(reply))
    }

    @Test(expected = LlmException::class)
    fun imageRefusalExplains() {
        GeminiMedia.parseImage(JSONObject("""{"candidates":[{"content":{"parts":[{"text":"I can't"}]},"finishReason":"STOP"}]}"""))
    }

    @Test fun imageRequestIncludesPhotoAndFraming() {
        val req = GeminiMedia.imageRequest(GeminiMedia.convertPrompt(GeminiMedia.ImageStyle.ANIME), byteArrayOf(9), "image/jpeg")
        val parts = req.getJSONArray("contents").getJSONObject(0).getJSONArray("parts")
        assertEquals("image/jpeg", parts.getJSONObject(0).getJSONObject("inlineData").getString("mimeType"))
        assertTrue(parts.getJSONObject(1).getString("text").contains("mouth gently closed"))
    }

    @Test fun parsesSpeechAndLevels() {
        // 0.1 s of silence then 0.1 s of loud square wave at 24 kHz.
        val samples = ShortArray(4800) { i -> if (i < 2400) 0 else if (i % 2 == 0) 12000 else -12000 }
        val bytes = ByteArray(samples.size * 2)
        samples.forEachIndexed { i, s -> bytes[2 * i] = (s.toInt() and 0xFF).toByte(); bytes[2 * i + 1] = (s.toInt() shr 8).toByte() }
        val reply = JSONObject("""{"candidates":[{"content":{"parts":[{"inlineData":{"mimeType":"audio/L16;codec=pcm;rate=24000",
            "data":"${Base64.getEncoder().encodeToString(bytes)}"}}]}}]}""")
        val pcm = GeminiMedia.parseSpeech(reply)
        assertEquals(24000, pcm.sampleRate)
        val levels = GeminiMedia.levels(pcm, 50)
        assertEquals(4, levels.size)
        assertEquals(0f, levels[0], 1e-6f)
        assertEquals(1f, levels[3], 1e-6f)
    }

    @Test fun speechRequestCarriesVoiceAndMood() {
        val req = GeminiMedia.speechRequest("Hello", "Puck", Mood.HAPPY)
        assertEquals("Say cheerfully: Hello", req.getJSONArray("contents").getJSONObject(0).getJSONArray("parts").getJSONObject(0).getString("text"))
        assertEquals("Puck", req.getJSONObject("generationConfig").getJSONObject("speechConfig")
            .getJSONObject("voiceConfig").getJSONObject("prebuiltVoiceConfig").getString("voiceName"))
    }
}

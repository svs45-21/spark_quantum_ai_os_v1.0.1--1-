package com.spark.companion

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
}

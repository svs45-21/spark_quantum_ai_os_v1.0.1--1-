package com.spark.companion

import com.spark.companion.brain.SpeechText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeechTextTest {
    @Test fun stripsMarkdown() {
        assertEquals("Hello there and friend", SpeechText.forSpeech("**Hello** there\n- and *friend*").replace("\n", " "))
        assertEquals("See docs", SpeechText.forSpeech("See [docs](https://x.y)"))
    }

    @Test fun chunksLongText() {
        val text = "This is a sentence. ".repeat(400)
        val chunks = SpeechText.chunks(text, 1000)
        assertTrue(chunks.all { it.length <= 1000 })
        assertEquals(text.replace(" ", ""), chunks.joinToString("").replace(" ", ""))
    }

    @Test fun wakeWord() {
        assertEquals("what's the time", SpeechText.afterWakeWord("hey Spark, what's the time", "spark"))
        assertEquals("", SpeechText.afterWakeWord("Spark", "spark"))
        assertNull(SpeechText.afterWakeWord("sparkling water please", "spark"))
        assertNull(SpeechText.afterWakeWord("hello there", "spark"))
    }
}

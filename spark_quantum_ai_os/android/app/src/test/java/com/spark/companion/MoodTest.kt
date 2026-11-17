package com.spark.companion

import com.spark.companion.brain.Greetings
import com.spark.companion.brain.Mood
import com.spark.companion.brain.SpeechText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class MoodTest {
    @Test fun splitsLeadingTag() {
        assertEquals(Mood.HAPPY to "Hello there!", Mood.split("[happy] Hello there!"))
        assertEquals(Mood.SAD to "Oh no.", Mood.split("  [ Sad ]Oh no."))
        assertEquals(Mood.CONCERNED to "Hmm.", Mood.split("[worried] Hmm."))
    }

    @Test fun noTag() {
        assertEquals(null to "Just text", Mood.split("Just text"))
    }

    @Test fun keepsOtherBracketsAndStripsStrayTags() {
        assertEquals(Mood.EXCITED to "Press [Send] now!", Mood.split("[excited] Press [Send] now! [happy]"))
    }

    @Test fun wakeWordVariants() {
        assertEquals("open maps", SpeechText.afterWakeWord("Sparky open maps", "spark"))
        assertEquals("", SpeechText.afterWakeWord("hey sparks!", "spark"))
        assertEquals("मौसम कैसा है", SpeechText.afterWakeWord("स्पार्क मौसम कैसा है", "spark"))
        assertNull(SpeechText.afterWakeWord("sparkling water", "spark"))
        assertNull(SpeechText.afterWakeWord("a spark-plug", "plug in"))
    }

    @Test fun greetingsUseName() {
        val all = (0 until 50).map { Greetings.pick("Shree", 9, Random(it)) }
        assertTrue(all.any { it.contains("Shree") })
        assertTrue(all.all { it.isNotBlank() })
        assertTrue(all.any { it.startsWith("Good morning") })
    }
}

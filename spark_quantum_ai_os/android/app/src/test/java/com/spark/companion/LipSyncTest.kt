package com.spark.companion

import com.spark.companion.brain.LipSync
import com.spark.companion.brain.Mood
import com.spark.companion.brain.MouthShape
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

class LipSyncTest {

    private fun tone(hz: Double, ms: Int, amp: Double, rate: Int = 24_000): ShortArray =
        ShortArray(rate * ms / 1000) { i -> (amp * sin(2 * PI * hz * i / rate)).toInt().toShort() }

    @Test fun silenceClosesTheMouth() {
        val shapes = LipSync.fromPcm(ShortArray(24_000 / 10), 24_000, 40)
        assertTrue(shapes.all { it == MouthShape.REST })
    }

    @Test fun lowVowelRoundsAndHissSpreads() {
        val rate = 24_000
        val vowel = tone(220.0, 200, 9000.0)
        val hiss = tone(5000.0, 200, 9000.0)
        val shapes = LipSync.fromPcm(vowel + hiss, rate, 40)
        val o = shapes[2]
        val s = shapes[shapes.size - 2]
        assertTrue("vowel opens the jaw: $o", o.open > 0.5f)
        assertTrue("low sound rounds the lips: $o", o.round > s.round)
        assertTrue("hiss spreads the lips: $s", s.wide > o.wide)
        assertTrue("hiss keeps the teeth close: $s", s.open < o.open)
    }

    @Test fun lettersMapToVisemes() {
        assertEquals(0f, LipSync.viseme('m').open, 0f)
        assertTrue(LipSync.viseme('o').round > 0.5f)
        assertTrue(LipSync.viseme('i').wide > 0.8f)
        assertTrue(LipSync.viseme('a').open > 0.8f)
        // Halfway between "m" and "a" the jaw is half open.
        val mid = LipSync.shapeInWord("ma", 90, 60f)
        assertTrue(mid.open in 0.3f..0.7f)
        assertEquals(MouthShape.REST, LipSync.shapeInWord("ma", 500, 60f))
    }

    @Test fun moodsUnderstandManyFeelingWords() {
        assertEquals(Mood.ANGRY, Mood.of("Frustrated"))
        assertEquals(Mood.SCARED, Mood.of("afraid"))
        assertEquals(Mood.DISGUSTED, Mood.of("disgusted"))
        assertEquals(Mood.CONFUSED, Mood.of("puzzled"))
        assertEquals(Mood.SLEEPY, Mood.of("tired"))
        assertTrue(Mood.values().size >= 20)
        assertTrue(Mood.PROMPT_LIST.contains("embarrassed"))
    }
}

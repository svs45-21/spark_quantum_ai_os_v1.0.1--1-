package com.spark.companion

import com.spark.companion.brain.SpeechText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WakeTimingTest {
    @Test fun quickEndMeansNoContinuousSupport() {
        assertTrue(SpeechText.continuousLooksUnsupported(4_000))
        assertFalse(SpeechText.continuousLooksUnsupported(10 * 60 * 1000L))
    }

    @Test fun fallbackBacksOffWhileSilent() {
        assertEquals(150L, SpeechText.wakeRestartDelayMs(0))
        assertEquals(150L, SpeechText.wakeRestartDelayMs(2))
        assertEquals(600L, SpeechText.wakeRestartDelayMs(3))
        assertEquals(1_200L, SpeechText.wakeRestartDelayMs(50))
    }

    @Test fun sessionIsLong() {
        assertTrue(SpeechText.WAKE_SESSION_MS >= 5 * 60 * 1000)
    }
}

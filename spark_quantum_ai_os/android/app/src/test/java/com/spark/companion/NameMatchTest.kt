package com.spark.companion

import com.spark.companion.phone.NameMatch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NameMatchTest {
    private val apps = listOf("WhatsApp", "WhatsApp Business", "Maps", "Google", "YouTube Music", "YouTube", "Camera")

    @Test fun picksBestApp() {
        assertEquals("WhatsApp", NameMatch.best("whatsapp", apps) { it })
        assertEquals("WhatsApp", NameMatch.best("whats app", apps) { it })
        assertEquals("YouTube", NameMatch.best("youtube", apps) { it })
        assertEquals("YouTube Music", NameMatch.best("youtube music", apps) { it })
        assertEquals("Maps", NameMatch.best("google maps app", apps) { it })
        assertEquals("Camera", NameMatch.best("cam", apps) { it })
        assertNull(NameMatch.best("spotify", apps) { it })
    }

    @Test fun numbers() {
        assertTrue(NameMatch.looksLikeNumber("+91 98765 43210"))
        assertTrue(NameMatch.looksLikeNumber("100"))
        assertFalse(NameMatch.looksLikeNumber("Mom"))
        assertEquals("+919876543210", NameMatch.digits("+91 98765-43210"))
    }
}

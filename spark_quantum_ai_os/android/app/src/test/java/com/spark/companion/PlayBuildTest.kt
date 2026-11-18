package com.spark.companion

import com.spark.companion.brain.Persona
import com.spark.companion.brain.ToolCatalog
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayBuildTest {
    private fun ctx(available: Boolean) = Persona.Context(
        userName = "Shree", now = "now", memories = emptyList(),
        screenControl = false, screenControlAvailable = available,
        desktopLink = false, voice = true,
    )

    @Test fun screenToolsAreRealTools() {
        assertTrue(ToolCatalog.names.containsAll(ToolCatalog.SCREEN_TOOLS))
        // Texting stays available in the Play build (it drafts instead of sending).
        assertFalse("send_sms" in ToolCatalog.SCREEN_TOOLS)
    }

    @Test fun playBuildPersonaDoesNotPointToMissingSetting() {
        val play = Persona.systemPrompt(ctx(available = false))
        assertTrue(play.contains("can't operate other apps' screens"))
        assertFalse(play.contains("Spark phone control"))
        val full = Persona.systemPrompt(ctx(available = true))
        assertTrue(full.contains("Spark phone control"))
    }
}

package com.spark.companion

import com.anthropic.core.jsonMapper
import com.spark.companion.brain.ClaudeClient
import com.spark.companion.brain.Vision
import org.junit.Assert.assertTrue
import org.junit.Test

class VisionTest {
    @Test fun sendsThePhotoThenTheQuestion() {
        val wire = jsonMapper().writeValueAsString(ClaudeClient.visionMessage("What is this?", byteArrayOf(1, 2, 3)))
        assertTrue(wire, wire.indexOf("\"type\":\"image\"") in 0 until wire.indexOf("What is this?"))
        assertTrue(wire.contains("\"media_type\":\"image/jpeg\""))
        assertTrue(wire.contains("\"data\":\"AQID\""))
    }

    @Test fun answersInTheUsersLanguage() {
        assertTrue(Vision.systemPrompt("hi-IN").contains("hi-IN"))
    }

    @Test fun tasksHavePrompts() {
        assertTrue(Vision.Task.values().all { it.prompt.isNotBlank() && it.label.isNotBlank() })
        assertTrue(Vision.Task.READ.prompt.contains("text", ignoreCase = true))
    }
}

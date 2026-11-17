package com.spark.companion

import com.spark.companion.brain.Vision
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VisionTest {
    @Test fun buildsImagePlusTextRequest() {
        val req = Vision.request("What is this?", byteArrayOf(1, 2, 3), "hi-IN")
        val parts = req.getJSONArray("contents").getJSONObject(0).getJSONArray("parts")
        assertTrue(parts.getJSONObject(0).getJSONObject("inlineData").getString("mimeType") == "image/jpeg")
        assertEquals("What is this?", parts.getJSONObject(1).getString("text"))
        assertTrue(req.getJSONObject("systemInstruction").toString().contains("hi-IN"))
    }

    @Test fun tasksHavePrompts() {
        assertTrue(Vision.Task.values().all { it.prompt.isNotBlank() && it.label.isNotBlank() })
        assertTrue(Vision.Task.READ.prompt.contains("text", ignoreCase = true))
    }
}

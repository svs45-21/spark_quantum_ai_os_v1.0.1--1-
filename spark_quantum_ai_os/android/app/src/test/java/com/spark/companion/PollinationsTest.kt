package com.spark.companion

import com.spark.companion.brain.PollinationsImageGen
import org.junit.Assert.assertTrue
import org.junit.Test

class PollinationsTest {
    @Test fun buildsAKeylessImageUrl() {
        val url = PollinationsImageGen.url("a friendly woman with a yellow kurta", seed = 42)
        assertTrue(url.startsWith("https://image.pollinations.ai/prompt/"))
        assertTrue(url.contains("a%20friendly%20woman"))
        assertTrue(url.contains("seed=42"))
        assertTrue(url.contains("width=768"))
        // No API key anywhere in the request.
        assertTrue(!url.contains("key="))
    }
}

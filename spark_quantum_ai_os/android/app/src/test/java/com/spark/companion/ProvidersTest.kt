package com.spark.companion

import com.spark.companion.brain.N8n
import com.spark.companion.brain.OpenAiCompatClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProvidersTest {
    @Test fun ollamaAndCustomEndpoints() {
        assertEquals("http://host:11434/v1/chat/completions", OpenAiCompatClient.chatEndpoint("http://host:11434"))
        assertEquals("http://host:11434/v1/chat/completions", OpenAiCompatClient.chatEndpoint("http://host:11434/"))
        assertEquals("http://host:1234/v1/chat/completions", OpenAiCompatClient.chatEndpoint("http://host:1234/v1"))
        // A full endpoint is left as-is.
        assertEquals("http://host/v1/chat/completions", OpenAiCompatClient.chatEndpoint("http://host/v1/chat/completions"))
        assertEquals("", OpenAiCompatClient.chatEndpoint("  "))
    }

    @Test fun n8nWebhookUrls() {
        val n8n = N8n({ "http://host:5678" }, { "" })
        assertTrue(n8n.configured)
        assertEquals("http://host:5678/webhook/send-email", n8n.webhookUrl("send-email"))
        assertEquals("http://host:5678/webhook/send-email", n8n.webhookUrl("/send-email"))
        // A base that already includes /webhook isn't doubled.
        assertEquals("http://host:5678/webhook/x", N8n({ "http://host:5678/webhook" }, { "" }).webhookUrl("x"))
        // A full URL passes through.
        assertEquals("https://n8n.me/webhook/abc", n8n.webhookUrl("https://n8n.me/webhook/abc"))
    }

    @Test fun n8nNotConfiguredWhenBlank() {
        assertFalse(N8n({ "" }, { "" }).configured)
    }

    @Test fun providerListIncludesLocalOptions() {
        val keys = com.spark.companion.brain.RoutingLlm.PROVIDERS.map { it.first }
        assertTrue(keys.contains("ollama"))
        assertTrue(keys.contains("custom"))
    }
}

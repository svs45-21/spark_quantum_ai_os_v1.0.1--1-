package com.spark.companion.brain

import kotlin.random.Random

/** What Spark says the moment it wakes up, before you've asked anything. */
object Greetings {
    fun pick(name: String, hour: Int, language: String = "en", random: Random = Random.Default): String {
        val n = name.trim()
        if (language.startsWith("hi", ignoreCase = true)) {
            val ji = if (n.isEmpty()) "" else " $n"
            val hindi = listOf("हाँ$ji, बताइए?", "मैं यहाँ हूँ!", "नमस्ते$ji! क्या करना है?", "जी, बोलिए?")
            return hindi[random.nextInt(hindi.size)]
        }
        val withName = if (n.isEmpty()) "" else ", $n"
        val options = mutableListOf(
            "Yes$withName?",
            "I'm here$withName.",
            "Hey$withName! What's up?",
            "Mm-hm? I'm listening.",
            "I'm awake! What can I do for you?",
        )
        when (hour) {
            in 5..11 -> options += "Good morning$withName!"
            in 12..16 -> options += "Good afternoon$withName!"
            in 17..21 -> options += "Good evening$withName!"
            else -> options += "Up late$withName? I'm here."
        }
        return options[random.nextInt(options.size)]
    }

    /** A sample sentence for testing a voice. */
    fun sample(name: String, language: String): String {
        val n = name.trim().ifEmpty { "there" }
        return when (language.substringBefore('-').lowercase()) {
            "hi" -> "नमस्ते $n! मैं स्पार्क हूँ। आज मैं आपकी क्या मदद करूँ?"
            "te" -> "నమస్కారం $n! నేను స్పార్క్. ఈ రోజు మీకు ఎలా సహాయం చేయగలను?"
            "ta" -> "வணக்கம் $n! நான் ஸ்பார்க். இன்று உங்களுக்கு எப்படி உதவலாம்?"
            "bn" -> "নমস্কার $n! আমি স্পার্ক। আজ আপনাকে কীভাবে সাহায্য করতে পারি?"
            "mr" -> "नमस्कार $n! मी स्पार्क. आज मी तुमची कशी मदत करू?"
            "es" -> "¡Hola $n! Soy Spark. ¿En qué te ayudo hoy?"
            "fr" -> "Bonjour $n ! Je suis Spark. Comment puis-je t'aider aujourd'hui ?"
            "de" -> "Hallo $n! Ich bin Spark. Wie kann ich dir heute helfen?"
            else -> "Hi $n! I'm Spark. What can I do for you today?"
        }
    }
}

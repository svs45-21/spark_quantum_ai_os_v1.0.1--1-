package com.spark.companion.brain

import kotlin.random.Random

/** What Spark says the moment it wakes up, before you've asked anything. */
object Greetings {
    fun pick(name: String, hour: Int, random: Random = Random.Default): String {
        val n = name.trim()
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
}

package com.spark.companion.brain

/**
 * The feeling behind a reply. The model starts each reply with a tag such as
 * "[happy]"; the tag drives the avatar's face and is never shown or spoken.
 */
enum class Mood(val tag: String) {
    CALM("calm"),
    HAPPY("happy"),
    EXCITED("excited"),
    PLAYFUL("playful"),
    LOVING("loving"),
    CONCERNED("concerned"),
    SAD("sad"),
    SURPRISED("surprised"),
    THOUGHTFUL("thoughtful");

    companion object {
        private val BY_TAG = values().associateBy { it.tag }
        private val ALIASES = mapOf(
            "neutral" to CALM, "joyful" to HAPPY, "cheerful" to HAPPY, "glad" to HAPPY,
            "worried" to CONCERNED, "caring" to LOVING, "warm" to LOVING, "curious" to THOUGHTFUL,
            "shocked" to SURPRISED, "amazed" to SURPRISED, "teasing" to PLAYFUL, "proud" to HAPPY,
        )
        private val ANY_TAG = Regex("\\[\\s*([A-Za-z]+)\\s*]\\s*")

        fun of(word: String): Mood? = word.lowercase().let { BY_TAG[it] ?: ALIASES[it] }

        /**
         * Splits "[happy] Hello!" into (HAPPY, "Hello!"). Removes every mood
         * tag in the text, and leaves other bracketed text alone. The mood is
         * null when the reply has no recognised tag.
         */
        fun split(text: String): Pair<Mood?, String> {
            var mood: Mood? = null
            val clean = ANY_TAG.replace(text) { m ->
                val found = of(m.groupValues[1])
                if (found != null) {
                    if (mood == null) mood = found
                    ""
                } else {
                    m.value
                }
            }
            return mood to clean.trim()
        }
    }
}

package com.spark.companion.brain

/**
 * The feeling behind a reply. The model starts each reply with a tag such as
 * "[happy]"; the tag drives the avatar's face, body language and voice, and
 * is never shown or spoken.
 */
enum class Mood(val tag: String) {
    CALM("calm"),
    HAPPY("happy"),
    EXCITED("excited"),
    PLAYFUL("playful"),
    LOVING("loving"),
    GRATEFUL("grateful"),
    PROUD("proud"),
    CONFIDENT("confident"),
    RELIEVED("relieved"),
    CONCERNED("concerned"),
    SAD("sad"),
    ANGRY("angry"),
    SCARED("scared"),
    DISGUSTED("disgusted"),
    SURPRISED("surprised"),
    CONFUSED("confused"),
    THOUGHTFUL("thoughtful"),
    SHY("shy"),
    EMBARRASSED("embarrassed"),
    NERVOUS("nervous"),
    BORED("bored"),
    SLEEPY("sleepy");

    companion object {
        private val BY_TAG = values().associateBy { it.tag }
        private val ALIASES = mapOf(
            "neutral" to CALM, "relaxed" to CALM, "peaceful" to CALM,
            "joyful" to HAPPY, "cheerful" to HAPPY, "glad" to HAPPY, "content" to HAPPY,
            "thrilled" to EXCITED, "ecstatic" to EXCITED, "enthusiastic" to EXCITED,
            "teasing" to PLAYFUL, "mischievous" to PLAYFUL, "cheeky" to PLAYFUL, "funny" to PLAYFUL,
            "caring" to LOVING, "warm" to LOVING, "affectionate" to LOVING, "tender" to LOVING,
            "thankful" to GRATEFUL, "appreciative" to GRATEFUL,
            "accomplished" to PROUD, "determined" to CONFIDENT, "sure" to CONFIDENT, "assertive" to CONFIDENT,
            "worried" to CONCERNED, "sympathetic" to CONCERNED, "anxious" to NERVOUS, "tense" to NERVOUS,
            "unhappy" to SAD, "sorry" to SAD, "disappointed" to SAD, "lonely" to SAD, "hurt" to SAD,
            "mad" to ANGRY, "annoyed" to ANGRY, "frustrated" to ANGRY, "furious" to ANGRY, "irritated" to ANGRY,
            "afraid" to SCARED, "fearful" to SCARED, "fear" to SCARED, "terrified" to SCARED, "frightened" to SCARED,
            "disgust" to DISGUSTED, "grossed" to DISGUSTED, "repulsed" to DISGUSTED,
            "shocked" to SURPRISED, "amazed" to SURPRISED, "astonished" to SURPRISED, "wow" to SURPRISED,
            "puzzled" to CONFUSED, "perplexed" to CONFUSED, "unsure" to CONFUSED,
            "curious" to THOUGHTFUL, "pensive" to THOUGHTFUL, "reflective" to THOUGHTFUL, "serious" to THOUGHTFUL,
            "bashful" to SHY, "timid" to SHY, "flustered" to EMBARRASSED, "awkward" to EMBARRASSED,
            "uninterested" to BORED, "tired" to SLEEPY, "drowsy" to SLEEPY, "exhausted" to SLEEPY,
        )
        fun of(word: String): Mood? = word.lowercase().let { BY_TAG[it] ?: ALIASES[it] }

        /** The list the model picks from, for the system prompt. */
        val PROMPT_LIST: String = values().joinToString(", ") { it.tag }

        /** Splits "[happy] Hello!" into (HAPPY, "Hello!"); see [ReplyTags]. */
        fun split(text: String): Pair<Mood?, String> = ReplyTags.parse(text).let { it.mood to it.text }
    }
}

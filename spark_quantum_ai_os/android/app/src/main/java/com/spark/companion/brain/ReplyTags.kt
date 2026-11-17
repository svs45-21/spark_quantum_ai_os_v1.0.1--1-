package com.spark.companion.brain

/**
 * The model starts each reply with a tag like "[happy|hi]": the mood sets the
 * avatar's face and the language picks the voice. Tags are never shown or
 * spoken.
 */
object ReplyTags {

    data class Tagged(val mood: Mood?, val language: String?, val text: String)

    private val TAG = Regex("\\[\\s*([A-Za-z]+)\\s*(?:[|,/:;]\\s*([A-Za-z]{2,3}(?:[-_][A-Za-z0-9]{2,8})?)\\s*)?]\\s*")

    fun parse(text: String): Tagged {
        var mood: Mood? = null
        var language: String? = null
        val clean = TAG.replace(text) { m ->
            val found = Mood.of(m.groupValues[1])
            if (found != null) {
                if (mood == null) {
                    mood = found
                    language = m.groupValues[2].ifEmpty { null }?.replace('_', '-')
                }
                ""
            } else {
                m.value
            }
        }
        return Tagged(mood, language, clean.trim())
    }
}

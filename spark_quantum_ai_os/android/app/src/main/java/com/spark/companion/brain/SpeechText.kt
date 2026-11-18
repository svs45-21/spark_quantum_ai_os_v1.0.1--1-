package com.spark.companion.brain

/** Text clean-up for speech output and wake-word handling. Pure logic. */
object SpeechText {

    private val CODE_BLOCK = Regex("```[\\s\\S]*?```")
    private val LINK = Regex("\\[([^\\]]+)]\\([^)]*\\)")
    private val URL = Regex("https?://\\S+")
    private val LIST_MARK = Regex("(?m)^\\s*([-*•]|\\d+[.)])\\s+")
    private val HEADING = Regex("(?m)^#{1,6}\\s*")
    private val EMPHASIS = Regex("[*_`~]+")
    private val SPACES = Regex("[ \\t]+")

    /** Removes markdown and other things that sound wrong when read aloud. */
    fun forSpeech(text: String): String = text
        .replace(CODE_BLOCK, " (code omitted) ")
        .replace(LINK, "$1")
        .replace(URL, "a link")
        .replace(HEADING, "")
        .replace(LIST_MARK, "")
        .replace(EMPHASIS, "")
        .replace(SPACES, " ")
        .trim()

    /** Splits long text into chunks a TTS engine will accept, at sentence ends. */
    fun chunks(text: String, max: Int = 3000): List<String> {
        if (text.length <= max) return if (text.isBlank()) emptyList() else listOf(text)
        val out = ArrayList<String>()
        var rest = text
        while (rest.length > max) {
            var cut = -1
            for (mark in listOf(". ", "? ", "! ", "\n", ", ", " ")) {
                cut = rest.lastIndexOf(mark, max)
                if (cut > max / 2) {
                    cut += mark.length
                    break
                }
                cut = -1
            }
            if (cut <= 0) cut = max
            out.add(rest.substring(0, cut).trim())
            rest = rest.substring(cut)
        }
        if (rest.isNotBlank()) out.add(rest.trim())
        return out
    }

    /**
     * Looks for the wake word in something overheard. Returns null if it isn't
     * there, otherwise whatever was said after it ("" if nothing).
     */
    fun afterWakeWord(heard: String, wakeWord: String): String? {
        val word = wakeWord.trim().lowercase()
        if (word.isEmpty()) return null
        // Recognisers often hear "sparks" / "sparky", or write the word in Devanagari.
        val forms = (listOf(Regex.escape(word) + "(?:s|y|ie|ey)?") +
            (EXTRA_FORMS[word] ?: emptyList()).map { Regex.escape(it) }).joinToString("|")
        val pattern = Regex(
            "(?<![\\p{L}\\p{M}])(?:(?:hey|ok|okay|hi|hello|oye|arre)[ ,]+)?(?:$forms)(?![\\p{L}\\p{M}])[,.!?।]?",
            RegexOption.IGNORE_CASE
        )
        val match = pattern.find(heard) ?: return null
        return heard.substring(match.range.last + 1).trim().trimStart(',', '.', '!', '?', '।').trim()
    }

    private val EXTRA_FORMS = mapOf("spark" to listOf("स्पार्क", "स्पार्की"))

    /** How long one continuous wake-word session asks the recogniser to stay open. */
    const val WAKE_SESSION_MS = 10 * 60 * 1000

    /**
     * A recogniser that ignores the continuous-session request ends at the
     * first pause, within seconds. Ending that fast means the phone doesn't
     * support it, so fall back to the restart loop.
     */
    fun continuousLooksUnsupported(elapsedMs: Long): Boolean = elapsedMs < 8_000

    /**
     * Pause before restarting the fallback loop after [silentRounds] rounds in
     * a row with nothing heard. Short at first so "Spark" isn't missed, then
     * longer so the mic doesn't blink on and off every couple of seconds.
     */
    fun wakeRestartDelayMs(silentRounds: Int): Long = when {
        silentRounds < 3 -> 150L
        silentRounds < 10 -> 600L
        else -> 1_200L
    }
}

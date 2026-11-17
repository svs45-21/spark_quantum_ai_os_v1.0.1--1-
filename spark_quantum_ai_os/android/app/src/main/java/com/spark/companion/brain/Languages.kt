package com.spark.companion.brain

/** Languages Spark can listen and speak in, and helpers to pick the right one. */
object Languages {

    data class Language(val tag: String, val english: String, val native: String)

    val ALL = listOf(
        Language("en-IN", "English (India)", "English"),
        Language("en-US", "English (US)", "English"),
        Language("en-GB", "English (UK)", "English"),
        Language("hi-IN", "Hindi", "हिन्दी"),
        Language("bn-IN", "Bengali", "বাংলা"),
        Language("te-IN", "Telugu", "తెలుగు"),
        Language("ta-IN", "Tamil", "தமிழ்"),
        Language("mr-IN", "Marathi", "मराठी"),
        Language("gu-IN", "Gujarati", "ગુજરાતી"),
        Language("kn-IN", "Kannada", "ಕನ್ನಡ"),
        Language("ml-IN", "Malayalam", "മലയാളം"),
        Language("pa-IN", "Punjabi", "ਪੰਜਾਬੀ"),
        Language("or-IN", "Odia", "ଓଡ଼ିଆ"),
        Language("ur-IN", "Urdu", "اردو"),
        Language("ne-NP", "Nepali", "नेपाली"),
        Language("es-ES", "Spanish", "Español"),
        Language("fr-FR", "French", "Français"),
        Language("de-DE", "German", "Deutsch"),
        Language("it-IT", "Italian", "Italiano"),
        Language("pt-BR", "Portuguese", "Português"),
        Language("ru-RU", "Russian", "Русский"),
        Language("ar-SA", "Arabic", "العربية"),
        Language("tr-TR", "Turkish", "Türkçe"),
        Language("id-ID", "Indonesian", "Bahasa Indonesia"),
        Language("ja-JP", "Japanese", "日本語"),
        Language("ko-KR", "Korean", "한국어"),
        Language("zh-CN", "Chinese", "中文"),
    )

    private val BY_PREFIX = ALL.groupBy { it.tag.substringBefore('-').lowercase() }

    fun name(tag: String): String =
        ALL.firstOrNull { it.tag.equals(tag, true) }?.english
            ?: BY_PREFIX[tag.substringBefore('-').lowercase()]?.first()?.english
            ?: tag

    /**
     * Turns a code the model gave ("hi", "hi-IN", "HI_in") into a full tag,
     * preferring the user's own variants (their "en-IN" over "en-US").
     */
    fun resolve(code: String?, userLanguages: List<String>): String? {
        val clean = code?.trim()?.replace('_', '-')?.takeIf { Regex("[A-Za-z]{2,3}(-[A-Za-z0-9]{2,8})?").matches(it) }
            ?: return null
        val prefix = clean.substringBefore('-').lowercase()
        userLanguages.firstOrNull { it.equals(clean, true) }?.let { return it }
        if (!clean.contains('-')) {
            userLanguages.firstOrNull { it.substringBefore('-').equals(prefix, true) }?.let { return it }
            BY_PREFIX[prefix]?.first()?.let { return it.tag }
        }
        return clean
    }

    /** Scripts that identify a language family, with candidates in preference order. */
    private val SCRIPT_LANGS: Map<Character.UnicodeScript, List<String>> = mapOf(
        Character.UnicodeScript.DEVANAGARI to listOf("hi", "mr", "ne"),
        Character.UnicodeScript.BENGALI to listOf("bn"),
        Character.UnicodeScript.GURMUKHI to listOf("pa"),
        Character.UnicodeScript.GUJARATI to listOf("gu"),
        Character.UnicodeScript.ORIYA to listOf("or"),
        Character.UnicodeScript.TAMIL to listOf("ta"),
        Character.UnicodeScript.TELUGU to listOf("te"),
        Character.UnicodeScript.KANNADA to listOf("kn"),
        Character.UnicodeScript.MALAYALAM to listOf("ml"),
        Character.UnicodeScript.ARABIC to listOf("ar", "ur"),
        Character.UnicodeScript.CYRILLIC to listOf("ru"),
        Character.UnicodeScript.HANGUL to listOf("ko"),
        Character.UnicodeScript.HIRAGANA to listOf("ja"),
        Character.UnicodeScript.KATAKANA to listOf("ja"),
        Character.UnicodeScript.HAN to listOf("zh"),
        Character.UnicodeScript.THAI to listOf("th"),
        Character.UnicodeScript.GREEK to listOf("el"),
        Character.UnicodeScript.HEBREW to listOf("he"),
    )

    /**
     * Guesses the language from the writing system. Returns null for Latin
     * text (English, Spanish, Hinglish… can't be told apart by script).
     */
    fun guessFromScript(text: String, userLanguages: List<String>): String? {
        val counts = HashMap<Character.UnicodeScript, Int>()
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            if (Character.isLetter(cp)) {
                val script = Character.UnicodeScript.of(cp)
                counts[script] = (counts[script] ?: 0) + 1
            }
            i += Character.charCount(cp)
        }
        // Japanese mixes kana with Han characters.
        if ((counts[Character.UnicodeScript.HIRAGANA] ?: 0) + (counts[Character.UnicodeScript.KATAKANA] ?: 0) > 0) {
            return resolve("ja", userLanguages)
        }
        val top = counts.entries.filter { it.key != Character.UnicodeScript.LATIN && it.key in SCRIPT_LANGS }
            .maxByOrNull { it.value } ?: return null
        val latin = counts[Character.UnicodeScript.LATIN] ?: 0
        if (top.value < latin) return null
        val candidates = SCRIPT_LANGS.getValue(top.key)
        val mine = candidates.firstOrNull { c -> userLanguages.any { it.substringBefore('-').equals(c, true) } }
        return resolve(mine ?: candidates.first(), userLanguages)
    }

    fun parseList(csv: String): List<String> =
        csv.split(',').map { it.trim() }.filter { it.isNotEmpty() }.distinct()
}

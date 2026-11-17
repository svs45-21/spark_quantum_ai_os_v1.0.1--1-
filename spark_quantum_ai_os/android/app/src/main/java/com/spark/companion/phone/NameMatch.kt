package com.spark.companion.phone

/** Fuzzy matching of spoken names against app labels / contact names. Pure logic. */
object NameMatch {

    private fun norm(s: String): String =
        s.lowercase().replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()

    /**
     * Scores how well [candidate] matches what the user said. 0 = no match;
     * higher is better. Exact > starts-with > whole-word > contains > squashed.
     */
    fun score(query: String, candidate: String): Int {
        val q = norm(query)
        val c = norm(candidate)
        if (q.isEmpty() || c.isEmpty()) return 0
        if (q == c) return 100
        val qs = q.replace(" ", "")
        val cs = c.replace(" ", "")
        if (qs == cs) return 95
        if (c.startsWith("$q ")) return 85
        if (c.split(" ").contains(q)) return 80
        if (c.startsWith(q)) return 75
        if (c.contains(" $q ") || c.endsWith(" $q")) return 70
        if (q.split(" ").all { word -> c.split(" ").any { it.startsWith(word) } }) return 65
        if (c.contains(q)) return 55
        if (cs.contains(qs) && qs.length >= 4) return 50
        // The user said more than the label ("google maps app" → "Maps").
        if (q.split(" ").contains(c) && c.length >= 3) return 45
        return 0
    }

    /** Best match among [items], or null if nothing scores. Ties go to the shorter name. */
    fun <T> best(query: String, items: List<T>, name: (T) -> String): T? =
        items.map { it to score(query, name(it)) }
            .filter { it.second > 0 }
            .sortedWith(compareByDescending<Pair<T, Int>> { it.second }.thenBy { name(it.first).length })
            .firstOrNull()?.first

    /** True if the string looks like a phone number rather than a name. */
    fun looksLikeNumber(s: String): Boolean {
        val digits = s.count { it.isDigit() }
        return digits >= 3 && s.all { it.isDigit() || it in "+-() .#*" }
    }

    /** Digits only (keeping a leading +), for building wa.me / tel: links. */
    fun digits(s: String): String {
        val t = s.trim()
        val d = t.filter { it.isDigit() }
        return if (t.startsWith("+")) "+$d" else d
    }
}

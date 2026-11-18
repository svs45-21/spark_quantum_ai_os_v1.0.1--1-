package com.spark.companion.brain

/** Orders the Claude models a key can use, best first, for the model drop-down and failover. */
object ModelCatalog {

    /** Anthropic's current lineup, in the order Spark prefers them. */
    val PREFERRED = listOf(
        "claude-opus-5-5",
        "claude-sonnet-5-5",
        "claude-opus-5",
        "claude-sonnet-5",
        "claude-haiku-5-5",
        "claude-fable-5-2",
        "claude-opus-4-8",
        "claude-sonnet-4-6",
        "claude-haiku-4-5",
    )

    /** Claude chat models, preferred ones first, then the rest newest-looking first. */
    fun claudeModels(ids: List<String>): List<String> {
        val chat = ids.distinct().filter { it.startsWith("claude") }
        return chat.sortedWith(
            compareBy<String> { id -> PREFERRED.indexOf(id).let { if (it < 0) PREFERRED.size else it } }
                .thenByDescending { it }
        )
    }
}

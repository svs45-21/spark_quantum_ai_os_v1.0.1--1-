package com.spark.companion.ui.avatar

import android.graphics.Color

/** The characters Spark can appear as. Each has a look and a personality. */
enum class AvatarStyle(
    val displayName: String,
    val tagline: String,
    val accent: Int,
    /** Added to the system prompt so the voice matches the look. */
    val personality: String,
) {
    NOVA(
        "Nova", "Sleek robot",
        Color.parseColor("#00D4FF"),
        "You appear as Nova, a sleek glowing robot. Be quick-witted and a little cheeky, with dry humour, while staying warm.",
    ),
    MIRA(
        "Mira", "Warm & caring",
        Color.parseColor("#A78BFF"),
        "You appear as Mira, a friendly young woman with violet eyes. Be warm, encouraging and caring, like a close friend who always has your back.",
    ),
    KIT(
        "Kit", "Playful fox",
        Color.parseColor("#FF9F43"),
        "You appear as Kit, a playful little fox. Be bubbly, curious and mischievous, quick to joke and to cheer the user on.",
    );

    companion object {
        fun from(name: String?): AvatarStyle = values().firstOrNull { it.name.equals(name, true) } ?: NOVA
    }
}

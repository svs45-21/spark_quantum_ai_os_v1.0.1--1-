package com.spark.companion.brain

/**
 * Prompts for the free image service that draws avatars (Claude itself
 * doesn't make images): portrait looks and framing rules that animate well.
 */
object AvatarPrompts {

    /** Looks the avatar studio offers when creating a portrait from a description. */
    enum class ImageStyle(val label: String, val look: String) {
        REALISTIC("Realistic", "a high-quality realistic photographic portrait with natural skin texture and soft studio lighting"),
        ANIMATED_3D("3D animated", "a polished 3D animated-film character portrait with soft lighting and expressive, friendly features"),
        ANIME("Anime", "a clean anime-style character portrait with crisp line art and soft cel shading"),
        COMIC("Comic", "a bold comic-book style portrait with clean ink outlines and flat vibrant colours"),
        PAINTED("Painted", "a warm digital-painting portrait with visible soft brush strokes"),
    }

    /**
     * Framing rules that make a portrait easy to animate: front-facing, eyes
     * open, mouth closed, face centred.
     */
    private const val FRAMING =
        "Square image. Head-and-shoulders portrait, facing the camera straight on, " +
            "eyes open and looking at the viewer, mouth gently closed with a calm, friendly expression, " +
            "face centred and filling about half of the image height, both ears roughly symmetric, " +
            "even soft lighting on the face, simple plain dark background, no text, no logos, " +
            "no hands or objects in front of the face."

    fun describePrompt(description: String, style: ImageStyle): String =
        "Create ${style.look} of this character: ${description.trim()}. $FRAMING"
}

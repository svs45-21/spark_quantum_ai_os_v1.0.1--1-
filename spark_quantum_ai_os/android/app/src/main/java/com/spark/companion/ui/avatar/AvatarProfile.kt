package com.spark.companion.ui.avatar

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import com.spark.companion.brain.GeminiMedia
import org.json.JSONObject
import java.io.File
import java.util.UUID

/** How an avatar sounds: phone-voice pitch and rate, and its Gemini voice. */
data class VoiceStyle(val pitch: Float, val rate: Float, val geminiVoice: String)

/**
 * One avatar Spark can appear as: a built-in character or one the user made
 * from a photo or a description.
 */
data class AvatarProfile(
    val id: String,
    val name: String,
    val tagline: String,
    val accent: Int,
    val personality: String,
    val voice: VoiceStyle,
    val builtin: AvatarStyle? = null,
    val portrait: File? = null,
    val landmarks: FaceLandmarks? = null,
    /** Set for full-body avatars: body type, outfit, hair and skin. */
    val body: BodySpec? = null,
) {
    val custom: Boolean get() = builtin == null

    /** Line added to the system prompt. */
    val prompt: String
        get() = builtin?.personality
            ?: "You appear as $name, shown as an animated portrait. " +
            personality.ifBlank { "Be warm, natural and attentive, like a close friend." }

    companion object {
        fun of(style: AvatarStyle) = AvatarProfile(
            id = "builtin:" + style.name,
            name = style.displayName,
            tagline = style.tagline,
            accent = style.accent,
            personality = style.personality,
            voice = when (style) {
                AvatarStyle.NOVA -> VoiceStyle(0.92f, 1.05f, "Puck")
                AvatarStyle.MIRA -> VoiceStyle(1.1f, 1.0f, "Aoede")
                AvatarStyle.KIT -> VoiceStyle(1.32f, 1.08f, "Leda")
                AvatarStyle.ARIA -> VoiceStyle(1.08f, 1.0f, "Kore")
                AvatarStyle.LEO -> VoiceStyle(0.9f, 1.0f, "Charon")
            },
            builtin = style,
            body = style.gender?.let { BodySpec.defaultFor(it) },
        )

        val DEFAULT: AvatarProfile = of(AvatarStyle.ARIA)

        /** Accent colours offered for custom avatars. */
        val ACCENTS = listOf("#00D4FF", "#A78BFF", "#FF9F43", "#3DDC97", "#FF6FA8", "#FFD166").map { Color.parseColor(it) }
    }
}

/** Saves and loads custom avatars under the app's private files. */
class AvatarRepository(context: Context) {
    private val root = File(context.applicationContext.filesDir, "avatars").apply { mkdirs() }

    fun all(): List<AvatarProfile> = AvatarStyle.values().map { withSavedBody(AvatarProfile.of(it)) } + custom()

    /** Built-in full-body avatars remember the outfit, hair and skin you picked. */
    private fun withSavedBody(profile: AvatarProfile): AvatarProfile {
        if (profile.body == null || profile.builtin == null) return profile
        val file = File(root, "builtin-${profile.builtin.name}.json")
        val saved = try {
            if (file.exists()) BodySpec.fromJson(JSONObject(file.readText())) else null
        } catch (e: Exception) {
            null
        }
        return if (saved != null && saved.gender == profile.body.gender) profile.copy(body = saved) else profile
    }

    /** Saves a new outfit/hair/skin for an avatar and returns the updated profile. */
    fun saveBody(profile: AvatarProfile, body: BodySpec): AvatarProfile {
        if (profile.builtin != null) {
            File(root, "builtin-${profile.builtin.name}.json").writeText(body.toJson().toString())
            return profile.copy(body = body)
        }
        val dir = File(root, profile.id.removePrefix("custom:"))
        val metaFile = File(dir, "meta.json")
        if (!metaFile.exists()) return profile
        val meta = JSONObject(metaFile.readText()).put("body", body.toJson())
        metaFile.writeText(meta.toString())
        return profile.copy(body = body)
    }

    fun custom(): List<AvatarProfile> =
        (root.listFiles()?.filter { it.isDirectory } ?: emptyList())
            .mapNotNull { load(it) }
            .sortedBy { it.id }

    fun get(id: String?): AvatarProfile {
        if (id.isNullOrBlank()) return AvatarProfile.DEFAULT
        // Older versions stored just the built-in's name.
        val key = if (id.contains(':')) id else "builtin:$id"
        if (key.startsWith("builtin:")) {
            val style = AvatarStyle.values().firstOrNull { it.name.equals(key.removePrefix("builtin:"), true) }
            return style?.let { withSavedBody(AvatarProfile.of(it)) } ?: AvatarProfile.DEFAULT
        }
        val dir = File(root, key.removePrefix("custom:"))
        return load(dir) ?: AvatarProfile.DEFAULT
    }

    private fun load(dir: File): AvatarProfile? = try {
        val meta = JSONObject(File(dir, "meta.json").readText())
        val portrait = File(dir, "portrait.png")
        if (!portrait.exists()) null else AvatarProfile(
            id = "custom:" + dir.name,
            name = meta.optString("name", "My avatar"),
            tagline = meta.optString("tagline", "Your avatar"),
            accent = meta.optInt("accent", AvatarProfile.ACCENTS[0]),
            personality = meta.optString("personality"),
            voice = VoiceStyle(
                meta.optDouble("pitch", 1.0).toFloat(),
                meta.optDouble("rate", 1.0).toFloat(),
                meta.optString("voice", GeminiMedia.VOICES[0].first),
            ),
            portrait = portrait,
            landmarks = FaceLandmarks.fromJson(meta.getJSONObject("landmarks")),
            body = BodySpec.fromJson(meta.optJSONObject("body")),
        )
    } catch (e: Exception) {
        null
    }

    /** Creates or replaces a custom avatar and returns it. */
    fun save(
        existingId: String?,
        name: String,
        personality: String,
        accent: Int,
        voice: VoiceStyle,
        portrait: Bitmap,
        landmarks: FaceLandmarks,
        tagline: String,
        body: BodySpec? = null,
    ): AvatarProfile {
        val folder = existingId?.takeIf { it.startsWith("custom:") }?.removePrefix("custom:")
            ?: (System.currentTimeMillis().toString(36) + UUID.randomUUID().toString().take(6))
        val dir = File(root, folder).apply { mkdirs() }
        File(dir, "portrait.png").outputStream().use { portrait.compress(Bitmap.CompressFormat.PNG, 100, it) }
        val meta = JSONObject()
            .put("name", name.ifBlank { "My avatar" })
            .put("tagline", tagline)
            .put("personality", personality)
            .put("accent", accent)
            .put("pitch", voice.pitch.toDouble())
            .put("rate", voice.rate.toDouble())
            .put("voice", voice.geminiVoice)
            .put("landmarks", landmarks.toJson())
        if (body != null) meta.put("body", body.toJson())
        File(dir, "meta.json").writeText(meta.toString())
        return load(dir)!!
    }

    fun delete(id: String) {
        if (!id.startsWith("custom:")) return
        File(root, id.removePrefix("custom:")).deleteRecursively()
    }

    companion object {
        fun loadPortrait(file: File): Bitmap? = try {
            BitmapFactory.decodeFile(file.absolutePath)
        } catch (e: OutOfMemoryError) {
            null
        }
    }
}

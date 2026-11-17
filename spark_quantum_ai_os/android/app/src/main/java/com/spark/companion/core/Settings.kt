package com.spark.companion.core

import android.content.Context
import android.content.SharedPreferences
import com.spark.companion.brain.GeminiClient
import com.spark.companion.brain.GeminiMedia
import com.spark.companion.brain.Languages

/** User preferences. Secrets are stored encrypted (see [SecretStore]). */
class Settings(context: Context) {
    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences("spark", Context.MODE_PRIVATE)

    // Decrypting hits the Keystore, so secrets are cached after first read.
    @Volatile private var apiKeyCache: String? = null
    @Volatile private var tokenCache: String? = null
    @Volatile private var groqKeyCache: String? = null
    @Volatile private var openRouterKeyCache: String? = null

    var apiKey: String
        get() = apiKeyCache ?: SecretStore.decrypt(prefs.getString("api_key", null)).also { apiKeyCache = it }
        set(value) {
            val clean = value.trim()
            apiKeyCache = clean
            prefs.edit().putString("api_key", if (clean.isEmpty()) null else SecretStore.encrypt(clean)).apply()
        }

    var groqApiKey: String
        get() = groqKeyCache ?: SecretStore.decrypt(prefs.getString("groq_key", null)).also { groqKeyCache = it }
        set(value) {
            val clean = value.trim()
            groqKeyCache = clean
            prefs.edit().putString("groq_key", if (clean.isEmpty()) null else SecretStore.encrypt(clean)).apply()
        }

    var openRouterApiKey: String
        get() = openRouterKeyCache ?: SecretStore.decrypt(prefs.getString("openrouter_key", null)).also { openRouterKeyCache = it }
        set(value) {
            val clean = value.trim()
            openRouterKeyCache = clean
            prefs.edit().putString("openrouter_key", if (clean.isEmpty()) null else SecretStore.encrypt(clean)).apply()
        }

    var serverToken: String
        get() = tokenCache ?: SecretStore.decrypt(prefs.getString("server_token", null)).also { tokenCache = it }
        set(value) {
            val clean = value.trim()
            tokenCache = clean
            prefs.edit().putString("server_token", if (clean.isEmpty()) null else SecretStore.encrypt(clean)).apply()
        }

    var userName: String
        get() = prefs.getString("user_name", "") ?: ""
        set(value) = prefs.edit().putString("user_name", value.trim()).apply()

    var model: String
        get() = prefs.getString("model", null)?.takeIf { it.isNotBlank() } ?: GeminiClient.DEFAULT_MODEL
        set(value) = prefs.edit().putString("model", value.trim()).apply()

    /** Base URL of the SPARK server on the user's PC, e.g. http://192.168.1.20:8765 */
    var serverUrl: String
        get() = prefs.getString("server_url", "") ?: ""
        set(value) = prefs.edit().putString("server_url", value.trim().trimEnd('/')).apply()

    /** BCP-47 tag for speech (e.g. en-IN, hi-IN). Empty = phone default. Kept for older installs. */
    var language: String
        get() = prefs.getString("language", "") ?: ""
        set(value) = prefs.edit().putString("language", value.trim()).apply()

    /**
     * Languages the user speaks, most-used first (BCP-47 tags). Spark replies
     * in whichever one they use, with a matching voice.
     */
    var languages: List<String>
        get() {
            val saved = Languages.parseList(prefs.getString("languages", "") ?: "")
            if (saved.isNotEmpty()) return saved
            val first = language.ifBlank { java.util.Locale.getDefault().toLanguageTag() }
            return listOf(Languages.resolve(first, emptyList()) ?: "en-IN")
        }
        set(value) = prefs.edit().putString("languages", value.distinct().joinToString(",")).apply()

    val primaryLanguage: String get() = languages.first()

    /** "phone" for the phone's own voices, "gemini" for Gemini's natural voices. */
    var voiceEngine: String
        get() = prefs.getString("voice_engine", "phone") ?: "phone"
        set(value) = prefs.edit().putString("voice_engine", value).apply()

    var imageModel: String
        get() = prefs.getString("image_model", null)?.takeIf { it.isNotBlank() } ?: GeminiMedia.DEFAULT_IMAGE_MODEL
        set(value) = prefs.edit().putString("image_model", value.trim()).apply()

    var ttsModel: String
        get() = prefs.getString("tts_model", null)?.takeIf { it.isNotBlank() } ?: GeminiMedia.DEFAULT_TTS_MODEL
        set(value) = prefs.edit().putString("tts_model", value.trim()).apply()

    /** Which brain answers: "gemini", "groq" or "openrouter". */
    var chatProvider: String
        get() = prefs.getString("chat_provider", "gemini") ?: "gemini"
        set(value) = prefs.edit().putString("chat_provider", value).apply()

    var groqModel: String
        get() = prefs.getString("groq_model", null)?.takeIf { it.isNotBlank() } ?: com.spark.companion.brain.OpenAiCompatClient.DEFAULT_GROQ_MODEL
        set(value) = prefs.edit().putString("groq_model", value.trim()).apply()

    var openRouterModel: String
        get() = prefs.getString("openrouter_model", null)?.takeIf { it.isNotBlank() } ?: com.spark.companion.brain.OpenAiCompatClient.DEFAULT_OPENROUTER_MODEL
        set(value) = prefs.edit().putString("openrouter_model", value.trim()).apply()

    /** Which image generator makes avatars: "gemini" or "pollinations" (free). */
    var imageProvider: String
        get() = prefs.getString("image_provider", "gemini") ?: "gemini"
        set(value) = prefs.edit().putString("image_provider", value).apply()

    /** The API key for the current chat provider (empty for Pollinations/Gemini-free is handled elsewhere). */
    fun chatKeyFor(provider: String): String = when (provider) {
        "groq" -> groqApiKey
        "openrouter" -> openRouterApiKey
        else -> apiKey
    }

    var wakeWord: String
        get() = prefs.getString("wake_word", null)?.takeIf { it.isNotBlank() } ?: "spark"
        set(value) = prefs.edit().putString("wake_word", value.trim().lowercase()).apply()

    var speakReplies: Boolean
        get() = prefs.getBoolean("speak_replies", true)
        set(value) = prefs.edit().putBoolean("speak_replies", value).apply()

    /** Keep listening after each spoken reply until the user stops talking. */
    var continuous: Boolean
        get() = prefs.getBoolean("continuous", true)
        set(value) = prefs.edit().putBoolean("continuous", value).apply()

    /** Show the sleeping avatar over other apps, not only while it is talking. */
    var floatingAvatar: Boolean
        get() = prefs.getBoolean("floating_orb", true)
        set(value) = prefs.edit().putBoolean("floating_orb", value).apply()

    /** Which avatar Spark appears as: "builtin:NOVA" or "custom:<id>". */
    var avatar: String
        get() = prefs.getString("avatar", "builtin:NOVA") ?: "builtin:NOVA"
        set(value) = prefs.edit().putString("avatar", value).apply()

    /** Always listen for the wake word. On by default: it's how you wake the avatar. */
    var wakeWordEnabled: Boolean
        get() = prefs.getBoolean("wake_word_enabled", true)
        set(value) = prefs.edit().putBoolean("wake_word_enabled", value).apply()

    /** Spark speaks a short heads-up when a new message/notification arrives. */
    var proactiveEnabled: Boolean
        get() = prefs.getBoolean("proactive", false)
        set(value) = prefs.edit().putBoolean("proactive", value).apply()

    val desktopLinked: Boolean get() = serverUrl.isNotBlank()

    fun registerListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) =
        prefs.registerOnSharedPreferenceChangeListener(listener)

    fun unregisterListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) =
        prefs.unregisterOnSharedPreferenceChangeListener(listener)
}

package com.spark.companion.core

import android.content.Context
import android.content.SharedPreferences
import com.spark.companion.brain.ClaudeClient
import com.spark.companion.brain.Languages

/** User preferences. Secrets are stored encrypted (see [SecretStore]). */
class Settings(context: Context) {
    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences("spark", Context.MODE_PRIVATE)

    // Decrypting hits the Keystore, so secrets are cached after first read.
    @Volatile private var claudeKeyCache: String? = null
    @Volatile private var tokenCache: String? = null
    @Volatile private var n8nAuthCache: String? = null

    init {
        // Spark now uses Claude only: forget keys saved for the providers it no longer uses.
        if (!prefs.getBoolean("old_providers_cleared", false)) {
            prefs.edit()
                .remove("api_key").remove("groq_key").remove("openrouter_key").remove("custom_key")
                .remove("model").remove("groq_model").remove("openrouter_model").remove("ollama_url")
                .remove("ollama_model").remove("custom_url").remove("custom_model").remove("chat_provider")
                .remove("image_model").remove("tts_model").remove("image_provider").remove("voice_engine")
                .putBoolean("old_providers_cleared", true)
                .apply()
        }
    }

    /** The Claude API key (console.anthropic.com), stored encrypted. */
    var claudeApiKey: String
        get() = claudeKeyCache ?: SecretStore.decrypt(prefs.getString("claude_key", null)).also { claudeKeyCache = it }
        set(value) {
            val clean = value.trim()
            claudeKeyCache = clean
            prefs.edit().putString("claude_key", if (clean.isEmpty()) null else SecretStore.encrypt(clean)).apply()
        }

    /** The Claude model Spark talks with. */
    var claudeModel: String
        get() = prefs.getString("claude_model", null)?.takeIf { it.isNotBlank() } ?: ClaudeClient.DEFAULT_MODEL
        set(value) = prefs.edit().putString("claude_model", value.trim()).apply()

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

    /** n8n webhook base, e.g. http://192.168.1.20:5678 (or .../webhook). */
    var n8nUrl: String
        get() = prefs.getString("n8n_url", "") ?: ""
        set(value) = prefs.edit().putString("n8n_url", value.trim().trimEnd('/')).apply()

    /** Optional n8n auth, as "Header-Name: value" or just a bearer token. */
    var n8nAuth: String
        get() = n8nAuthCache ?: SecretStore.decrypt(prefs.getString("n8n_auth", null)).also { n8nAuthCache = it }
        set(value) {
            val clean = value.trim()
            n8nAuthCache = clean
            prefs.edit().putString("n8n_auth", if (clean.isEmpty()) null else SecretStore.encrypt(clean)).apply()
        }

    val n8nConfigured: Boolean get() = n8nUrl.isNotBlank()

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

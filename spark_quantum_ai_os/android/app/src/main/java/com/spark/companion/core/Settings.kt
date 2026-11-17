package com.spark.companion.core

import android.content.Context
import android.content.SharedPreferences
import com.spark.companion.brain.GeminiClient

/** User preferences. Secrets are stored encrypted (see [SecretStore]). */
class Settings(context: Context) {
    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences("spark", Context.MODE_PRIVATE)

    // Decrypting hits the Keystore, so secrets are cached after first read.
    @Volatile private var apiKeyCache: String? = null
    @Volatile private var tokenCache: String? = null

    var apiKey: String
        get() = apiKeyCache ?: SecretStore.decrypt(prefs.getString("api_key", null)).also { apiKeyCache = it }
        set(value) {
            val clean = value.trim()
            apiKeyCache = clean
            prefs.edit().putString("api_key", if (clean.isEmpty()) null else SecretStore.encrypt(clean)).apply()
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

    /** BCP-47 tag for speech (e.g. en-IN, hi-IN). Empty = phone default. */
    var language: String
        get() = prefs.getString("language", "") ?: ""
        set(value) = prefs.edit().putString("language", value.trim()).apply()

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

    /** Which character Spark appears as (an AvatarStyle name). */
    var avatar: String
        get() = prefs.getString("avatar", "NOVA") ?: "NOVA"
        set(value) = prefs.edit().putString("avatar", value).apply()

    /** Always listen for the wake word. On by default: it's how you wake the avatar. */
    var wakeWordEnabled: Boolean
        get() = prefs.getBoolean("wake_word_enabled", true)
        set(value) = prefs.edit().putBoolean("wake_word_enabled", value).apply()

    val desktopLinked: Boolean get() = serverUrl.isNotBlank()

    fun registerListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) =
        prefs.registerOnSharedPreferenceChangeListener(listener)

    fun unregisterListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) =
        prefs.unregisterOnSharedPreferenceChangeListener(listener)
}

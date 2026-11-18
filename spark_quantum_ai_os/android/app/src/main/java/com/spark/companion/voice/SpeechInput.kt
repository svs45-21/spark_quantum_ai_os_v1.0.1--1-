package com.spark.companion.voice

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognitionService
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * Speech-to-text using the phone's own recognition service. Must be called
 * from the main thread (SpeechRecognizer requirement).
 */
class SpeechInput(private val context: Context) {

    private val main = Handler(Looper.getMainLooper())

    /** Error code of the most recent failed listen, or 0. */
    var lastError: Int = 0
        private set

    val available: Boolean
        get() = SpeechRecognizer.isRecognitionAvailable(context)

    /**
     * Whether an on-device recogniser exists (Android 12+). It works offline
     * and on most phones does not play a beep each time it starts, which
     * makes it much better for always-on wake-word listening.
     */
    val onDeviceAvailable: Boolean
        get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            try {
                SpeechRecognizer.isOnDeviceRecognitionAvailable(context)
            } catch (e: Exception) {
                false
            }

    /**
     * Picks the recognition service to use. Spark declares a placeholder
     * RecognitionService of its own (needed to be selectable as the default
     * assistant), and once selected the system would route recognition to that
     * placeholder, so a real engine is always chosen explicitly.
     */
    private fun recognizerComponent(): ComponentName? {
        val services = try {
            context.packageManager.queryIntentServices(Intent(RecognitionService.SERVICE_INTERFACE), 0)
        } catch (e: Exception) {
            emptyList()
        }
        val others = services.mapNotNull { it.serviceInfo }
            .filter { it.packageName != context.packageName }
        val preferred = listOf("com.google.android.googlequicksearchbox", "com.google.android.tts", "com.google.android.as")
        val pick = preferred.firstNotNullOfOrNull { pkg -> others.firstOrNull { it.packageName == pkg } }
            ?: others.firstOrNull()
        return pick?.let { ComponentName(it.packageName, it.name) }
    }

    /**
     * Listens for one utterance. Returns the recognised text, or null if
     * nothing was heard or recognition failed (see [lastError]).
     */
    /** Language the recogniser reported hearing in the last listen (Android 14+), or null. */
    var detectedLanguage: String? = null
        private set

    /**
     * Listens for one utterance in [language]. On Android 14+ it may also
     * switch to any of [alsoAllowed] if that's what the user speaks.
     */
    suspend fun listen(
        language: String,
        onPartial: (String) -> Unit = {},
        onLevel: (Float) -> Unit = {},
        onDevice: Boolean = false,
        alsoAllowed: List<String> = emptyList(),
    ): String? = withTimeoutOrNull(25_000) { listenOnce(language, onPartial, onLevel, onDevice, alsoAllowed) }

    private suspend fun listenOnce(
        language: String,
        onPartial: (String) -> Unit,
        onLevel: (Float) -> Unit,
        onDevice: Boolean,
        alsoAllowed: List<String>,
    ): String? = suspendCancellableCoroutine { cont ->
        lastError = 0
        detectedLanguage = null
        val recognizer = try {
            val component = recognizerComponent()
            when {
                onDevice && onDeviceAvailable && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
                    SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
                component != null -> SpeechRecognizer.createSpeechRecognizer(context, component)
                else -> SpeechRecognizer.createSpeechRecognizer(context)
            }
        } catch (e: Exception) {
            lastError = SpeechRecognizer.ERROR_CLIENT
            cont.resume(null)
            return@suspendCancellableCoroutine
        }
        var done = false
        fun release() {
            try {
                recognizer.destroy()
            } catch (e: Exception) {
                // already gone
            }
        }

        fun finish(text: String?) {
            if (done) return
            done = true
            release()
            if (cont.isActive) cont.resume(text)
        }

        recognizer.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onEvent(eventType: Int, params: Bundle?) {}

            override fun onRmsChanged(rmsdB: Float) {
                // Roughly -2..10 dB in practice; map to 0..1.
                onLevel(((rmsdB + 2f) / 12f).coerceIn(0f, 1f))
            }

            override fun onPartialResults(partialResults: Bundle?) {
                best(partialResults)?.let(onPartial)
            }

            override fun onResults(results: Bundle?) {
                finish(best(results)?.takeIf { it.isNotBlank() })
            }

            override fun onError(error: Int) {
                lastError = error
                finish(null)
            }

            override fun onLanguageDetection(results: Bundle) {
                detectedLanguage = results.getString(SpeechRecognizer.DETECTED_LANGUAGE)
            }
        })

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
            if (language.isNotBlank()) putExtra(RecognizerIntent.EXTRA_LANGUAGE, language)
            val others = alsoAllowed.filter { it.isNotBlank() }
            if (others.size > 1 && Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                // Let the recogniser follow the user between the languages they speak.
                val allowed = ArrayList(others)
                putExtra(RecognizerIntent.EXTRA_ENABLE_LANGUAGE_DETECTION, true)
                putStringArrayListExtra(RecognizerIntent.EXTRA_LANGUAGE_DETECTION_ALLOWED_LANGUAGES, allowed)
                putExtra(RecognizerIntent.EXTRA_ENABLE_LANGUAGE_SWITCH, RecognizerIntent.LANGUAGE_SWITCH_BALANCED)
                putStringArrayListExtra(RecognizerIntent.EXTRA_LANGUAGE_SWITCH_ALLOWED_LANGUAGES, allowed)
            }
        }
        try {
            recognizer.startListening(intent)
        } catch (e: Exception) {
            lastError = SpeechRecognizer.ERROR_CLIENT
            finish(null)
        }

        cont.invokeOnCancellation {
            main.post {
                if (!done) {
                    done = true
                    try {
                        recognizer.cancel()
                    } catch (e: Exception) {
                        // ignore
                    }
                    release()
                }
            }
        }
    }

    /** Outcome of one continuous wake-word session. */
    sealed class WakeResult {
        /** The wake word was heard; [command] is whatever followed it ("" if nothing). */
        data class Heard(val command: String) : WakeResult()

        /** The session ended without the wake word. */
        data class Ended(val error: Int, val unsupported: Boolean) : WakeResult()
    }

    /**
     * Listens for the wake word in ONE long session (Android 13+ "segmented
     * session"), so the microphone stays on steadily instead of switching off
     * and on after every pause. Returns as soon as the wake word is heard.
     * If the recogniser doesn't support long sessions it ends at the first
     * pause; the result then says [WakeResult.Ended.unsupported].
     */
    suspend fun listenForWakeWord(
        language: String,
        wakeWord: String,
        onDevice: Boolean,
        sessionMs: Int,
        onStir: () -> Unit,
    ): WakeResult = suspendCancellableCoroutine { cont ->
        lastError = 0
        val started = android.os.SystemClock.uptimeMillis()
        val recognizer = try {
            val component = recognizerComponent()
            when {
                onDevice && onDeviceAvailable && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
                    SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
                component != null -> SpeechRecognizer.createSpeechRecognizer(context, component)
                else -> SpeechRecognizer.createSpeechRecognizer(context)
            }
        } catch (e: Exception) {
            lastError = SpeechRecognizer.ERROR_CLIENT
            cont.resume(WakeResult.Ended(SpeechRecognizer.ERROR_CLIENT, unsupported = true))
            return@suspendCancellableCoroutine
        }
        var done = false
        var stirred = false

        fun finish(result: WakeResult) {
            if (done) return
            done = true
            try {
                recognizer.cancel()
            } catch (e: Exception) {
                // already stopped
            }
            try {
                recognizer.destroy()
            } catch (e: Exception) {
                // already gone
            }
            if (cont.isActive) cont.resume(result)
        }

        fun elapsed() = android.os.SystemClock.uptimeMillis() - started

        fun check(text: String?, final: Boolean) {
            if (text.isNullOrBlank()) return
            val command = com.spark.companion.brain.SpeechText.afterWakeWord(text, wakeWord) ?: return
            if (!stirred) {
                stirred = true
                onStir()
            }
            if (final) finish(WakeResult.Heard(command))
        }

        recognizer.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
            override fun onRmsChanged(rmsdB: Float) {}

            override fun onPartialResults(partialResults: Bundle?) = check(best(partialResults), final = false)

            // A finished stretch of speech inside the long session.
            override fun onSegmentResults(segmentResults: Bundle) = check(best(segmentResults), final = true)

            override fun onEndOfSegmentedSession() {
                finish(WakeResult.Ended(0, unsupported = false))
            }

            // Reached only when the recogniser ignored the long-session request.
            override fun onResults(results: Bundle?) {
                check(best(results), final = true)
                finish(WakeResult.Ended(0, com.spark.companion.brain.SpeechText.continuousLooksUnsupported(elapsed())))
            }

            override fun onError(error: Int) {
                lastError = error
                val quiet = error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT
                finish(WakeResult.Ended(error, quiet && com.spark.companion.brain.SpeechText.continuousLooksUnsupported(elapsed())))
            }
        })

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
            if (language.isNotBlank()) putExtra(RecognizerIntent.EXTRA_LANGUAGE, language)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                putExtra(RecognizerIntent.EXTRA_SEGMENTED_SESSION, RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, sessionMs)
            }
        }
        try {
            recognizer.startListening(intent)
        } catch (e: Exception) {
            lastError = SpeechRecognizer.ERROR_CLIENT
            finish(WakeResult.Ended(SpeechRecognizer.ERROR_CLIENT, unsupported = true))
        }

        cont.invokeOnCancellation {
            main.post {
                if (!done) {
                    done = true
                    try {
                        recognizer.cancel()
                    } catch (e: Exception) {
                        // ignore
                    }
                    try {
                        recognizer.destroy()
                    } catch (e: Exception) {
                        // ignore
                    }
                }
            }
        }
    }

    private fun best(bundle: Bundle?): String? =
        bundle?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()

    companion object {
        fun describeError(code: Int): String = when (code) {
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "I need microphone permission to hear you."
            SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT ->
                "Speech recognition needs an internet connection right now."
            SpeechRecognizer.ERROR_AUDIO -> "I couldn't get the microphone."
            SpeechRecognizer.ERROR_CLIENT, SpeechRecognizer.ERROR_SERVER ->
                "Speech recognition isn't working on this phone right now."
            else -> ""
        }
    }
}

package com.spark.companion.voice

import android.content.Context
import android.media.AudioAttributes
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import com.spark.companion.brain.SpeechText
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.resume

/** Text-to-speech with a suspend [speak] that returns when the audio ends. */
class Speaker(context: Context) {

    private val ready = CompletableDeferred<Boolean>()
    private val waiters = ConcurrentHashMap<String, () -> Unit>()
    private val counter = AtomicLong()

    private val tts: TextToSpeech = TextToSpeech(context.applicationContext) { status ->
        ready.complete(status == TextToSpeech.SUCCESS)
    }

    init {
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) = release(utteranceId)
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) = release(utteranceId)
            override fun onError(utteranceId: String?, errorCode: Int) = release(utteranceId)
            override fun onStop(utteranceId: String?, interrupted: Boolean) = release(utteranceId)
        })
    }

    private fun release(id: String?) {
        if (id != null) waiters.remove(id)?.invoke()
    }

    /** Speaks [text] and suspends until it has finished (or is cancelled). */
    suspend fun speak(text: String, language: String) {
        val clean = SpeechText.forSpeech(text)
        if (clean.isBlank()) return
        if (withTimeoutOrNull(5_000) { ready.await() } != true) return
        try {
            tts.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANT)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            if (language.isNotBlank()) tts.language = Locale.forLanguageTag(language)
        } catch (e: Exception) {
            // keep the engine defaults
        }
        for (chunk in SpeechText.chunks(clean)) {
            val id = "spark-" + counter.incrementAndGet()
            // Generous ceiling so a TTS engine that never calls back can't hang the app.
            withTimeoutOrNull(20_000L + chunk.length * 120L) {
                suspendCancellableCoroutine { cont ->
                    waiters[id] = { if (cont.isActive) cont.resume(Unit) }
                    val result = tts.speak(chunk, TextToSpeech.QUEUE_ADD, Bundle(), id)
                    if (result != TextToSpeech.SUCCESS) {
                        waiters.remove(id)
                        if (cont.isActive) cont.resume(Unit)
                    }
                    cont.invokeOnCancellation {
                        waiters.remove(id)
                        stop()
                    }
                }
            }
        }
    }

    fun stop() {
        try {
            tts.stop()
        } catch (e: Exception) {
            // ignore
        }
    }
}

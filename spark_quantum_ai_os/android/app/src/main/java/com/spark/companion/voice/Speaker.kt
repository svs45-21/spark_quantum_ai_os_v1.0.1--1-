package com.spark.companion.voice

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.audiofx.Visualizer
import android.os.Bundle
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import com.spark.companion.brain.SpeechText
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.resume
import kotlin.math.abs
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Text-to-speech with a suspend [speak] that returns when the audio ends,
 * and a live voice level for the avatar's lip-sync.
 */
class Speaker(private val context: Context) {

    private val ready = CompletableDeferred<Boolean>()
    private val waiters = ConcurrentHashMap<String, () -> Unit>()
    private val counter = AtomicLong()
    private val audio = context.getSystemService(AudioManager::class.java)

    /** Uptime of the last word boundary reported by the TTS engine. */
    @Volatile private var lastWordAt = 0L
    @Volatile private var lastWordLength = 0
    @Volatile private var wordEvents = false

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
            override fun onRangeStart(utteranceId: String?, start: Int, end: Int, frame: Int) {
                wordEvents = true
                lastWordAt = SystemClock.uptimeMillis()
                lastWordLength = end - start
            }
        })
    }

    private fun release(id: String?) {
        if (id != null) waiters.remove(id)?.invoke()
    }

    /**
     * Speaks [text] and suspends until it has finished (or is cancelled).
     * [onLevel] receives the loudness of the voice, 0..1, about 30 times a second.
     */
    suspend fun speak(text: String, language: String, onLevel: (Float) -> Unit = {}) {
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
        val session = try {
            audio.generateAudioSessionId()
        } catch (e: Exception) {
            AudioManager.ERROR
        }
        val meter = if (session > 0) VoiceMeter.open(context, session) else null
        val started = SystemClock.uptimeMillis()
        wordEvents = false
        lastWordAt = 0L
        try {
            coroutineScope {
                val ticker = launch {
                    while (isActive) {
                        val now = SystemClock.uptimeMillis()
                        // Use the real audio level unless the device only feeds us silence.
                        val real = meter?.takeIf { now - started < 900 || it.maxSeen > 0.03f }?.level
                        onLevel(real ?: syntheticLevel(now))
                        delay(33)
                    }
                }
                for (chunk in SpeechText.chunks(clean)) {
                    val id = "spark-" + counter.incrementAndGet()
                    // Generous ceiling so a TTS engine that never calls back can't hang the app.
                    withTimeoutOrNull(20_000L + chunk.length * 120L) {
                        suspendCancellableCoroutine { cont ->
                            waiters[id] = { if (cont.isActive) cont.resume(Unit) }
                            val params = Bundle()
                            if (session > 0) params.putInt(TextToSpeech.Engine.KEY_PARAM_SESSION_ID, session)
                            val result = tts.speak(chunk, TextToSpeech.QUEUE_ADD, params, id)
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
                ticker.cancel()
            }
        } finally {
            meter?.close()
            onLevel(0f)
        }
    }

    /** Mouth movement from word timings when no audio level is available. */
    private fun syntheticLevel(now: Long): Float {
        val t = now / 1000f
        val wobble = abs(sin(t * 13f) * sin(t * 3.7f))
        if (!wordEvents) return 0.2f + 0.7f * wobble
        val wordMs = 90L * lastWordLength.coerceIn(2, 12)
        return if (now - lastWordAt < wordMs) 0.35f + 0.6f * wobble else 0.08f * wobble
    }

    fun stop() {
        try {
            tts.stop()
        } catch (e: Exception) {
            // ignore
        }
    }

    /**
     * Measures how loud Spark's own voice is by attaching a Visualizer to the
     * TTS audio session. Needs the microphone permission; some phones refuse,
     * in which case word timings drive the mouth instead.
     */
    private class VoiceMeter private constructor(private val visualizer: Visualizer) {
        @Volatile var level = 0f
            private set
        @Volatile var maxSeen = 0f
            private set

        init {
            visualizer.captureSize = Visualizer.getCaptureSizeRange()[0].coerceAtLeast(128)
                .coerceAtMost(Visualizer.getCaptureSizeRange()[1])
            visualizer.setDataCaptureListener(object : Visualizer.OnDataCaptureListener {
                override fun onWaveFormDataCapture(v: Visualizer?, wave: ByteArray?, rate: Int) {
                    wave ?: return
                    var sum = 0.0
                    for (b in wave) {
                        val s = (b.toInt() and 0xFF) - 128
                        sum += s * s
                    }
                    val rms = sqrt(sum / wave.size.coerceAtLeast(1)).toFloat()
                    val target = (rms / 28f).coerceIn(0f, 1f)
                    level += (target - level) * 0.6f
                    if (target > maxSeen) maxSeen = target
                }

                override fun onFftDataCapture(v: Visualizer?, fft: ByteArray?, rate: Int) = Unit
            }, Visualizer.getMaxCaptureRate() / 2, true, false)
            visualizer.enabled = true
        }

        fun close() {
            try {
                visualizer.enabled = false
                visualizer.release()
            } catch (e: Exception) {
                // already released
            }
        }

        companion object {
            fun open(context: Context, session: Int): VoiceMeter? {
                if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return null
                return try {
                    VoiceMeter(Visualizer(session))
                } catch (e: Throwable) {
                    null
                }
            }
        }
    }
}

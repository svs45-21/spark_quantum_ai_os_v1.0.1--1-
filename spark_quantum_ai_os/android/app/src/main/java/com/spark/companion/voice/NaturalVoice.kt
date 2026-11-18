package com.spark.companion.voice

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import com.spark.companion.brain.GeminiHttp
import com.spark.companion.brain.GeminiMedia
import com.spark.companion.brain.LlmException
import com.spark.companion.brain.ModelSwitch
import com.spark.companion.brain.ModelCatalog
import com.spark.companion.brain.Mood
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

/**
 * Gemini's natural, expressive voices. Audio comes back as raw PCM, so the
 * avatar's mouth follows the exact loudness of what is being played.
 */
class NaturalVoice(
    private val apiKey: () -> String,
    model: () -> String,
    onModelSwitch: (String) -> Unit = {},
) {
    private val models = ModelSwitch({ model().trim().ifEmpty { GeminiMedia.DEFAULT_TTS_MODEL } }, onModelSwitch)

    @Throws(LlmException::class)
    suspend fun synthesize(text: String, voice: String, mood: Mood?): GeminiMedia.Pcm {
        val body = GeminiMedia.speechRequest(text, voice, mood).toString()
        val key = apiKey().trim()
        val reply = models.run(
            discover = { ModelCatalog.pickGeminiTts(ModelCatalog.listGemini(key)) },
            alternatives = { ModelCatalog.geminiTtsModels(ModelCatalog.listGemini(key)) },
        ) { m ->
            GeminiHttp.post(GeminiHttp.endpoint(m, GeminiMedia.DEFAULT_TTS_MODEL), key, body, 120_000)
        }
        return GeminiMedia.parseSpeech(reply)
    }

    /** Plays [pcm] and reports the voice level as it goes. Suspends until done or cancelled. */
    suspend fun play(pcm: GeminiMedia.Pcm, onLevel: (Float) -> Unit, onMouth: (com.spark.companion.brain.MouthShape?) -> Unit = {}) {
        val frameMs = 40
        val levels = GeminiMedia.levels(pcm, frameMs)
        // Mouth shapes from the sound itself (jaw, lip spread and rounding).
        val shapes = withContext(Dispatchers.Default) {
            val samples = ShortArray(pcm.data.size / 2) { i ->
                ((pcm.data[2 * i].toInt() and 0xFF) or (pcm.data[2 * i + 1].toInt() shl 8)).toShort()
            }
            com.spark.companion.brain.LipSync.fromPcm(samples, pcm.sampleRate, frameMs)
        }
        val track = withContext(Dispatchers.IO) {
            AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANT)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(pcm.sampleRate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setTransferMode(AudioTrack.MODE_STATIC)
                .setBufferSizeInBytes(pcm.data.size.coerceAtLeast(2))
                .build()
        }
        try {
            track.write(pcm.data, 0, pcm.data.size)
            track.play()
            val totalFrames = pcm.data.size / 2
            while (coroutineContext.isActive) {
                val head = track.playbackHeadPosition
                if (head >= totalFrames || track.playState != AudioTrack.PLAYSTATE_PLAYING) break
                val index = (head.toLong() * 1000 / pcm.sampleRate / frameMs).toInt()
                onLevel(levels.getOrElse(index) { 0f })
                onMouth(shapes.getOrNull(index))
                delay(30)
            }
        } finally {
            onLevel(0f)
            onMouth(null)
            try {
                track.stop()
            } catch (e: IllegalStateException) {
                // never started
            }
            track.release()
        }
    }
}

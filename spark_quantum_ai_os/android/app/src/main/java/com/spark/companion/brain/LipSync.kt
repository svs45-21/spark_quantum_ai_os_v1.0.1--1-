package com.spark.companion.brain

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * The shape of the mouth for one moment of speech. [open] is how far the jaw
 * drops, [wide] how much the lips stretch sideways (ee, s), [round] how much
 * they purse forward (oo, o, w). All 0..1.
 */
data class MouthShape(val open: Float, val wide: Float, val round: Float) {
    companion object {
        val REST = MouthShape(0f, 0f, 0f)
    }
}

/**
 * Turns speech into mouth shapes so the lips move like real talking rather
 * than just flapping with loudness.
 *
 * - For Gemini voices the audio itself is analysed: loudness opens the jaw,
 *   hiss-like sound (many zero crossings: s, sh, f, ee) stretches the lips,
 *   and bass-heavy vowels (oo, o, w) round them; near-silence closes the lips
 *   (m, b, p and pauses).
 * - For the phone's voice, where the audio isn't available, the shapes come
 *   from the letters being spoken (visemes), timed by the engine's word events.
 */
object LipSync {

    /** Mouth shapes for 16-bit mono PCM, one per [frameMs] of audio. */
    fun fromPcm(samples: ShortArray, sampleRate: Int, frameMs: Int): List<MouthShape> {
        val frame = (sampleRate * frameMs / 1000).coerceAtLeast(1)
        val out = ArrayList<MouthShape>(samples.size / frame + 1)
        var peak = 1e-3
        // First pass: loudness ceiling so quiet voices still open the mouth.
        var i = 0
        while (i < samples.size) {
            val end = minOf(samples.size, i + frame)
            peak = maxOf(peak, rms(samples, i, end))
            i = end
        }
        val ref = peak * 0.8
        var low = 0.0
        i = 0
        while (i < samples.size) {
            val end = minOf(samples.size, i + frame)
            val energy = rms(samples, i, end)
            var crossings = 0
            var lowEnergy = 0.0
            var prev = samples[i].toInt()
            for (k in i until end) {
                val s = samples[k].toInt()
                if ((s >= 0) != (prev >= 0)) crossings++
                prev = s
                // One-pole low-pass (~500 Hz) to measure how bass-heavy the sound is.
                low += (s - low) * (2 * Math.PI * 500 / sampleRate)
                lowEnergy += low * low
            }
            val n = (end - i).coerceAtLeast(1)
            val zcr = crossings.toDouble() / n * sampleRate / 2 // ≈ dominant frequency in Hz
            val lowRatio = if (energy > 1e-6) sqrt(lowEnergy / n) / energy else 0.0
            val loud = (energy / ref).coerceIn(0.0, 1.0)
            out += shapeFor(loud.toFloat(), zcr.toFloat(), lowRatio.toFloat())
            i = end
        }
        return out
    }

    /** Maps loudness, brightness (Hz) and bass ratio to a mouth shape. */
    fun shapeFor(loud: Float, brightnessHz: Float, lowRatio: Float): MouthShape {
        if (loud < 0.06f) return MouthShape.REST
        val hiss = ((brightnessHz - 1800f) / 2500f).coerceIn(0f, 1f)
        val round = ((lowRatio - 0.72f) / 0.22f).coerceIn(0f, 1f) * (1f - hiss)
        // Hissing sounds keep the teeth nearly together; vowels drop the jaw.
        val open = (loud * (1f - 0.65f * hiss) * (1f - 0.3f * round)).coerceIn(0f, 1f)
        val wide = (hiss * 0.8f + (1f - round) * 0.35f * loud).coerceIn(0f, 1f)
        return MouthShape(open, wide, round)
    }

    private fun rms(s: ShortArray, from: Int, to: Int): Double {
        if (to <= from) return 0.0
        var sum = 0.0
        for (k in from until to) {
            val v = s[k].toDouble()
            sum += v * v
        }
        return sqrt(sum / (to - from))
    }

    /** The mouth shape for a letter of text (phone voice). */
    fun viseme(c: Char): MouthShape = when (c.lowercaseChar()) {
        'a', 'á', 'à', 'ā', 'h' -> MouthShape(0.95f, 0.35f, 0f)
        'e', 'é', 'è' -> MouthShape(0.6f, 0.75f, 0f)
        'i', 'í', 'ī', 'y' -> MouthShape(0.4f, 1f, 0f)
        'o', 'ó', 'ō' -> MouthShape(0.7f, 0f, 0.85f)
        'u', 'ú', 'ū', 'w', 'q' -> MouthShape(0.35f, 0f, 1f)
        'm', 'b', 'p' -> MouthShape(0f, 0.15f, 0f)
        'f', 'v' -> MouthShape(0.15f, 0.55f, 0f)
        's', 'z', 'c', 'x', 'j', 'ś', 'ṣ' -> MouthShape(0.18f, 0.85f, 0f)
        't', 'd', 'n', 'l', 'k', 'g', 'r' -> MouthShape(0.4f, 0.45f, 0.1f)
        ' ', ',', '.', '!', '?', ';', ':' -> MouthShape.REST
        else -> if (c.isLetter()) MouthShape(0.55f, 0.4f, 0.1f) else MouthShape.REST
    }

    /**
     * Mouth shape [msIntoWord] milliseconds into [word], assuming [msPerChar]
     * per letter. Blends the neighbouring letters so the lips glide.
     */
    fun shapeInWord(word: String, msIntoWord: Long, msPerChar: Float): MouthShape {
        if (word.isEmpty()) return MouthShape.REST
        val pos = (msIntoWord / msPerChar).coerceAtLeast(0f)
        val idx = pos.toInt()
        if (idx >= word.length) return MouthShape.REST
        val a = viseme(word[idx])
        val b = if (idx + 1 < word.length) viseme(word[idx + 1]) else MouthShape.REST
        val f = pos - idx
        return MouthShape(
            a.open + (b.open - a.open) * f,
            a.wide + (b.wide - a.wide) * f,
            a.round + (b.round - a.round) * f,
        )
    }

    /** True when two shapes are close enough that updating would be wasted work. */
    fun near(a: MouthShape, b: MouthShape): Boolean =
        abs(a.open - b.open) < 0.02f && abs(a.wide - b.wide) < 0.02f && abs(a.round - b.round) < 0.02f
}

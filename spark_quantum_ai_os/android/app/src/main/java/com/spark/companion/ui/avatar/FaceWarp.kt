package com.spark.companion.ui.avatar

import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sin

/**
 * Where the eyes and mouth corners are in a portrait, as fractions of the
 * image (0..1). Enough to animate a photo: blinking, talking, smiling,
 * brows, gaze and head motion.
 */
data class FaceLandmarks(
    val leftEyeX: Float, val leftEyeY: Float,
    val rightEyeX: Float, val rightEyeY: Float,
    val mouthLeftX: Float, val mouthLeftY: Float,
    val mouthRightX: Float, val mouthRightY: Float,
) {
    val eyeDistance: Float get() = hypot(rightEyeX - leftEyeX, rightEyeY - leftEyeY).coerceAtLeast(0.02f)
    val mouthX: Float get() = (mouthLeftX + mouthRightX) / 2
    val mouthY: Float get() = (mouthLeftY + mouthRightY) / 2
    val mouthWidth: Float get() = hypot(mouthRightX - mouthLeftX, mouthRightY - mouthLeftY).coerceAtLeast(0.02f)
    val eyesX: Float get() = (leftEyeX + rightEyeX) / 2
    val eyesY: Float get() = (leftEyeY + rightEyeY) / 2
    /** Roll of the face in degrees, from the eye line. */
    val roll: Float get() = Math.toDegrees(atan2((rightEyeY - leftEyeY).toDouble(), (rightEyeX - leftEyeX).toDouble())).toFloat()

    fun toJson(): JSONObject = JSONObject()
        .put("lex", leftEyeX.toDouble()).put("ley", leftEyeY.toDouble())
        .put("rex", rightEyeX.toDouble()).put("rey", rightEyeY.toDouble())
        .put("mlx", mouthLeftX.toDouble()).put("mly", mouthLeftY.toDouble())
        .put("mrx", mouthRightX.toDouble()).put("mry", mouthRightY.toDouble())

    companion object {
        fun fromJson(j: JSONObject) = FaceLandmarks(
            j.getDouble("lex").toFloat(), j.getDouble("ley").toFloat(),
            j.getDouble("rex").toFloat(), j.getDouble("rey").toFloat(),
            j.getDouble("mlx").toFloat(), j.getDouble("mly").toFloat(),
            j.getDouble("mrx").toFloat(), j.getDouble("mry").toFloat(),
        )

        /** Typical face proportions from the eye midpoint and eye distance. */
        fun estimate(eyesX: Float, eyesY: Float, eyeDistance: Float): FaceLandmarks {
            val d = eyeDistance
            return FaceLandmarks(
                eyesX - d / 2, eyesY, eyesX + d / 2, eyesY,
                eyesX - 0.4f * d, eyesY + 1.05f * d, eyesX + 0.4f * d, eyesY + 1.05f * d,
            )
        }

        /** A sensible guess when no face was detected: a centred portrait. */
        val CENTERED = estimate(0.5f, 0.42f, 0.24f)
    }
}

/**
 * Deforms a portrait into an expression. The picture is split at the mouth
 * line into an upper and a lower mesh; lowering the jaw opens a real gap
 * between them (the mouth), while smiles, brows, gaze and head tilt are smooth
 * local warps applied to both halves alike, so there is never a seam.
 *
 * Output vertices are in the avatar's unit space: the portrait spans
 * [-frame/2, frame/2] on both axes, centred on the origin.
 */
class FaceWarp(
    val landmarks: FaceLandmarks,
    val cols: Int = 40,
    val topRows: Int = 48,
    val bottomRows: Int = 20,
    val frame: Float = 0.86f,
) {
    /** Image row (0..1) where the picture is split: the line between the lips. */
    val split: Float = landmarks.mouthY.coerceIn(0.15f, 0.95f)

    val top = FloatArray((cols + 1) * (topRows + 1) * 2)
    val bottom = FloatArray((cols + 1) * (bottomRows + 1) * 2)

    private var open = 0f
    private var smile = 0f
    private var brow = 0f
    private var lookX = 0f
    private var lookY = 0f
    private var tilt = 0f
    private var bob = 0f
    private var close = 0f

    private val d = landmarks.eyeDistance
    private val mw = landmarks.mouthWidth
    private val tmp = FloatArray(2)

    fun update(p: Pose) {
        open = p.mouthOpen
        smile = p.smile
        brow = p.brow
        lookX = p.lookX
        lookY = p.lookY
        tilt = p.tilt
        bob = p.bob
        // 1 = fully shut, a little below 0 = wide-eyed.
        close = (1f - p.eyeOpen).coerceIn(-0.2f, 1f)
        fill(top, topRows, 0f, split, lower = false)
        fill(bottom, bottomRows, split, 1f, lower = true)
    }

    private fun fill(out: FloatArray, rows: Int, v0: Float, v1: Float, lower: Boolean) {
        var k = 0
        for (r in 0..rows) {
            val v = v0 + (v1 - v0) * r / rows
            for (c in 0..cols) {
                map(c.toFloat() / cols, v, lower, tmp)
                out[k++] = tmp[0]
                out[k++] = tmp[1]
            }
        }
    }

    /**
     * Where image point (u, v) ends up, in unit space. [lower] says which
     * half the point belongs to (it matters only on the mouth line).
     */
    fun map(u: Float, v: Float, lower: Boolean, out: FloatArray) {
        val lm = landmarks
        var du = 0f
        var dv = 0f

        // Mouth: the jaw (lower half) drops; the upper lip lifts a little.
        val dx = abs(u - lm.mouthX)
        val lens = max(0f, 1f - (dx / (0.5f * mw)).pow(2)).pow(0.6f)
        if (lower) {
            val jaw = 1f - smooth(0.55f * mw, 1.6f * mw, dx)
            val t = smooth(split, split + 0.15f * d, v)
            val fade = 1f - smooth(split + 0.75f * d, split + 1.3f * d, v)
            dv += open * 0.17f * d * (lens * (1f - t) + jaw * t) * fade
        } else {
            val near = smooth(split - 0.12f * d, split, v)
            dv -= open * 0.025f * d * lens * near
        }

        // Smile / frown: the mouth corners move up and out (or down).
        for (side in intArrayOf(-1, 1)) {
            val cx = if (side < 0) lm.mouthLeftX else lm.mouthRightX
            val cy = if (side < 0) lm.mouthLeftY else lm.mouthRightY
            val g = gauss(u - cx, v - cy, 0.2f * d)
            du += side * 0.035f * d * smile * g
            dv -= 0.045f * d * smile * g
        }

        // Brows rise or knit; gaze shifts the irises.
        for (side in intArrayOf(-1, 1)) {
            val ex = if (side < 0) lm.leftEyeX else lm.rightEyeX
            val ey = if (side < 0) lm.leftEyeY else lm.rightEyeY
            dv -= 0.06f * d * brow * gauss(u - ex, v - (ey - 0.32f * d), 0.24f * d)
            val g = gauss(u - ex, v - ey, 0.08f * d)
            du += 0.03f * d * lookX * g
            dv += 0.02f * d * lookY * g
        }

        // Eyelids: the skin above each eye sweeps down while the eye itself
        // squeezes into the lash line, the way a real blink looks.
        if (close != 0f && !lower) {
            for (side in intArrayOf(-1, 1)) {
                val ex = if (side < 0) lm.leftEyeX else lm.rightEyeX
                val ey = if (side < 0) lm.leftEyeY else lm.rightEyeY
                val wx = 1f - smooth(0.2f * d, 0.34f * d, abs(u - ex))
                if (wx <= 0f) continue
                val lash = ey + 0.085f * d
                if (v >= lash) continue
                val reach = smooth(ey - 0.36f * d, ey - 0.1f * d, v)
                dv += close * (lash - v) * reach * wx * if (close < 0f) 0.25f else 1f
            }
        }

        // Head: tilt around the neck and bob, fading out towards the shoulders.
        val hx = lm.eyesX
        val hy = (lm.eyesY + lm.mouthY) / 2
        val head = 1f - smooth(1.1f * d, 1.9f * d, hypot(u - hx, v - hy))
        dv += bob / frame * 0.6f * head
        var x = u + du
        var y = v + dv
        if (tilt != 0f && head > 0f) {
            val px = lm.mouthX
            val py = lm.mouthY + 0.9f * d
            val a = Math.toRadians((tilt * head).toDouble())
            val ca = cos(a).toFloat()
            val sa = sin(a).toFloat()
            val rx = x - px
            val ry = y - py
            x = px + rx * ca - ry * sa
            y = py + rx * sa + ry * ca
        }
        out[0] = (x - 0.5f) * frame
        out[1] = (y - 0.5f) * frame
    }

    companion object {
        fun smooth(a: Float, b: Float, x: Float): Float {
            if (x <= a) return 0f
            if (x >= b) return 1f
            val t = (x - a) / (b - a)
            return t * t * (3 - 2 * t)
        }

        private fun gauss(dx: Float, dy: Float, r: Float): Float = exp(-(dx * dx + dy * dy) / (r * r))
    }
}

package com.spark.companion.ui.avatar

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * A 2D similarity transform (rotate + scale + move, no skew) that takes a
 * detected face in a source photo and places it upright and centred in a
 * square portrait. Uprighting from the real eye line is what makes tilted and
 * slightly angled photos line up. Pure math, so it is unit-tested on the JVM.
 */
class Framing private constructor(
    val a: Float, val c: Float, val tx: Float,
    val d: Float, val e: Float, val ty: Float,
    val outSize: Int,
) {
    /** Maps a source pixel to the output pixel. */
    fun mapPx(x: Float, y: Float): FloatArray = floatArrayOf(a * x + c * y + tx, d * x + e * y + ty)

    /** Maps a source pixel to a 0..1 fraction of the output. */
    fun mapFrac(x: Float, y: Float): FloatArray {
        val p = mapPx(x, y)
        return floatArrayOf(p[0] / outSize, p[1] / outSize)
    }

    /** The 9 values of the equivalent android.graphics.Matrix, for drawing. */
    fun matrixValues(): FloatArray = floatArrayOf(a, c, tx, d, e, ty, 0f, 0f, 1f)

    companion object {
        /**
         * Builds the transform from the two eye centres (in source pixels).
         * The output has the eyes horizontal, centred at [targetEyeFracY] down,
         * [targetEyeDistFrac] of the width apart.
         */
        fun forEyes(
            leftEyeX: Float, leftEyeY: Float, rightEyeX: Float, rightEyeY: Float,
            outSize: Int = FaceTools.PORTRAIT_SIZE,
            targetEyeFracY: Float = 0.40f,
            targetEyeDistFrac: Float = 0.26f,
        ): Framing {
            val eyeCx = (leftEyeX + rightEyeX) / 2f
            val eyeCy = (leftEyeY + rightEyeY) / 2f
            val dist = hypot(rightEyeX - leftEyeX, rightEyeY - leftEyeY).coerceAtLeast(1f)
            val angle = atan2(rightEyeY - leftEyeY, rightEyeX - leftEyeX)
            val s = targetEyeDistFrac * outSize / dist
            // Rotate by -angle so the eye line becomes horizontal.
            val cosA = cos(-angle)
            val sinA = sin(-angle)
            val a = s * cosA
            val c = -s * sinA
            val d = s * sinA
            val e = s * cosA
            val targetX = 0.5f * outSize
            val targetY = targetEyeFracY * outSize
            val tx = targetX - (a * eyeCx + c * eyeCy)
            val ty = targetY - (d * eyeCx + e * eyeCy)
            return Framing(a, c, tx, d, e, ty, outSize)
        }
    }
}

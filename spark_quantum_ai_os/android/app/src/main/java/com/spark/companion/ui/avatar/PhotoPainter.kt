package com.spark.companion.ui.avatar

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import kotlin.math.atan2
import kotlin.math.max
import kotlin.math.min

/**
 * Brings a portrait (photo, painting or AI-made) to life: the face is warped
 * with [FaceWarp], the mouth opens onto a drawn interior, and eyelids sampled
 * from the skin around the eyes close for blinks and sleep.
 */
class PhotoPainter(portrait: Bitmap, val landmarks: FaceLandmarks, accent: Int) : AvatarPainter(accent) {

    override val ownsHeadMotion = true

    private val warp = FaceWarp(landmarks)
    private val topHalf: Bitmap
    private val bottomHalf: Bitmap
    private val lidColors: IntArray
    private val bitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val clip = Path()
    private val cavity = Path()
    private val pt = FloatArray(2)
    private val pt2 = FloatArray(2)

    init {
        val w = portrait.width
        val h = portrait.height
        val splitRow = (warp.split * h).toInt().coerceIn(1, h - 1)
        topHalf = Bitmap.createBitmap(portrait, 0, 0, w, splitRow)
        bottomHalf = Bitmap.createBitmap(portrait, 0, splitRow, w, h - splitRow)
        val d = landmarks.eyeDistance
        lidColors = intArrayOf(
            average(portrait, landmarks.leftEyeX, landmarks.leftEyeY - 0.21f * d, 0.045f * d),
            average(portrait, landmarks.rightEyeX, landmarks.rightEyeY - 0.21f * d, 0.045f * d),
        )
    }

    /** Colour adjustment for the face (used to match a body's skin). */
    fun setFilter(filter: android.graphics.ColorFilter?) {
        bitmapPaint.colorFilter = filter
    }

    /** Size of the portrait in unit space (it spans -frame/2..frame/2). */
    val frame: Float get() = warp.frame

    /** Average skin colour of the cheeks, so a body can match the face. */
    val skinTone: Int by lazy {
        val lm = landmarks
        val d = lm.eyeDistance
        val a = average(sourceForTone, lm.leftEyeX, lm.eyesY + 0.45f * d, 0.07f * d)
        val b = average(sourceForTone, lm.rightEyeX, lm.eyesY + 0.45f * d, 0.07f * d)
        Color.rgb((Color.red(a) + Color.red(b)) / 2, (Color.green(a) + Color.green(b)) / 2, (Color.blue(a) + Color.blue(b)) / 2)
    }
    private val sourceForTone = portrait

    /** The animated face alone (no frame or background), in unit space. */
    fun drawFace(c: Canvas, p: Pose) {
        warp.update(p)
        drawMouthInside(c, p)
        c.drawBitmapMesh(topHalf, warp.cols, warp.topRows, warp.top, 0, null, 0, bitmapPaint)
        c.drawBitmapMesh(bottomHalf, warp.cols, warp.bottomRows, warp.bottom, 0, null, 0, bitmapPaint)
        drawLids(c, p)
    }

    override fun drawHead(c: Canvas, p: Pose, t: Float) {
        val r = warp.frame / 2
        c.save()
        clip.reset()
        clip.addCircle(0f, 0f, r, Path.Direction.CW)
        c.clipPath(clip)
        c.drawCircle(0f, 0f, r, solid(Color.parseColor("#0B1020")))
        drawFace(c, p)
        // Dim slightly while asleep.
        if (p.zzz > 0.02f) c.drawCircle(0f, 0f, r, solid(alpha(Color.parseColor("#050814"), 0.22f * p.zzz)))
        c.restore()
        c.drawCircle(0f, 0f, r, line(alpha(accent, 0.85f), 0.012f))
    }

    /** The dark mouth, teeth and tongue that show through the gap between the halves. */
    private fun drawMouthInside(c: Canvas, p: Pose) {
        if (p.mouthOpen < 0.02f) return
        val lm = landmarks
        val d = lm.eyeDistance
        val steps = 10
        cavity.reset()
        // Upper edge (slightly inside the top half), left to right.
        for (i in 0..steps) {
            val u = lm.mouthLeftX + (lm.mouthRightX - lm.mouthLeftX) * i / steps
            val v = lm.mouthLeftY + (lm.mouthRightY - lm.mouthLeftY) * i / steps
            warp.map(u, v - 0.02f * d, false, pt)
            if (i == 0) cavity.moveTo(pt[0], pt[1]) else cavity.lineTo(pt[0], pt[1])
        }
        // Lower edge, right to left.
        for (i in steps downTo 0) {
            val u = lm.mouthLeftX + (lm.mouthRightX - lm.mouthLeftX) * i / steps
            val v = lm.mouthLeftY + (lm.mouthRightY - lm.mouthLeftY) * i / steps
            warp.map(u, v + 0.02f * d, true, pt)
            cavity.lineTo(pt[0], pt[1])
        }
        cavity.close()
        c.drawPath(cavity, solid(Color.parseColor("#2A0A0E")))

        warp.map(lm.mouthX, lm.mouthY, false, pt)
        warp.map(lm.mouthX, lm.mouthY, true, pt2)
        val gap = pt2[1] - pt[1]
        if (gap <= 0f) return
        val halfWidth = lm.mouthWidth * warp.frame * 0.45f
        c.save()
        c.clipPath(cavity)
        if (p.mouthOpen > 0.25f) {
            // Upper teeth, tucked up under the lip so no dark seam shows.
            val teethAlpha = ((p.mouthOpen - 0.25f) / 0.3f).coerceIn(0f, 1f)
            rect.set(pt[0] - halfWidth * 0.8f, pt[1] - 0.04f * d * warp.frame, pt[0] + halfWidth * 0.8f, pt[1] + gap * 0.28f)
            c.drawRoundRect(rect, gap * 0.14f, gap * 0.14f, solid(alpha(Color.parseColor("#E4DDD4"), teethAlpha)))
        }
        oval(c, pt2[0], pt2[1] - gap * 0.05f, halfWidth * 0.7f, gap * 0.35f, solid(Color.parseColor("#A84450")))
        c.restore()
    }

    /** The warp closes the eyes; this adds a soft lash shadow along the closed lid. */
    private fun drawLids(c: Canvas, p: Pose) {
        val shut = (1f - p.eyeOpen).coerceIn(0f, 1f)
        if (shut < 0.6f) return
        val lm = landmarks
        val d = lm.eyeDistance
        for (side in 0..1) {
            val ex = if (side == 0) lm.leftEyeX else lm.rightEyeX
            val ey = if (side == 0) lm.leftEyeY else lm.rightEyeY
            warp.map(ex - 0.21f * d, ey + 0.08f * d, false, pt)
            warp.map(ex + 0.21f * d, ey + 0.08f * d, false, pt2)
            val midX = (pt[0] + pt2[0]) / 2
            val midY = (pt[1] + pt2[1]) / 2
            path.reset()
            path.moveTo(pt[0], pt[1])
            path.quadTo(midX, midY + 0.02f * d * warp.frame, pt2[0], pt2[1])
            val strength = (shut - 0.6f) / 0.4f
            c.drawPath(path, line(alpha(darken(lidColors[side], 0.5f), 0.3f * strength), 0.045f * d * warp.frame))
            c.drawPath(path, line(alpha(darken(lidColors[side], 0.35f), 0.7f * strength), 0.018f * d * warp.frame))
        }
    }

    companion object {
        /** Average colour of a small square of the image (fractions of size). */
        fun average(b: Bitmap, u: Float, v: Float, radius: Float): Int {
            val x0 = max(0, ((u - radius) * b.width).toInt())
            val x1 = min(b.width - 1, ((u + radius) * b.width).toInt())
            val y0 = max(0, ((v - radius) * b.height).toInt())
            val y1 = min(b.height - 1, ((v + radius) * b.height).toInt())
            var r = 0L
            var g = 0L
            var bl = 0L
            var n = 0L
            var y = y0
            while (y <= y1) {
                var x = x0
                while (x <= x1) {
                    val px = b.getPixel(x, y)
                    r += Color.red(px); g += Color.green(px); bl += Color.blue(px); n++
                    x++
                }
                y++
            }
            if (n == 0L) return Color.rgb(200, 160, 130)
            return Color.rgb((r / n).toInt(), (g / n).toInt(), (bl / n).toInt())
        }
    }
}

package com.spark.companion.ui.avatar

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RadialGradient
import android.graphics.Shader

/**
 * The face-swap part of a full-body avatar: the face from an uploaded photo,
 * animated with [FaceWarp] (talking, blinking, expressions), scaled and lined
 * up to the avatar's eyes, and blended into the head with a soft oval edge so
 * no photo background or hair shows. [skinTone] lets the body match the face.
 */
class PhotoFace(portrait: Bitmap, val landmarks: FaceLandmarks) {
    private val painter = PhotoPainter(portrait, landmarks, Color.WHITE)
    private val mask = Paint(Paint.ANTI_ALIAS_FLAG).apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN) }

    /** Tints the face towards [target] skin so a swapped face matches the body it's on. */
    fun matchSkin(target: Int) {
        val src = skinTone
        fun k(t: Int, s: Int) = (t.toFloat() / s.coerceAtLeast(1)).coerceIn(0.7f, 1.4f)
        // Blend halfway: keep the person's own colouring, close most of the gap.
        val r = 1f + (k(Color.red(target), Color.red(src)) - 1f) * 0.7f
        val g = 1f + (k(Color.green(target), Color.green(src)) - 1f) * 0.7f
        val b = 1f + (k(Color.blue(target), Color.blue(src)) - 1f) * 0.7f
        painter.setFilter(android.graphics.ColorMatrixColorFilter(android.graphics.ColorMatrix().apply { setScale(r, g, b, 1f) }))
    }

    /** The face's own skin colour (cheeks). */
    val skinTone: Int get() = painter.skinTone

    /** The photo's hair colour, sampled above the forehead. */
    val hairTone: Int by lazy {
        val lm = landmarks
        PhotoPainter.average(source, lm.eyesX, (lm.eyesY - 1.3f * lm.eyeDistance).coerceAtLeast(0.02f), 0.12f * lm.eyeDistance)
    }
    private val source = portrait

    /**
     * Draws the face with its eyes centred at (0, [eyeY]) and [eyeDistance]
     * apart, in the caller's units. Head tilt and bob are left to the caller.
     */
    fun draw(c: Canvas, p: Pose, eyeY: Float, eyeDistance: Float) {
        val lm = landmarks
        val frame = painter.frame
        val d = lm.eyeDistance * frame
        val k = eyeDistance / d
        val ex = (lm.eyesX - 0.5f) * frame
        val ey = (lm.eyesY - 0.5f) * frame
        val mcx = ex
        val mcy = ey + 0.36f * d
        val rx = 0.98f * d
        val ry = 1.16f * d

        c.save()
        c.translate(0f, eyeY)
        c.scale(k, k)
        c.translate(-ex, -ey)
        // The layer only needs the face; everything outside it is cleared by the mask anyway.
        val layer = c.saveLayer(mcx - rx * 1.2f, mcy - ry * 1.2f, mcx + rx * 1.2f, mcy + ry * 1.2f, null)
        // The body turns the head, so the face itself stays upright here.
        val tilt = p.tilt
        val bob = p.bob
        p.tilt = 0f
        p.bob = 0f
        painter.drawFace(c, p)
        p.tilt = tilt
        p.bob = bob
        // Feathered oval: keeps the face, fades out hair and background.
        mask.shader = RadialGradient(mcx, mcy, rx, intArrayOf(Color.BLACK, Color.BLACK, Color.TRANSPARENT), floatArrayOf(0f, 0.74f, 1f), Shader.TileMode.CLAMP)
        // DST_IN only touches pixels the mask covers, so it must cover the whole layer
        // (the gradient is transparent outside the oval, which clears the rest).
        c.save()
        c.scale(1f, ry / rx, mcx, mcy)
        val far = frame * 2f
        c.drawRect(mcx - far, mcy - far, mcx + far, mcy + far, mask)
        c.restore()
        c.restoreToCount(layer)
        c.restore()
    }
}

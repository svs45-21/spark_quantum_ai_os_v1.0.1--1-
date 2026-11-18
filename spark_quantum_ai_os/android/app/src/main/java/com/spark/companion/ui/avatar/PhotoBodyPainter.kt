package com.spark.companion.ui.avatar

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/** A painter that draws a whole standing figure (figure space: y 0 = top, 1 = feet). */
interface StandingPainter {
    /** Height of the eyes in figure space, for effects around the head. */
    val headCenterY: Float
    fun drawFigure(c: Canvas, p: Pose, t: Float, bust: Boolean = false)
}

/**
 * A photo-realistic full-body avatar brought to life. The picture is drawn on
 * a mesh that breathes, shifts its weight and turns the head; on top, the face
 * (its own, or a swapped-in face from your photo) blinks, talks with mouth
 * shapes and shows every feeling through [PhotoFace].
 *
 * [face] holds the eye and mouth positions as fractions of the picture's width
 * (x) and height (y).
 */
class PhotoBodyPainter(
    private val body: Bitmap,
    private val face: FaceLandmarks,
    accent: Int,
    swapFace: PhotoFace? = null,
) : AvatarPainter(accent), StandingPainter {

    override val ownsHeadMotion = true

    private val w = body.width.toFloat()
    private val h = body.height.toFloat()
    private val eyeX = face.eyesX * w
    private val eyeY = face.eyesY * h
    private val eyeDist = hypot((face.rightEyeX - face.leftEyeX) * w, (face.rightEyeY - face.leftEyeY) * h).coerceAtLeast(2f)
    private val roll = Math.toDegrees(kotlin.math.atan2(((face.rightEyeY - face.leftEyeY) * h).toDouble(), ((face.rightEyeX - face.leftEyeX) * w).toDouble())).toFloat()

    // Body landmarks estimated from the face.
    private val neckY = eyeY + 1.9f * eyeDist
    private val shoulderY = eyeY + 2.8f * eyeDist
    private val hipY = maxOf(h * 0.52f, eyeY + 7f * eyeDist).coerceAtMost(h * 0.62f)

    private val cols = 16
    private val rows = 40
    private val verts = FloatArray((cols + 1) * (rows + 1) * 2)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val shadow = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pt = FloatArray(2)

    /** The animated face: the body's own, cropped from the picture, or a swapped-in one. */
    private val faceLayer: PhotoFace = swapFace?.also { it.matchSkin(skinOfBody()) } ?: ownFace()

    /** A swapped-in face comes upright, so it is turned to the body's head angle. */
    private val faceRoll = if (swapFace != null) roll else 0f

    override val headCenterY: Float get() = face.eyesY

    private fun ownFace(): PhotoFace {
        // A square crop around the head, so the face can be warped in detail.
        val side = (eyeDist * 4.4f).toInt().coerceAtLeast(16)
        val cx = eyeX
        val cy = eyeY + 0.45f * eyeDist
        val left = (cx - side / 2f).toInt()
        val top = (cy - side / 2f).toInt()
        val crop = Bitmap.createBitmap(side, side, Bitmap.Config.ARGB_8888)
        Canvas(crop).drawBitmap(body, -left.toFloat(), -top.toFloat(), null)
        fun fx(x: Float) = (x * w - left) / side
        fun fy(y: Float) = (y * h - top) / side
        val lm = FaceLandmarks(
            fx(face.leftEyeX), fy(face.leftEyeY), fx(face.rightEyeX), fy(face.rightEyeY),
            fx(face.mouthLeftX), fy(face.mouthLeftY), fx(face.mouthRightX), fy(face.mouthRightY),
        )
        return PhotoFace(crop, lm)
    }

    /** Average skin colour on the body's cheeks. */
    private fun skinOfBody(): Int {
        val a = PhotoPainter.average(body, face.leftEyeX, face.eyesY + 0.45f * eyeDist / h, 0.07f * eyeDist / w)
        val b = PhotoPainter.average(body, face.rightEyeX, face.eyesY + 0.45f * eyeDist / h, 0.07f * eyeDist / w)
        return Color.rgb((Color.red(a) + Color.red(b)) / 2, (Color.green(a) + Color.green(b)) / 2, (Color.blue(a) + Color.blue(b)) / 2)
    }

    // ── Motion ───────────────────────────────────────────────────────────

    private var headAngle = 0f
    private var nod = 0f
    private var bodyAngle = 0f
    private var swayPx = 0f
    private var breath = 0f
    private var bob = 0f

    private fun setMotion(p: Pose) {
        headAngle = p.tilt * 0.6f
        nod = (p.pitch / 15f).coerceIn(-1f, 1f) * eyeDist * 0.08f
        bodyAngle = p.lean * 0.35f
        swayPx = p.sway * h * 0.0035f
        breath = p.breath
        bob = p.bob * eyeDist * 0.6f
    }

    private fun smooth(a: Float, b: Float, x: Float): Float {
        if (x <= a) return 0f
        if (x >= b) return 1f
        val k = (x - a) / (b - a)
        return k * k * (3 - 2 * k)
    }

    /** Where picture point (x, y) (pixels) moves to this frame (pixels). */
    private fun move(x: Float, y: Float, out: FloatArray) {
        var nx = x
        var ny = y
        // Breathing: the chest rises and widens a touch; everything above lifts with it.
        val chest = smooth(hipY, shoulderY, y)
        ny -= breath * h * 0.0022f * chest
        nx += (x - w / 2f) * 0.006f * breath * smooth(hipY, shoulderY - eyeDist, y) * (1f - smooth(shoulderY - eyeDist, neckY - eyeDist, y))

        // Head: turns around the neck, with a nod and a little bob.
        val headW = (1f - smooth(neckY - 0.2f * eyeDist, neckY + 0.7f * eyeDist, y)) *
            (1f - smooth(2.4f * eyeDist, 3.4f * eyeDist, kotlin.math.abs(x - eyeX)))
        if (headW > 0f) {
            val a = Math.toRadians((headAngle * headW).toDouble())
            val rx = nx - eyeX
            val ry = ny - neckY
            nx = eyeX + (rx * cos(a) - ry * sin(a)).toFloat()
            ny = neckY + (rx * sin(a) + ry * cos(a)).toFloat()
            ny += (nod + bob) * headW
        }

        // Upper body leans and shifts weight over planted feet.
        val upper = smooth(h * 0.9f, hipY - eyeDist, y)
        if (upper > 0f) {
            val a = Math.toRadians((bodyAngle * upper).toDouble())
            val px = w / 2f
            val rx = nx - px
            val ry = ny - hipY
            nx = px + (rx * cos(a) - ry * sin(a)).toFloat() + swayPx * upper
            ny = hipY + (rx * sin(a) + ry * cos(a)).toFloat()
        }
        out[0] = nx
        out[1] = ny
    }

    // ── Drawing ──────────────────────────────────────────────────────────

    override fun drawFigure(c: Canvas, p: Pose, t: Float, bust: Boolean) {
        setMotion(p)
        if (!bust) {
            // Soft shadow on the floor.
            shadow.shader = RadialGradient(0f, 1f, 0.2f, intArrayOf(Color.argb(110, 0, 0, 0), Color.TRANSPARENT), null, Shader.TileMode.CLAMP)
            c.save()
            c.scale(1f, 0.16f, 0f, 1f)
            c.drawCircle(0f, 1f, 0.2f, shadow)
            c.restore()
            shadow.shader = null
        }
        // Figure space: unit = picture height, x centred on the picture.
        c.save()
        c.scale(1f / h, 1f / h)
        c.translate(-w / 2f, 0f)
        var k = 0
        for (r in 0..rows) {
            val y = h * r / rows
            for (col in 0..cols) {
                move(w * col / cols, y, pt)
                verts[k++] = pt[0]
                verts[k++] = pt[1]
            }
        }
        c.drawBitmapMesh(body, cols, rows, verts, 0, null, 0, paint)

        // The living face, placed exactly where the head moved to.
        move(eyeX, eyeY, pt)
        c.translate(pt[0], pt[1])
        c.rotate(headAngle + bodyAngle * smooth(h * 0.9f, hipY - eyeDist, eyeY) + faceRoll)
        faceLayer.draw(c, p, eyeY = 0f, eyeDistance = eyeDist)
        c.restore()
    }

    override fun drawHead(c: Canvas, p: Pose, t: Float) {
        // Head-and-shoulders for small round places.
        val k = 0.2f * h / eyeDist
        c.save()
        c.translate(0f, -0.06f)
        c.scale(k, k)
        c.translate(-(eyeX - w / 2f) / h, -eyeY / h)
        drawFigure(c, p, t, bust = true)
        c.restore()
    }
}

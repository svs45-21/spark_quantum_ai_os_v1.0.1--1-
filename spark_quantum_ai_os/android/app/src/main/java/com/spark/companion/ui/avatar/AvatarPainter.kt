package com.spark.companion.ui.avatar

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

/**
 * Draws one character from a [Pose]. Painters work in a unit space: the
 * canvas is already translated to the head centre and scaled so that 1.0
 * equals the avatar's size, so all coordinates below are fractions of it.
 */
abstract class AvatarPainter(val accent: Int) {
    protected val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    protected val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    protected val path = Path()
    protected val rect = RectF()

    abstract fun drawHead(c: Canvas, p: Pose, t: Float)

    /** True when the painter tilts and bobs the head itself (photo avatars warp it). */
    open val ownsHeadMotion: Boolean = false

    protected fun alpha(color: Int, a: Float): Int =
        Color.argb((a.coerceIn(0f, 1f) * 255).toInt(), Color.red(color), Color.green(color), Color.blue(color))

    protected fun oval(c: Canvas, cx: Float, cy: Float, rx: Float, ry: Float, paint: Paint) {
        rect.set(cx - rx, cy - ry, cx + rx, cy + ry)
        c.drawOval(rect, paint)
    }

    protected fun solid(color: Int): Paint = fill.apply { shader = null; this.color = color }

    protected fun line(color: Int, width: Float): Paint = stroke.apply { shader = null; this.color = color; strokeWidth = width }

    companion object {
        fun of(style: AvatarStyle): AvatarPainter = when (style) {
            AvatarStyle.NOVA -> NovaPainter(style.accent)
            AvatarStyle.MIRA -> MiraPainter(style.accent)
            AvatarStyle.KIT -> KitPainter(style.accent)
        }
    }
}

// ── Nova: a sleek robot ─────────────────────────────────────────────────────

class NovaPainter(accent: Int) : AvatarPainter(accent) {
    private val shell = Paint(Paint.ANTI_ALIAS_FLAG)
    private val shellShader = LinearGradient(0f, -0.3f, 0f, 0.28f,
        Color.parseColor("#36446A"), Color.parseColor("#141C30"), Shader.TileMode.CLAMP)

    override fun drawHead(c: Canvas, p: Pose, t: Float) {
        val lit = 0.35f + 0.65f * p.eyeOpen.coerceAtMost(1f)

        // Antenna: leans when sleepy, the bulb glows with the voice.
        c.save()
        c.rotate((1f - p.perk) * -14f, 0f, -0.28f)
        c.drawLine(0f, -0.28f, 0f, -0.39f, line(Color.parseColor("#8A97B8"), 0.014f))
        oval(c, 0f, -0.42f, 0.05f, 0.05f, solid(alpha(accent, 0.18f + 0.3f * p.glow)))
        oval(c, 0f, -0.42f, 0.028f, 0.028f, solid(alpha(accent, 0.45f + 0.55f * lit)))
        c.restore()

        // Ears.
        for (side in intArrayOf(-1, 1)) {
            oval(c, side * 0.325f, 0f, 0.055f, 0.075f, solid(Color.parseColor("#1F2A44")))
            oval(c, side * 0.325f, 0f, 0.028f, 0.042f, solid(alpha(accent, 0.3f + 0.5f * lit)))
        }

        // Head shell.
        shell.shader = shellShader
        rect.set(-0.31f, -0.3f, 0.31f, 0.27f)
        c.drawRoundRect(rect, 0.13f, 0.13f, shell)
        c.drawRoundRect(rect, 0.13f, 0.13f, line(alpha(accent, 0.55f), 0.008f))
        // Top shine.
        rect.set(-0.2f, -0.27f, 0.12f, -0.22f)
        c.drawRoundRect(rect, 0.03f, 0.03f, solid(alpha(Color.WHITE, 0.12f)))

        // Visor.
        rect.set(-0.25f, -0.17f, 0.25f, 0.04f)
        c.drawRoundRect(rect, 0.09f, 0.09f, solid(Color.parseColor("#04070E")))
        c.drawRoundRect(rect, 0.09f, 0.09f, line(alpha(accent, 0.35f), 0.006f))

        // Eyes: glowing capsules, a thin line when shut.
        val eyeH = max(0.014f, 0.105f * p.eyeOpen)
        for (side in intArrayOf(-1, 1)) {
            val ex = side * 0.105f + p.lookX * 0.03f
            val ey = -0.065f + p.lookY * 0.022f
            rect.set(ex - 0.06f, ey - eyeH / 2 - 0.012f, ex + 0.06f, ey + eyeH / 2 + 0.012f)
            c.drawRoundRect(rect, 0.04f, 0.04f, solid(alpha(accent, 0.18f * lit)))
            rect.set(ex - 0.045f, ey - eyeH / 2, ex + 0.045f, ey + eyeH / 2)
            c.drawRoundRect(rect, 0.03f, 0.03f, solid(alpha(accent, 0.35f + 0.65f * lit)))
            if (p.eyeOpen > 0.4f) {
                oval(c, ex - 0.015f, ey - eyeH * 0.22f, 0.012f, 0.012f * p.eyeOpen, solid(alpha(Color.WHITE, 0.8f)))
            }
        }

        // Brows: light bars above the visor.
        for (side in intArrayOf(-1, 1)) {
            c.save()
            val bx = side * 0.105f
            val by = -0.215f - p.brow * 0.018f
            c.rotate(side * -p.brow * 9f, bx, by)
            c.drawLine(bx - 0.045f, by, bx + 0.045f, by, line(alpha(accent, 0.25f + 0.45f * lit), 0.016f))
            c.restore()
        }

        // Mouth: LED bars that bounce with the voice and curve with the mood.
        val heights = floatArrayOf(0.55f, 0.8f, 1f, 0.8f, 0.55f)
        for (i in 0 until 5) {
            val k = (i - 2) / 2f
            val x = k * 0.085f
            val wobble = if (p.mouthOpen > 0.1f) 0.75f + 0.25f * sin(t * 17f + i * 1.7f) else 1f
            val h = 0.012f + p.mouthOpen * 0.075f * heights[i] * wobble
            val y = 0.155f - p.smile * 0.028f * k * k
            rect.set(x - 0.014f, y - h / 2, x + 0.014f, y + h / 2)
            c.drawRoundRect(rect, 0.01f, 0.01f, solid(alpha(accent, 0.4f + 0.6f * lit)))
        }

        // Cheek lights for blush.
        if (p.blush > 0.02f) {
            for (side in intArrayOf(-1, 1)) oval(c, side * 0.215f, 0.1f, 0.03f, 0.018f, solid(alpha(Color.parseColor("#FF6FA8"), 0.7f * p.blush)))
        }
    }
}

// ── Mira: a warm cartoon girl ───────────────────────────────────────────────

class MiraPainter(accent: Int) : AvatarPainter(accent) {
    private val skin = Color.parseColor("#F5CFAE")
    private val skinShade = Color.parseColor("#E2AE88")
    private val hair = Color.parseColor("#2E1D4D")
    private val hairLight = Color.parseColor("#4B3478")
    private val lip = Color.parseColor("#C2546A")
    private val faceShader = RadialGradient(-0.06f, -0.08f, 0.36f, skin, skinShade, Shader.TileMode.CLAMP)
    private val face = Paint(Paint.ANTI_ALIAS_FLAG)
    private val clip = Path()

    override fun drawHead(c: Canvas, p: Pose, t: Float) {
        // Shoulders and neck.
        oval(c, 0f, 0.49f, 0.33f, 0.17f, solid(alpha(accent, 1f).let { darken(it, 0.45f) }))
        rect.set(-0.065f, 0.18f, 0.065f, 0.37f)
        c.drawRect(rect, solid(skinShade))

        // Hair behind the head, falling to the shoulders.
        path.reset()
        path.moveTo(-0.29f, -0.05f)
        path.cubicTo(-0.36f, -0.42f, 0.36f, -0.42f, 0.29f, -0.05f)
        path.cubicTo(0.33f, 0.16f, 0.36f, 0.33f, 0.27f, 0.4f)
        path.lineTo(-0.27f, 0.4f)
        path.cubicTo(-0.36f, 0.33f, -0.33f, 0.16f, -0.29f, -0.05f)
        path.close()
        c.drawPath(path, solid(hair))

        // Ears and face.
        for (side in intArrayOf(-1, 1)) oval(c, side * 0.235f, 0.01f, 0.035f, 0.05f, solid(skinShade))
        face.shader = faceShader
        rect.set(-0.235f, -0.27f, 0.235f, 0.27f)
        c.drawOval(rect, face)

        // Blush.
        oval(c, -0.14f, 0.065f, 0.045f, 0.025f, solid(alpha(Color.parseColor("#FF7A9A"), 0.18f + 0.45f * p.blush)))
        oval(c, 0.14f, 0.065f, 0.045f, 0.025f, solid(alpha(Color.parseColor("#FF7A9A"), 0.18f + 0.45f * p.blush)))

        // Eyes.
        for (side in intArrayOf(-1, 1)) drawEye(c, side, p)

        // Brows.
        for (side in intArrayOf(-1, 1)) {
            val inner = side * 0.04f
            val outer = side * 0.15f
            val y = -0.105f - p.brow * 0.02f
            val innerLift = if (p.brow < 0) p.brow * 0.02f else 0f
            path.reset()
            path.moveTo(inner, y + innerLift)
            path.quadTo((inner + outer) / 2, y - 0.025f, outer, y + 0.012f)
            c.drawPath(path, line(hair, 0.013f))
        }

        // Nose.
        path.reset()
        path.moveTo(-0.012f, 0.06f)
        path.quadTo(0f, 0.072f, 0.012f, 0.06f)
        c.drawPath(path, line(skinShade, 0.008f))

        drawMouth(c, p)

        // Bangs over the forehead.
        path.reset()
        path.moveTo(-0.255f, 0.0f)
        path.cubicTo(-0.30f, -0.36f, 0.30f, -0.38f, 0.255f, -0.02f)
        path.quadTo(0.20f, -0.17f, 0.07f, -0.19f)
        path.quadTo(0.03f, -0.13f, -0.04f, -0.2f)
        path.quadTo(-0.16f, -0.18f, -0.255f, 0.0f)
        path.close()
        c.drawPath(path, solid(hair))
        path.reset()
        path.moveTo(-0.17f, -0.25f)
        path.quadTo(-0.05f, -0.31f, 0.1f, -0.29f)
        c.drawPath(path, line(alpha(hairLight, 0.9f), 0.014f))

        // A small star hair clip in the accent colour.
        drawStar(c, 0.19f, -0.2f, 0.035f, solid(accent))
    }

    private fun drawEye(c: Canvas, side: Int, p: Pose) {
        val ex = side * 0.095f
        val ey = -0.025f
        val rx = 0.056f
        val ry = 0.047f
        val open = p.eyeOpen.coerceIn(0f, 1.2f)
        if (open < 0.1f) {
            // Closed: a gentle curve with lashes.
            path.reset()
            path.moveTo(ex - rx, ey)
            path.quadTo(ex, ey + 0.028f, ex + rx, ey)
            c.drawPath(path, line(hair, 0.011f))
            c.drawLine(ex + side * rx, ey, ex + side * (rx + 0.015f), ey - 0.008f, line(hair, 0.009f))
            return
        }
        c.save()
        clip.reset()
        rect.set(ex - rx, ey - ry, ex + rx, ey + ry)
        clip.addOval(rect, Path.Direction.CW)
        c.clipPath(clip)
        c.drawOval(rect, solid(Color.WHITE))
        val ix = ex + p.lookX * 0.018f
        val iy = ey + p.lookY * 0.012f + 0.004f
        oval(c, ix, iy, 0.031f, 0.031f, solid(darken(accent, 0.75f)))
        oval(c, ix, iy, 0.024f, 0.024f, solid(accent))
        oval(c, ix, iy, 0.013f, 0.013f, solid(Color.parseColor("#120A24")))
        oval(c, ix - 0.011f, iy - 0.012f, 0.008f, 0.008f, solid(Color.WHITE))
        // Eyelid comes down from the top.
        val lid = ey - ry + 2 * ry * (1f - open.coerceAtMost(1f))
        rect.set(ex - rx - 0.01f, ey - ry - 0.01f, ex + rx + 0.01f, lid)
        c.drawRect(rect, solid(skin))
        c.restore()
        // Upper lash line follows the lid.
        path.reset()
        path.moveTo(ex - rx - 0.004f, ey - 0.004f)
        path.quadTo(ex, lid - 0.02f * open, ex + rx + 0.004f, ey - 0.004f)
        c.drawPath(path, line(hair, 0.012f))
        c.drawLine(ex + side * (rx + 0.002f), ey - 0.006f, ex + side * (rx + 0.018f), ey - 0.02f, line(hair, 0.009f))
    }

    private fun drawMouth(c: Canvas, p: Pose) {
        val y = 0.125f
        val w = 0.048f + 0.012f * p.smile.coerceAtLeast(0f)
        if (p.mouthOpen < 0.07f) {
            path.reset()
            path.moveTo(-w, y - p.smile * 0.012f)
            path.quadTo(0f, y + p.smile * 0.035f, w, y - p.smile * 0.012f)
            c.drawPath(path, line(lip, 0.011f))
            return
        }
        val h = 0.012f + 0.07f * p.mouthOpen
        path.reset()
        path.moveTo(-w, y - p.smile * 0.01f)
        path.quadTo(0f, y - 0.006f + p.smile * 0.012f, w, y - p.smile * 0.01f)
        path.quadTo(0f, y + h + p.smile * 0.02f, -w, y - p.smile * 0.01f)
        path.close()
        c.drawPath(path, solid(Color.parseColor("#6E1F33")))
        if (p.mouthOpen > 0.3f) oval(c, 0f, y + h * 0.6f, w * 0.45f, h * 0.22f, solid(Color.parseColor("#E07A8A")))
        c.drawPath(path, line(lip, 0.008f))
    }

    private fun drawStar(c: Canvas, cx: Float, cy: Float, r: Float, paint: Paint) {
        path.reset()
        for (i in 0 until 10) {
            val a = -PI / 2 + i * PI / 5
            val rr = if (i % 2 == 0) r else r * 0.45f
            val x = cx + (rr * cos(a)).toFloat()
            val y = cy + (rr * sin(a)).toFloat()
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        path.close()
        c.drawPath(path, paint)
    }
}

// ── Kit: a playful fox ──────────────────────────────────────────────────────

class KitPainter(accent: Int) : AvatarPainter(accent) {
    private val fur = Color.parseColor("#F58A34")
    private val furDark = Color.parseColor("#C9601C")
    private val cream = Color.parseColor("#FFF3E4")
    private val ink = Color.parseColor("#2A1A12")
    private val head = Paint(Paint.ANTI_ALIAS_FLAG)
    private val headShader = LinearGradient(0f, -0.28f, 0f, 0.26f, Color.parseColor("#FFA552"), fur, Shader.TileMode.CLAMP)

    override fun drawHead(c: Canvas, p: Pose, t: Float) {
        // Ears perk up when awake and droop when asleep; they twitch now and then.
        val twitch = if (p.perk > 0.6f) 6f * max(0f, sin(t * 2.3f) - 0.92f) * 12f else 0f
        for (side in intArrayOf(-1, 1)) {
            c.save()
            c.rotate(side * ((1f - p.perk) * 28f + if (side == 1) twitch else 0f), side * 0.17f, -0.17f)
            path.reset()
            path.moveTo(side * 0.07f, -0.2f)
            path.lineTo(side * 0.27f, -0.46f)
            path.lineTo(side * 0.29f, -0.1f)
            path.close()
            c.drawPath(path, solid(fur))
            path.reset()
            path.moveTo(side * 0.12f, -0.21f)
            path.lineTo(side * 0.255f, -0.39f)
            path.lineTo(side * 0.26f, -0.15f)
            path.close()
            c.drawPath(path, solid(Color.parseColor("#FFD3AE")))
            path.reset()
            path.moveTo(side * 0.215f, -0.385f)
            path.lineTo(side * 0.27f, -0.46f)
            path.lineTo(side * 0.275f, -0.36f)
            path.close()
            c.drawPath(path, solid(ink))
            c.restore()
        }

        // Cheek fluff, then the head.
        for (side in intArrayOf(-1, 1)) {
            path.reset()
            path.moveTo(side * 0.24f, -0.02f)
            path.lineTo(side * 0.38f, 0.11f)
            path.lineTo(side * 0.27f, 0.12f)
            path.lineTo(side * 0.33f, 0.19f)
            path.lineTo(side * 0.17f, 0.2f)
            path.close()
            c.drawPath(path, solid(fur))
        }
        head.shader = headShader
        rect.set(-0.29f, -0.26f, 0.29f, 0.25f)
        c.drawOval(rect, head)

        // Cream mask around the muzzle.
        oval(c, -0.085f, 0.11f, 0.12f, 0.12f, solid(cream))
        oval(c, 0.085f, 0.11f, 0.12f, 0.12f, solid(cream))
        oval(c, 0f, 0.16f, 0.1f, 0.08f, solid(cream))

        // Eyes.
        for (side in intArrayOf(-1, 1)) {
            val ex = side * 0.115f + p.lookX * 0.02f
            val ey = -0.045f + p.lookY * 0.015f
            if (p.eyeOpen < 0.1f) {
                path.reset()
                path.moveTo(ex - 0.04f, ey - 0.004f)
                path.quadTo(ex, ey + 0.026f, ex + 0.04f, ey - 0.004f)
                c.drawPath(path, line(ink, 0.013f))
            } else {
                val ry = 0.05f * p.eyeOpen.coerceAtMost(1.2f)
                oval(c, ex, ey, 0.04f, ry, solid(ink))
                oval(c, ex - 0.012f, ey - ry * 0.35f, 0.013f, 0.013f * p.eyeOpen.coerceAtMost(1f), solid(Color.WHITE))
                oval(c, ex + 0.012f, ey + ry * 0.35f, 0.006f, 0.006f * p.eyeOpen.coerceAtMost(1f), solid(alpha(Color.WHITE, 0.7f)))
            }
            // Brow tufts.
            c.save()
            val bx = side * 0.115f
            val by = -0.125f - p.brow * 0.018f
            c.rotate(side * -p.brow * 12f, bx, by)
            c.drawLine(bx - 0.03f, by, bx + 0.03f, by, line(furDark, 0.014f))
            c.restore()
        }

        // Nose.
        path.reset()
        path.moveTo(-0.032f, 0.06f)
        path.quadTo(0f, 0.045f, 0.032f, 0.06f)
        path.quadTo(0.01f, 0.095f, 0f, 0.095f)
        path.quadTo(-0.01f, 0.095f, -0.032f, 0.06f)
        path.close()
        c.drawPath(path, solid(ink))
        oval(c, -0.01f, 0.058f, 0.01f, 0.005f, solid(alpha(Color.WHITE, 0.5f)))

        // Mouth.
        if (p.mouthOpen < 0.07f) {
            c.drawLine(0f, 0.095f, 0f, 0.115f, line(ink, 0.009f))
            path.reset()
            path.moveTo(-0.055f, 0.11f - p.smile * 0.012f)
            path.quadTo(-0.028f, 0.115f + p.smile * 0.025f, 0f, 0.115f)
            path.quadTo(0.028f, 0.115f + p.smile * 0.025f, 0.055f, 0.11f - p.smile * 0.012f)
            c.drawPath(path, line(ink, 0.009f))
        } else {
            val h = 0.015f + 0.065f * p.mouthOpen
            path.reset()
            path.moveTo(-0.045f, 0.11f)
            path.quadTo(0f, 0.1f, 0.045f, 0.11f)
            path.quadTo(0f, 0.11f + h * 1.6f, -0.045f, 0.11f)
            path.close()
            c.drawPath(path, solid(Color.parseColor("#5A1E1A")))
            if (p.mouthOpen > 0.3f) oval(c, 0f, 0.11f + h * 0.75f, 0.022f, h * 0.25f, solid(Color.parseColor("#F07B7B")))
        }

        // Whiskers.
        for (side in intArrayOf(-1, 1)) {
            for (k in 0 until 2) {
                val y = 0.1f + k * 0.03f
                c.drawLine(side * 0.12f, y, side * 0.24f, y - 0.02f + k * 0.03f, line(alpha(ink, 0.35f), 0.006f))
            }
        }

        // Blush.
        if (p.blush > 0.02f) {
            for (side in intArrayOf(-1, 1)) oval(c, side * 0.19f, 0.06f, 0.035f, 0.02f, solid(alpha(Color.parseColor("#FF5E7E"), 0.55f * p.blush)))
        }
    }
}

internal fun darken(color: Int, factor: Float): Int = Color.rgb(
    (Color.red(color) * factor).toInt(), (Color.green(color) * factor).toInt(), (Color.blue(color) * factor).toInt()
)

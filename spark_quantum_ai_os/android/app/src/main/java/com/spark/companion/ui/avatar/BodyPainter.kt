package com.spark.companion.ui.avatar

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Shader
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * A full-body avatar, standing in open space: body, outfit, hair and face,
 * with breathing, weight shifts, gestures and expressions driven by [Pose].
 *
 * Figure space: x is centred on the body, y runs from 0 at the top of the
 * head to 1 at the soles, so 1 unit = the figure's height.
 *
 * When [face] is given, the uploaded photo's face is placed on the head
 * (a face swap) and the skin is matched to it.
 */
class BodyPainter(
    val spec: BodySpec,
    accent: Int,
    private val face: PhotoFace? = null,
) : AvatarPainter(accent), StandingPainter {

    override val ownsHeadMotion = true

    private val outfit = spec.outfit
    private val female = spec.gender == Gender.FEMALE
    private val skin = if (spec.skinFromFace && face != null) face.skinTone else spec.skin
    private val skinDark = darken(skin, 0.18f)
    private val skinShadow = darken(skin, 0.32f)
    private val hairColor = if (spec.skinFromFace && face != null) face.hairTone else spec.hairColor
    private val hairDark = darken(hairColor, 0.35f)
    private val hairLight = lighten(hairColor, 0.25f)

    // ── Proportions (figure units) ───────────────────────────────────────
    private val headY = 0.088f
    private val headRx = if (female) 0.053f else 0.057f
    private val headRy = if (female) 0.067f else 0.07f
    private val neckW = if (female) 0.02f else 0.026f
    private val shoulderW = if (female) 0.104f else 0.127f
    private val baseShoulderY = 0.198f
    private val waistW = if (female) 0.068f else 0.09f
    private val hipW = if (female) 0.104f else 0.095f
    private val upperLen = if (female) 0.158f else 0.166f
    private val foreLen = if (female) 0.148f else 0.155f
    private val upperThick = if (female) 0.039f else 0.048f
    private val foreThick = if (female) 0.031f else 0.039f

    private val shaded = Paint(Paint.ANTI_ALIAS_FLAG)
    private val clipPath = Path()
    private val tmp = Path()
    private val joints = FloatArray(12)

    /** Head centre in figure units, for effects drawn around the avatar. */
    override val headCenterY: Float get() = headY

    // ── Entry points ─────────────────────────────────────────────────────

    /**
     * Bust view for small round places (the floating bubble, lists): head and
     * shoulders, centred like the classic avatars.
     */
    override fun drawHead(c: Canvas, p: Pose, t: Float) {
        c.save()
        val k = 2.7f
        c.translate(0f, -0.05f + p.bob)
        c.scale(k, k)
        c.translate(0f, -headY)
        drawFigure(c, p, t, bust = true)
        c.restore()
    }

    /** Draws the whole figure. The canvas is in figure space (see class docs). */
    override fun drawFigure(c: Canvas, p: Pose, t: Float, bust: Boolean) {
        val sy = baseShoulderY - 0.012f * p.shoulders
        // The upper body leans and shifts weight around the hips; legs stay planted.
        val swayX = 0.005f * p.sway
        val lean = p.lean * 0.6f

        if (!bust) {
            drawGroundShadow(c)
        }

        // Things behind the body.
        c.save()
        upperBody(c, swayX, lean)
        drawCape(c, sy, back = true)
        drawHairBack(c, p)
        drawPalluBack(c, sy)
        drawTorsoSkin(c, sy, p)
        c.restore()

        if (!bust) {
            drawLegs(c)
            drawShoes(c)
        }
        drawSkirtOrDhoti(c, t)

        c.save()
        upperBody(c, swayX, lean)
        drawTop(c, sy, t)
        drawOuter(c, sy, t)
        drawTorsoExtras(c, sy, t)
        drawHead(c, p, t, sy)
        drawArms(c, p, sy)
        c.restore()
    }

    private fun upperBody(c: Canvas, swayX: Float, lean: Float) {
        c.translate(swayX, 0f)
        c.rotate(lean, 0f, 0.5f)
    }

    // ── Ground ───────────────────────────────────────────────────────────

    private fun drawGroundShadow(c: Canvas) {
        shaded.shader = RadialGradient(0f, 1.0f, 0.2f, intArrayOf(Color.argb(110, 0, 0, 0), Color.TRANSPARENT), null, Shader.TileMode.CLAMP)
        c.save()
        c.scale(1f, 0.16f, 0f, 1f)
        c.drawCircle(0f, 1f, 0.2f, shaded)
        c.restore()
        shaded.shader = null
    }

    // ── Silhouette ───────────────────────────────────────────────────────

    /** Half-width of the bare torso at height [y]. */
    private fun torsoHalf(y: Float, sy: Float): Float {
        val pts = if (female) floatArrayOf(
            sy + 0.012f, shoulderW, 0.25f, shoulderW - 0.012f, 0.285f, 0.094f, 0.34f, 0.078f,
            0.39f, waistW, 0.45f, 0.098f, 0.49f, hipW, 0.54f, hipW * 0.96f,
        ) else floatArrayOf(
            sy + 0.012f, shoulderW, 0.25f, shoulderW - 0.008f, 0.29f, 0.114f, 0.34f, 0.102f,
            0.39f, waistW, 0.45f, 0.093f, 0.49f, hipW, 0.54f, hipW * 0.97f,
        )
        return interp(pts, y)
    }

    /** Piecewise smooth interpolation through (y, x) pairs. */
    private fun interp(pts: FloatArray, y: Float): Float {
        if (y <= pts[0]) return pts[1]
        var i = 0
        while (i + 3 < pts.size) {
            val y0 = pts[i]; val x0 = pts[i + 1]; val y1 = pts[i + 2]; val x1 = pts[i + 3]
            if (y <= y1) {
                val k = ((y - y0) / (y1 - y0)).coerceIn(0f, 1f)
                val s = k * k * (3 - 2 * k)
                return x0 + (x1 - x0) * s
            }
            i += 2
        }
        return pts[pts.size - 1]
    }

    /**
     * A garment around the torso from the shoulders down to [y1], [extra]
     * wider than the body, flaring below the hips by [flare] per unit.
     */
    private fun garmentPath(out: Path, sy: Float, y1: Float, extra: Float, flare: Float, neckline: Neck?, neckDepth: Float = 0f) {
        out.reset()
        val steps = 22
        val nw = neckW + 0.012f
        val ny = sy - 0.006f
        // Left shoulder top to neck.
        out.moveTo(-(shoulderW + extra), sy + 0.012f)
        out.quadTo(-(shoulderW * 0.6f), sy - 0.004f, -nw, ny)
        // Neckline across to the right side of the neck.
        when (neckline) {
            Neck.V -> { out.lineTo(0f, ny + 0.05f + neckDepth); out.lineTo(nw, ny) }
            Neck.SQUARE -> { out.lineTo(-nw - 0.012f, ny + 0.045f); out.lineTo(nw + 0.012f, ny + 0.045f); out.lineTo(nw, ny) }
            Neck.HIGH -> { out.lineTo(-neckW, ny - 0.035f); out.lineTo(neckW, ny - 0.035f); out.lineTo(nw, ny) }
            Neck.MANDARIN -> { out.lineTo(-neckW - 0.002f, ny - 0.012f); out.lineTo(neckW + 0.002f, ny - 0.012f); out.lineTo(nw, ny) }
            Neck.COLLAR -> { out.lineTo(0f, ny + 0.025f + neckDepth); out.lineTo(nw, ny) }
            Neck.ROUND -> out.quadTo(0f, ny + 0.03f + neckDepth, nw, ny)
            null -> out.lineTo(nw, ny)
        }
        out.quadTo(shoulderW * 0.6f, sy - 0.004f, shoulderW + extra, sy + 0.012f)
        // Down the right side.
        for (i in 1..steps) {
            val y = sy + 0.012f + (y1 - sy - 0.012f) * i / steps
            out.lineTo(widthAt(y, sy, extra, flare), y)
        }
        // Hem, then up the left side.
        for (i in steps downTo 1) {
            val y = sy + 0.012f + (y1 - sy - 0.012f) * i / steps
            out.lineTo(-widthAt(y, sy, extra, flare), y)
        }
        out.close()
    }

    private fun widthAt(y: Float, sy: Float, extra: Float, flare: Float): Float {
        val base = if (y > 0.54f) hipW * 0.96f else torsoHalf(y, sy)
        val loose = if (y > 0.47f) (y - 0.47f) * flare else 0f
        return base + extra + loose
    }

    // ── Body ─────────────────────────────────────────────────────────────

    private fun drawTorsoSkin(c: Canvas, sy: Float, p: Pose) {
        // Neck.
        rect.set(-neckW, headY + headRy * 0.6f, neckW, sy + 0.03f)
        c.drawRoundRect(rect, neckW * 0.6f, neckW * 0.6f, solid(skin))
        // Shadow under the chin.
        rect.set(-neckW, headY + headRy * 0.75f, neckW, headY + headRy * 1.15f)
        c.drawRect(rect, solid(alpha(skinShadow, 0.35f)))
        garmentPath(tmp, sy, 0.53f, 0f, 0f, null)
        c.drawPath(tmp, solid(skin))
        cylinder(c, tmp, shoulderW)
    }

    private fun legShape(out: Path, side: Int, outerExtra: Float, innerExtra: Float, bottomY: Float, ankleW: Float = 0.022f) {
        val s = side.toFloat()
        out.reset()
        val top = 0.47f
        out.moveTo(s * 0.004f, 0.53f)
        out.quadTo(s * (0.012f - innerExtra), 0.6f, s * (0.022f - innerExtra), 0.73f)
        out.quadTo(s * (0.026f - innerExtra), 0.85f, s * (0.03f - innerExtra * 0.5f), bottomY)
        out.lineTo(s * (0.03f + ankleW * 2f + outerExtra * 0.5f), bottomY)
        out.quadTo(s * (0.08f + outerExtra), 0.82f, s * (0.072f + outerExtra), 0.73f)
        out.quadTo(s * (hipW + outerExtra * 0.6f + 0.004f), 0.58f, s * (hipW + outerExtra * 0.3f), top)
        out.lineTo(s * 0.004f, top)
        out.close()
    }

    private fun drawLegs(c: Canvas) {
        val b = outfit.bottom
        // Bare legs first (skirts and shorts show them).
        for (side in intArrayOf(-1, 1)) {
            legShape(tmp, side, 0f, 0f, 0.955f)
            c.drawPath(tmp, solid(skin))
            cylinder(c, tmp, 0.05f, side * 0.05f)
        }
        val color = outfit.bottomColor
        when (b) {
            Bottom.JEANS, Bottom.TROUSERS, Bottom.JOGGERS, Bottom.CHURIDAR, Bottom.LEGGINGS -> {
                val (o, i) = when (b) {
                    Bottom.LEGGINGS, Bottom.CHURIDAR -> 0.002f to 0f
                    Bottom.JOGGERS -> 0.012f to 0.004f
                    else -> 0.009f to 0.003f
                }
                for (side in intArrayOf(-1, 1)) {
                    legShape(tmp, side, o, i, if (b == Bottom.JOGGERS) 0.945f else 0.955f, if (b == Bottom.LEGGINGS || b == Bottom.CHURIDAR) 0.02f else 0.026f)
                    c.drawPath(tmp, solid(color))
                    pattern(c, tmp, outfit.pattern.takeIf { b != Bottom.JEANS && outfit.dress.not() && it == Pattern.STRIPES } ?: Pattern.PLAIN, 0f)
                    cylinder(c, tmp, 0.06f, side * 0.05f)
                    if (b == Bottom.JEANS) {
                        // Seams and a faded knee.
                        c.drawLine(side * 0.05f, 0.5f, side * 0.052f, 0.95f, line(alpha(lighten(color, 0.35f), 0.5f), 0.0018f))
                        oval(c, side * 0.05f, 0.72f, 0.02f, 0.05f, solid(alpha(lighten(color, 0.25f), 0.25f)))
                    }
                    if (b == Bottom.CHURIDAR) {
                        for (k in 0 until 4) c.drawLine(side * 0.03f, 0.9f + k * 0.012f, side * 0.07f, 0.905f + k * 0.012f, line(alpha(darken(color, 0.3f), 0.6f), 0.002f))
                    }
                    if (b == Bottom.JOGGERS) c.drawLine(side * 0.032f, 0.935f, side * 0.082f, 0.935f, line(darken(color, 0.3f), 0.008f))
                }
                // Waistband.
                rect.set(-hipW - 0.006f, 0.455f, hipW + 0.006f, 0.49f)
                c.drawRect(rect, solid(color))
            }
            Bottom.SHORTS -> {
                for (side in intArrayOf(-1, 1)) {
                    tmp.reset()
                    val s = side.toFloat()
                    tmp.moveTo(s * 0.002f, 0.455f)
                    tmp.lineTo(s * (hipW + 0.008f), 0.455f)
                    tmp.lineTo(s * (hipW + 0.004f), 0.63f)
                    tmp.lineTo(s * 0.012f, 0.63f)
                    tmp.lineTo(s * 0.004f, 0.54f)
                    tmp.close()
                    c.drawPath(tmp, solid(color))
                    pattern(c, tmp, outfit.pattern.takeIf { !outfit.dress } ?: Pattern.PLAIN, 0f)
                    cylinder(c, tmp, 0.06f, side * 0.05f)
                }
            }
            Bottom.PALAZZO -> {
                for (side in intArrayOf(-1, 1)) {
                    tmp.reset()
                    val s = side.toFloat()
                    tmp.moveTo(s * 0.002f, 0.455f)
                    tmp.lineTo(s * (hipW + 0.006f), 0.455f)
                    tmp.quadTo(s * (hipW + 0.02f), 0.7f, s * 0.122f, 0.965f)
                    tmp.lineTo(s * 0.004f, 0.965f)
                    tmp.lineTo(s * 0.002f, 0.54f)
                    tmp.close()
                    c.drawPath(tmp, solid(color))
                    pattern(c, tmp, Pattern.PLAIN, 0f)
                    cylinder(c, tmp, 0.07f, side * 0.06f)
                    for (k in 1..3) c.drawLine(s * (0.02f + k * 0.025f), 0.62f, s * (0.02f + k * 0.03f), 0.96f, line(alpha(darken(color, 0.25f), 0.45f), 0.002f))
                }
            }
            else -> Unit // skirts and dhotis are drawn over the legs later
        }
    }

    private fun drawSkirtOrDhoti(c: Canvas, t: Float) {
        val b = outfit.bottom
        val color = outfit.bottomColor
        val top = if (outfit.dress) 0.385f else 0.43f
        val swing = 0.004f * sin(t * 1.3f)
        val (hem, hemW) = when (b) {
            Bottom.SKIRT -> 0.64f to 0.14f
            Bottom.PENCIL -> 0.7f to 0.085f
            Bottom.LONG_SKIRT -> 0.965f to 0.16f
            Bottom.LEHENGA -> 0.975f to 0.25f
            Bottom.DHOTI -> 0.88f to 0.12f
            else -> return
        }
        tmp.reset()
        val topW = if (outfit.dress) waistW + 0.006f else 0.085f
        tmp.moveTo(-topW, top)
        tmp.lineTo(topW, top)
        tmp.quadTo(hipW + 0.016f, 0.5f, hemW + swing, hem)
        // Hem with soft waves for full skirts.
        val waves = if (b == Bottom.LEHENGA || b == Bottom.LONG_SKIRT) 7 else 4
        for (i in 1..waves) {
            val x0 = hemW - 2 * hemW * (i - 0.5f) / waves
            val x1 = hemW - 2 * hemW * i / waves
            tmp.quadTo(x0 + swing, hem + 0.012f, x1 + swing, hem)
        }
        tmp.quadTo(-hipW - 0.016f, 0.5f, -topW, top)
        tmp.close()
        if (b == Bottom.DHOTI) {
            // A dhoti is draped between the legs: show the split.
            c.drawPath(tmp, solid(color))
            c.drawLine(0f, 0.58f, 0f, hem, line(alpha(darken(color, 0.3f), 0.6f), 0.004f))
        } else {
            c.drawPath(tmp, solid(color))
        }
        pattern(c, tmp, outfit.pattern, t, hemY = hem)
        // Folds.
        val folds = if (b == Bottom.LEHENGA) 9 else if (b == Bottom.PENCIL) 0 else 5
        for (i in 0 until folds) {
            val k = (i + 0.5f) / folds * 2 - 1
            c.drawLine(k * topW * 0.6f, top + 0.06f, k * hemW * 0.9f + swing, hem - 0.01f,
                line(alpha(darken(color, 0.3f), 0.35f), 0.003f))
        }
        cylinder(c, tmp, hemW)
        // Saree pleats at the front.
        if (outfit.outer == Outer.PALLU) {
            for (i in -2..2) {
                c.drawLine(i * 0.008f, 0.5f, i * 0.016f + swing, hem - 0.008f, line(alpha(darken(color, 0.35f), 0.5f), 0.0025f))
            }
        }
    }

    private fun drawShoes(c: Canvas) {
        val color = outfit.shoeColor
        for (side in intArrayOf(-1, 1)) {
            val s = side.toFloat()
            val x = s * 0.054f
            when (outfit.shoes) {
                Shoes.SNEAKERS -> {
                    oval(c, x, 0.968f, 0.033f, 0.024f, solid(color))
                    rect.set(x - 0.034f, 0.975f, x + 0.034f, 0.99f)
                    c.drawRoundRect(rect, 0.008f, 0.008f, solid(Color.WHITE))
                    c.drawLine(x - 0.012f, 0.958f, x + 0.012f, 0.958f, line(alpha(darken(color, 0.4f), 0.7f), 0.003f))
                }
                Shoes.FORMAL -> {
                    oval(c, x, 0.972f, 0.031f, 0.02f, solid(color))
                    oval(c, x - s * 0.006f, 0.966f, 0.012f, 0.006f, solid(alpha(Color.WHITE, 0.25f)))
                }
                Shoes.HEELS -> {
                    oval(c, x + s * 0.004f, 0.975f, 0.022f, 0.014f, solid(color))
                    c.drawLine(x - s * 0.012f, 0.975f, x - s * 0.014f, 0.995f, line(color, 0.005f))
                    oval(c, x, 0.958f, 0.017f, 0.012f, solid(skin))
                }
                Shoes.BOOTS -> {
                    rect.set(x - 0.028f, 0.875f, x + 0.026f, 0.975f)
                    c.drawRoundRect(rect, 0.01f, 0.01f, solid(color))
                    oval(c, x, 0.975f, 0.033f, 0.019f, solid(color))
                    rect.set(x - 0.034f, 0.982f, x + 0.034f, 0.994f)
                    c.drawRoundRect(rect, 0.005f, 0.005f, solid(darken(color, 0.4f)))
                }
                Shoes.SANDALS -> {
                    oval(c, x, 0.97f, 0.026f, 0.018f, solid(skin))
                    rect.set(x - 0.03f, 0.981f, x + 0.03f, 0.992f)
                    c.drawRoundRect(rect, 0.005f, 0.005f, solid(color))
                    c.drawLine(x - 0.022f, 0.962f, x + 0.022f, 0.97f, line(color, 0.005f))
                }
                Shoes.FLATS -> {
                    oval(c, x, 0.972f, 0.028f, 0.017f, solid(color))
                    oval(c, x, 0.962f, 0.016f, 0.008f, solid(skin))
                }
                Shoes.JUTTI -> {
                    tmp.reset()
                    tmp.moveTo(x - 0.028f, 0.975f)
                    tmp.quadTo(x, 0.952f, x + s * 0.034f, 0.962f)
                    tmp.quadTo(x + s * 0.04f, 0.95f, x + s * 0.036f, 0.958f)
                    tmp.lineTo(x + 0.028f, 0.988f)
                    tmp.lineTo(x - 0.028f, 0.988f)
                    tmp.close()
                    c.drawPath(tmp, solid(color))
                    c.drawCircle(x, 0.972f, 0.005f, solid(lighten(color, 0.5f)))
                }
            }
        }
    }

    // ── Tops ─────────────────────────────────────────────────────────────

    private fun drawTop(c: Canvas, sy: Float, t: Float) {
        val top = outfit.top
        val color = outfit.topColor
        val len = when {
            outfit.dress -> 0.44f
            outfit.bottom == Bottom.SKIRT || outfit.bottom == Bottom.PENCIL || outfit.bottom == Bottom.LONG_SKIRT ||
                outfit.bottom == Bottom.LEHENGA -> min(outfit.topLength, 0.45f)
            else -> outfit.topLength
        }
        val flare = if (top == Top.KURTA) (if (outfit.dress) 0.75f else 0.32f) else 0.05f
        val extra = when (top) {
            Top.HOODIE, Top.SWEATER -> 0.01f
            Top.KURTA, Top.SHIRT, Top.SCRUBS -> 0.006f
            Top.SPORTS, Top.TANK, Top.CROP, Top.CHOLI -> 0f
            else -> 0.004f
        }
        if (top == Top.TANK || top == Top.SPORTS && outfit.sleeve == Sleeve.NONE) {
            // Straps instead of shoulders.
            garmentPath(tmp, sy + 0.035f, len, extra, flare, outfit.neck, 0.01f)
            c.drawPath(tmp, solid(color))
            for (s in intArrayOf(-1, 1)) {
                c.drawLine(s * (neckW + 0.022f), sy + 0.004f, s * (neckW + 0.03f), sy + 0.05f, line(color, 0.012f))
            }
        } else {
            garmentPath(tmp, sy, len, extra, flare, outfit.neck)
            c.drawPath(tmp, solid(color))
        }
        clipPath.set(tmp)
        pattern(c, clipPath, outfit.pattern, t, hemY = len)
        cylinder(c, clipPath, shoulderW + extra)
        outlineOf(c, clipPath, color)

        // Details.
        when (top) {
            Top.SHIRT, Top.POLO -> {
                // Collar points and buttons.
                for (s in intArrayOf(-1, 1)) {
                    tmp.reset()
                    tmp.moveTo(s * (neckW + 0.012f), sy - 0.008f)
                    tmp.lineTo(s * 0.004f, sy + 0.032f)
                    tmp.lineTo(s * (neckW + 0.018f), sy + 0.022f)
                    tmp.close()
                    c.drawPath(tmp, solid(lighten(color, 0.08f)))
                    c.drawPath(tmp, line(alpha(darken(color, 0.35f), 0.6f), 0.0018f))
                }
                if (top == Top.SHIRT) for (k in 0 until 5) c.drawCircle(0f, sy + 0.05f + k * 0.045f, 0.0035f, solid(darken(color, 0.3f)))
                else for (k in 0 until 2) c.drawCircle(0f, sy + 0.04f + k * 0.025f, 0.003f, solid(darken(color, 0.3f)))
            }
            Top.HOODIE -> {
                // Hood folded behind the neck, pocket and strings.
                tmp.reset()
                tmp.moveTo(-neckW - 0.03f, sy - 0.002f)
                tmp.quadTo(0f, sy + 0.05f, neckW + 0.03f, sy - 0.002f)
                c.drawPath(tmp, line(darken(color, 0.15f), 0.016f))
                c.drawLine(-0.012f, sy + 0.02f, -0.014f, sy + 0.09f, line(lighten(color, 0.5f), 0.003f))
                c.drawLine(0.012f, sy + 0.02f, 0.014f, sy + 0.09f, line(lighten(color, 0.5f), 0.003f))
                rect.set(-0.06f, 0.38f, 0.06f, 0.45f)
                c.drawRoundRect(rect, 0.012f, 0.012f, line(alpha(darken(color, 0.3f), 0.6f), 0.0025f))
            }
            Top.SWEATER, Top.TURTLENECK -> {
                rect.set(-hipW - 0.01f, len - 0.02f, hipW + 0.01f, len)
                for (k in 0 until 12) {
                    val x = -hipW + k * (2 * hipW / 11)
                    c.drawLine(x, len - 0.02f, x, len, line(alpha(darken(color, 0.25f), 0.5f), 0.002f))
                }
                if (top == Top.TURTLENECK) {
                    rect.set(-neckW - 0.004f, sy - 0.048f, neckW + 0.004f, sy)
                    c.drawRoundRect(rect, 0.006f, 0.006f, solid(darken(color, 0.06f)))
                    for (k in 0 until 3) c.drawLine(-neckW, sy - 0.04f + k * 0.012f, neckW, sy - 0.04f + k * 0.012f, line(alpha(darken(color, 0.3f), 0.5f), 0.0015f))
                }
            }
            Top.KURTA -> {
                // Placket with buttons, side slits.
                c.drawLine(0f, sy, 0f, sy + 0.1f, line(alpha(darken(color, 0.3f), 0.7f), 0.003f))
                for (k in 0 until 3) c.drawCircle(0f, sy + 0.02f + k * 0.03f, 0.0035f, solid(outfit.accent))
                if (len > 0.55f) for (s in intArrayOf(-1, 1)) {
                    val w = widthAt(len, sy, extra, flare)
                    c.drawLine(s * w * 0.95f, len, s * w * 0.9f, len - 0.08f, line(alpha(darken(color, 0.35f), 0.7f), 0.003f))
                }
            }
            Top.SCRUBS -> {
                rect.set(-0.07f, 0.27f, -0.025f, 0.31f)
                c.drawRect(rect, line(alpha(darken(color, 0.3f), 0.7f), 0.0025f))
            }
            else -> Unit
        }
        if (outfit.neck == Neck.MANDARIN && top != Top.KURTA || top == Top.KURTA && outfit.neck == Neck.MANDARIN) {
            rect.set(-neckW - 0.003f, sy - 0.02f, neckW + 0.003f, sy - 0.004f)
            c.drawRoundRect(rect, 0.004f, 0.004f, solid(darken(color, 0.08f)))
        }
    }

    // ── Outer layers ─────────────────────────────────────────────────────

    private fun outerHasSleeves(o: Outer) = when (o) {
        Outer.JACKET, Outer.DENIM, Outer.LEATHER, Outer.BLAZER, Outer.COAT, Outer.CARDIGAN,
        Outer.LAB_COAT, Outer.SHERWANI, Outer.ROBE -> true
        else -> false
    }

    private fun drawOuter(c: Canvas, sy: Float, t: Float) {
        val o = outfit.outer
        val color = outfit.outerColor
        when (o) {
            Outer.NONE, Outer.CAPE -> Unit
            Outer.JACKET, Outer.DENIM, Outer.LEATHER, Outer.BLAZER, Outer.COAT, Outer.CARDIGAN, Outer.LAB_COAT, Outer.VEST, Outer.NEHRU -> {
                val len = when (o) {
                    Outer.COAT -> 0.72f; Outer.LAB_COAT -> 0.74f; Outer.BLAZER -> 0.53f; Outer.CARDIGAN -> 0.51f
                    Outer.VEST -> 0.47f; Outer.NEHRU -> 0.49f; else -> 0.48f
                }
                val open = when (o) { Outer.VEST, Outer.NEHRU -> 0.012f; Outer.BLAZER -> 0.018f; else -> 0.026f }
                val extra = if (o == Outer.VEST || o == Outer.NEHRU) 0.002f else 0.012f
                val flare = if (len > 0.6f) 0.18f else 0.05f
                for (side in intArrayOf(-1, 1)) {
                    sidePanel(tmp, side, sy, len, extra, flare, open, deepV = o != Outer.NEHRU)
                    if (o == Outer.VEST || o == Outer.NEHRU) {
                        // Sleeveless: start inside the shoulder.
                        c.save()
                        rect.set(-shoulderW + 0.012f, sy - 0.05f, shoulderW - 0.012f, 1f)
                        c.clipRect(rect)
                        c.drawPath(tmp, solid(color))
                        c.restore()
                    } else {
                        c.drawPath(tmp, solid(color))
                    }
                    pattern(c, tmp, if (o == Outer.DENIM) Pattern.PLAIN else Pattern.PLAIN, t)
                    cylinder(c, tmp, shoulderW + extra, side * shoulderW * 0.5f)
                    outlineOf(c, tmp, color)
                }
                // Lapels and details.
                when (o) {
                    Outer.BLAZER, Outer.COAT, Outer.LAB_COAT -> for (s in intArrayOf(-1, 1)) {
                        tmp.reset()
                        tmp.moveTo(s * (neckW + 0.012f), sy - 0.004f)
                        tmp.lineTo(s * (open + 0.03f), sy + 0.06f)
                        tmp.lineTo(s * (open + 0.002f), sy + 0.13f)
                        tmp.lineTo(s * open, sy + 0.02f)
                        tmp.close()
                        c.drawPath(tmp, solid(darken(color, 0.12f)))
                        c.drawPath(tmp, line(alpha(darken(color, 0.4f), 0.6f), 0.0018f))
                        if (o == Outer.LAB_COAT) {
                            rect.set(s * 0.05f - 0.018f, 0.47f, s * 0.05f + 0.018f, 0.51f)
                            c.drawRect(rect, line(alpha(darken(color, 0.25f), 0.8f), 0.002f))
                        }
                    }
                    Outer.JACKET, Outer.LEATHER -> for (s in intArrayOf(-1, 1)) {
                        c.drawLine(s * open, sy + 0.02f, s * open, len - 0.01f, line(alpha(Color.LTGRAY, 0.8f), 0.0022f))
                        tmp.reset()
                        tmp.moveTo(s * (neckW + 0.014f), sy - 0.012f)
                        tmp.lineTo(s * (open + 0.02f), sy + 0.045f)
                        tmp.lineTo(s * open, sy + 0.03f)
                        tmp.close()
                        c.drawPath(tmp, solid(darken(color, 0.15f)))
                    }
                    Outer.DENIM -> for (s in intArrayOf(-1, 1)) {
                        rect.set(s * 0.07f - 0.02f, 0.27f, s * 0.07f + 0.02f, 0.3f)
                        c.drawRect(rect, line(lighten(color, 0.4f), 0.002f))
                        c.drawLine(s * open, sy + 0.02f, s * open, len, line(lighten(color, 0.4f), 0.002f))
                        c.drawLine(-hipW, len - 0.02f, hipW, len - 0.02f, line(lighten(color, 0.4f), 0.0018f))
                    }
                    Outer.CARDIGAN -> for (k in 0 until 4) c.drawCircle(open, sy + 0.08f + k * 0.06f, 0.0035f, solid(outfit.accent))
                    Outer.VEST, Outer.NEHRU -> for (k in 0 until 4) c.drawCircle(open * 0.6f, sy + 0.06f + k * 0.055f, 0.0035f, solid(outfit.accent))
                    else -> Unit
                }
                if (o == Outer.NEHRU) {
                    rect.set(-neckW - 0.003f, sy - 0.02f, neckW + 0.003f, sy - 0.004f)
                    c.drawRoundRect(rect, 0.004f, 0.004f, solid(color))
                }
            }
            Outer.SHERWANI, Outer.ROBE -> {
                val len = if (o == Outer.ROBE) 0.86f else 0.76f
                garmentPath(tmp, sy, len, 0.014f, 0.3f, Neck.MANDARIN)
                c.drawPath(tmp, solid(color))
                clipPath.set(tmp)
                pattern(c, clipPath, outfit.pattern, t, hemY = len)
                cylinder(c, clipPath, shoulderW + 0.014f)
                outlineOf(c, clipPath, color)
                if (o == Outer.SHERWANI) {
                    rect.set(-neckW - 0.004f, sy - 0.026f, neckW + 0.004f, sy - 0.002f)
                    c.drawRoundRect(rect, 0.004f, 0.004f, solid(darken(color, 0.1f)))
                    for (k in 0 until 9) c.drawCircle(0f, sy + 0.01f + k * 0.045f, 0.004f, solid(outfit.accent))
                    c.drawLine(0f, sy, 0f, len, line(alpha(darken(color, 0.3f), 0.5f), 0.0025f))
                } else {
                    // Graduation robe: open front with a coloured stole.
                    for (s in intArrayOf(-1, 1)) {
                        c.drawLine(s * 0.035f, sy, s * 0.04f, 0.6f, line(outfit.accent, 0.016f))
                    }
                }
            }
            Outer.APRON -> {
                tmp.reset()
                tmp.moveTo(-0.055f, 0.25f)
                tmp.lineTo(0.055f, 0.25f)
                tmp.lineTo(0.065f, 0.4f)
                tmp.quadTo(0.1f, 0.42f, 0.1f, 0.47f)
                tmp.lineTo(0.09f, 0.7f)
                tmp.lineTo(-0.09f, 0.7f)
                tmp.lineTo(-0.1f, 0.47f)
                tmp.quadTo(-0.1f, 0.42f, -0.065f, 0.4f)
                tmp.close()
                c.drawPath(tmp, solid(color))
                outlineOf(c, tmp, color)
                c.drawLine(-0.05f, 0.25f, -neckW, sy - 0.01f, line(color, 0.005f))
                c.drawLine(0.05f, 0.25f, neckW, sy - 0.01f, line(color, 0.005f))
                c.drawLine(-waistW - 0.01f, 0.41f, waistW + 0.01f, 0.41f, line(outfit.accent, 0.006f))
                rect.set(-0.04f, 0.5f, 0.04f, 0.57f)
                c.drawRect(rect, line(alpha(darken(color, 0.3f), 0.6f), 0.0025f))
            }
            Outer.DUPATTA -> {
                // A soft U across the chest, ends falling behind the shoulders.
                val a = alpha(color, 0.88f)
                tmp.reset()
                tmp.moveTo(-shoulderW + 0.01f, sy + 0.005f)
                tmp.quadTo(0f, sy + 0.17f, shoulderW - 0.01f, sy + 0.005f)
                tmp.lineTo(shoulderW - 0.005f, sy + 0.035f)
                tmp.quadTo(0f, sy + 0.21f, -shoulderW + 0.005f, sy + 0.035f)
                tmp.close()
                c.drawPath(tmp, solid(a))
                tmp.reset()
                tmp.moveTo(0f, sy + 0.17f)
                tmp.quadTo(-shoulderW + 0.01f, sy + 0.02f, -shoulderW + 0.01f, sy + 0.005f)
                c.drawPath(tmp, line(outfit.accent, 0.004f))
                tmp.reset()
                tmp.moveTo(-shoulderW + 0.005f, sy + 0.035f)
                tmp.quadTo(0f, sy + 0.21f, shoulderW - 0.005f, sy + 0.035f)
                c.drawPath(tmp, line(outfit.accent, 0.004f))
            }
            Outer.PALLU -> {
                // Saree pallu: across the body from the waist up over the left shoulder.
                tmp.reset()
                tmp.moveTo(-hipW + 0.005f, 0.44f)
                tmp.quadTo(-0.02f, 0.4f, neckW + 0.004f, sy + 0.002f)
                tmp.quadTo(shoulderW * 0.6f, sy - 0.008f, shoulderW + 0.006f, sy + 0.014f)
                tmp.quadTo(shoulderW + 0.004f, sy + 0.08f, shoulderW - 0.012f, sy + 0.1f)
                tmp.quadTo(0.02f, 0.38f, -hipW + 0.05f, 0.5f)
                tmp.close()
                c.drawPath(tmp, solid(color))
                clipPath.set(tmp)
                pattern(c, clipPath, Pattern.FLORAL.takeIf { outfit.pattern == Pattern.SEQUIN } ?: Pattern.DOTS, t)
                // Gold border along the lower edge, and soft folds.
                path.reset()
                path.moveTo(shoulderW - 0.012f, sy + 0.1f)
                path.quadTo(0.02f, 0.38f, -hipW + 0.05f, 0.5f)
                c.drawPath(path, line(outfit.accent, 0.011f))
                for (k in 1..3) {
                    path.reset()
                    path.moveTo(neckW + 0.01f + k * 0.022f, sy + 0.005f)
                    path.quadTo(-0.01f + k * 0.012f, 0.36f, -hipW + 0.012f + k * 0.012f, 0.46f)
                    c.drawPath(path, line(alpha(darken(outfit.outerColor, 0.35f), 0.4f), 0.0025f))
                }
                cylinder(c, clipPath, shoulderW)
                // Pleats tucked at the waist.
                rect.set(-0.08f, 0.43f, 0.08f, 0.45f)
                c.drawRect(rect, solid(outfit.bottomColor))
            }
            Outer.SHAWL -> {
                tmp.reset()
                tmp.moveTo(shoulderW - 0.02f, sy - 0.004f)
                tmp.lineTo(shoulderW + 0.006f, sy + 0.03f)
                tmp.lineTo(0.06f, 0.6f)
                tmp.lineTo(0.025f, 0.6f)
                tmp.lineTo(neckW + 0.004f, sy)
                tmp.close()
                c.drawPath(tmp, solid(alpha(color, 0.95f)))
                c.drawLine(0.025f, 0.6f, 0.06f, 0.6f, line(outfit.accent, 0.008f))
                cylinder(c, tmp, shoulderW)
            }
        }
    }

    private fun sidePanel(out: Path, side: Int, sy: Float, len: Float, extra: Float, flare: Float, open: Float, deepV: Boolean) {
        val s = side.toFloat()
        out.reset()
        out.moveTo(s * (shoulderW + extra), sy + 0.012f)
        out.quadTo(s * shoulderW * 0.6f, sy - 0.006f, s * (neckW + 0.012f), sy - 0.008f)
        out.lineTo(s * open, if (deepV) sy + 0.11f else sy + 0.02f)
        out.lineTo(s * open, len)
        val steps = 18
        for (i in steps downTo 0) {
            val y = sy + 0.012f + (len - sy - 0.012f) * i / steps
            out.lineTo(s * widthAt(y, sy, extra, flare), y)
        }
        out.close()
    }

    private fun drawCape(c: Canvas, sy: Float, back: Boolean) {
        if (outfit.outer != Outer.CAPE || !back) return
        tmp.reset()
        tmp.moveTo(-shoulderW, sy)
        tmp.lineTo(shoulderW, sy)
        tmp.quadTo(shoulderW + 0.08f, 0.6f, shoulderW + 0.12f, 0.9f)
        tmp.quadTo(0f, 0.93f, -shoulderW - 0.12f, 0.9f)
        tmp.quadTo(-shoulderW - 0.08f, 0.6f, -shoulderW, sy)
        tmp.close()
        c.drawPath(tmp, solid(outfit.outerColor))
        cylinder(c, tmp, shoulderW + 0.12f)
    }

    private fun drawPalluBack(c: Canvas, sy: Float) {
        if (outfit.outer != Outer.PALLU) return
        // The loose end hangs down the back, visible beside the arm.
        tmp.reset()
        tmp.moveTo(shoulderW - 0.03f, sy)
        tmp.lineTo(shoulderW + 0.03f, sy + 0.02f)
        tmp.quadTo(shoulderW + 0.06f, 0.4f, shoulderW + 0.05f, 0.62f)
        tmp.lineTo(shoulderW - 0.01f, 0.6f)
        tmp.close()
        c.drawPath(tmp, solid(darken(outfit.outerColor, 0.1f)))
        c.drawLine(shoulderW + 0.05f, 0.62f, shoulderW - 0.01f, 0.6f, line(outfit.accent, 0.01f))
    }

    // ── Accessories on the body ──────────────────────────────────────────

    private fun drawTorsoExtras(c: Canvas, sy: Float, t: Float) {
        val e = outfit.extras
        val gold = Color.parseColor("#E1B84F")
        if (Extra.BELT in e) {
            val y = if (outfit.dress) 0.395f else 0.455f
            rect.set(-hipW - 0.004f, y, hipW + 0.004f, y + 0.016f)
            c.drawRect(rect, solid(Color.parseColor("#3B2A20")))
            rect.set(-0.012f, y - 0.002f, 0.012f, y + 0.018f)
            c.drawRect(rect, line(gold, 0.003f))
        }
        if (Extra.TIE in e) {
            tmp.reset()
            tmp.moveTo(-0.008f, sy + 0.008f)
            tmp.lineTo(0.008f, sy + 0.008f)
            tmp.lineTo(0.006f, sy + 0.02f)
            tmp.lineTo(0.014f, 0.38f)
            tmp.lineTo(0f, 0.395f)
            tmp.lineTo(-0.014f, 0.38f)
            tmp.lineTo(-0.006f, sy + 0.02f)
            tmp.close()
            c.drawPath(tmp, solid(outfit.accent))
            for (k in 0 until 6) c.drawLine(-0.01f, sy + 0.05f + k * 0.03f, 0.01f, sy + 0.04f + k * 0.03f, line(alpha(lighten(outfit.accent, 0.3f), 0.5f), 0.002f))
        }
        if (Extra.BOW_TIE in e) {
            tmp.reset()
            tmp.moveTo(0f, sy + 0.008f)
            tmp.lineTo(-0.024f, sy - 0.004f)
            tmp.lineTo(-0.024f, sy + 0.02f)
            tmp.close()
            tmp.moveTo(0f, sy + 0.008f)
            tmp.lineTo(0.024f, sy - 0.004f)
            tmp.lineTo(0.024f, sy + 0.02f)
            tmp.close()
            c.drawPath(tmp, solid(outfit.accent.takeIf { it != outfit.topColor } ?: Color.BLACK))
            c.drawCircle(0f, sy + 0.008f, 0.005f, solid(darken(outfit.accent, 0.2f)))
        }
        if (Extra.NECKLACE in e) {
            tmp.reset()
            tmp.moveTo(-neckW - 0.006f, sy - 0.004f)
            tmp.quadTo(0f, sy + 0.05f, neckW + 0.006f, sy - 0.004f)
            c.drawPath(tmp, line(gold, 0.003f))
            c.drawCircle(0f, sy + 0.028f, 0.007f, solid(gold))
            c.drawCircle(0f, sy + 0.028f, 0.003f, solid(Color.parseColor("#C0392B")))
        }
        if (Extra.SCARF in e) {
            rect.set(-neckW - 0.012f, sy - 0.03f, neckW + 0.012f, sy + 0.01f)
            c.drawRoundRect(rect, 0.012f, 0.012f, solid(outfit.accent))
            rect.set(0.005f, sy, 0.03f, sy + 0.13f)
            c.drawRoundRect(rect, 0.006f, 0.006f, solid(darken(outfit.accent, 0.08f)))
            for (k in 0 until 3) c.drawLine(0.005f, sy + 0.11f + k * 0.006f, 0.03f, sy + 0.11f + k * 0.006f, line(lighten(outfit.accent, 0.3f), 0.002f))
        }
        if (Extra.STETHOSCOPE in e) {
            tmp.reset()
            tmp.moveTo(-neckW - 0.012f, sy - 0.006f)
            tmp.quadTo(-0.04f, sy + 0.1f, -0.02f, sy + 0.14f)
            tmp.moveTo(neckW + 0.012f, sy - 0.006f)
            tmp.quadTo(0.04f, sy + 0.08f, 0.03f, sy + 0.12f)
            c.drawPath(tmp, line(Color.parseColor("#2B2D42"), 0.004f))
            c.drawCircle(0.03f, sy + 0.125f, 0.009f, solid(Color.parseColor("#B0B7C3")))
        }
        if (Extra.BADGE in e) {
            rect.set(0.045f, 0.27f, 0.075f, 0.29f)
            c.drawRoundRect(rect, 0.003f, 0.003f, solid(Color.WHITE))
            c.drawRect(rect.left, rect.top, rect.right, rect.top + 0.006f, solid(outfit.accent))
        }
        if (Extra.POCKET_SQUARE in e) {
            tmp.reset()
            tmp.moveTo(-0.08f, 0.285f)
            tmp.lineTo(-0.07f, 0.27f)
            tmp.lineTo(-0.062f, 0.282f)
            tmp.lineTo(-0.055f, 0.272f)
            tmp.lineTo(-0.05f, 0.285f)
            tmp.close()
            c.drawPath(tmp, solid(outfit.accent))
        }
    }

    // ── Arms and hands ───────────────────────────────────────────────────

    private fun drawArms(c: Canvas, p: Pose, sy: Float) {
        val outerSleeves = outerHasSleeves(outfit.outer)
        val sleeve = if (outerSleeves) Sleeve.LONG else outfit.sleeve
        val sleeveColor = if (outerSleeves) outfit.outerColor else outfit.topColor
        for (i in 0..1) {
            val s = if (i == 0) -1f else 1f
            val a = Math.toRadians(p.armSwing[i].toDouble())
            val b = Math.toRadians((p.armSwing[i] - p.armBend[i]).toDouble())
            val jx = s * (shoulderW - 0.016f)
            val jy = sy + 0.02f
            val lu = upperLen * p.armUpper[i]
            val lf = foreLen * p.armFore[i]
            val ex = jx + lu * s * sin(a).toFloat()
            val ey = jy + lu * cos(a).toFloat()
            val hx = ex + lf * s * sin(b).toFloat()
            val hy = ey + lf * cos(b).toFloat()
            joints[i * 6] = jx; joints[i * 6 + 1] = jy; joints[i * 6 + 2] = ex
            joints[i * 6 + 3] = ey; joints[i * 6 + 4] = hx; joints[i * 6 + 5] = hy

            // Bare arm, with a soft darker edge so it reads against the body.
            c.drawLine(jx, jy, ex, ey, line(alpha(skinShadow, 0.6f), upperThick + 0.004f))
            c.drawLine(ex, ey, hx, hy, line(alpha(skinShadow, 0.6f), foreThick + 0.004f))
            c.drawLine(jx, jy, ex, ey, line(skin, upperThick))
            c.drawLine(ex, ey, hx, hy, line(skin, foreThick))
            c.drawLine(jx + s * 0.007f, jy, ex + s * 0.006f, ey, line(alpha(skinDark, 0.35f), upperThick * 0.3f))

            // Sleeve.
            val sleeveW = 0.009f
            when (sleeve) {
                Sleeve.NONE -> Unit
                Sleeve.SHORT -> segment(c, jx, jy, ex, ey, 0.5f, upperThick + sleeveW, sleeveColor)
                Sleeve.ELBOW -> segment(c, jx, jy, ex, ey, 1.02f, upperThick + sleeveW, sleeveColor)
                Sleeve.LONG -> {
                    segment(c, jx, jy, ex, ey, 1f, upperThick + sleeveW, sleeveColor)
                    segment(c, ex, ey, hx, hy, 0.9f, foreThick + sleeveW, sleeveColor)
                    c.drawCircle(ex, ey, (upperThick + sleeveW) / 2 * 0.95f, solid(sleeveColor))
                }
            }
            if (sleeve != Sleeve.NONE) {
                // A soft shade along the sleeve for roundness.
                val sh = line(alpha(darken(sleeveColor, 0.4f), 0.18f), (upperThick + sleeveW) * 0.3f)
                c.drawLine(jx + s * 0.006f, jy, ex + s * 0.006f, ey, sh)
            }

            // Wrist accessories.
            val wx = ex + (hx - ex) * 0.86f
            val wy = ey + (hy - ey) * 0.86f
            if (Extra.WATCH in outfit.extras && i == 1) {
                c.drawCircle(wx, wy, foreThick * 0.36f, solid(Color.parseColor("#2B2D42")))
                c.drawCircle(wx, wy, foreThick * 0.24f, solid(Color.parseColor("#C9D1D9")))
            }
            if (Extra.BANGLES in outfit.extras) {
                for (k in 0 until 3) c.drawCircle(wx + (hx - ex) * 0.02f * k, wy + (hy - ey) * 0.02f * k, foreThick * 0.58f, line(Color.parseColor("#E1B84F"), 0.0025f))
            }

            drawHand(c, hx, hy, (hx - ex), (hy - ey), s, p.fist)
        }
        // A phone held in both hands while working on the phone.
        if (p.phone > 0.05f) {
            val mx = (joints[4] + joints[10]) / 2
            val my = (joints[5] + joints[11]) / 2 - 0.01f
            c.save()
            c.translate(mx, my)
            rect.set(-0.018f, -0.03f, 0.018f, 0.03f)
            c.drawRoundRect(rect, 0.005f, 0.005f, solid(alpha(Color.parseColor("#1C1C24"), p.phone)))
            rect.set(-0.015f, -0.026f, 0.015f, 0.026f)
            c.drawRoundRect(rect, 0.003f, 0.003f, solid(alpha(accent, 0.8f * p.phone)))
            c.restore()
        }
    }

    private fun segment(c: Canvas, x0: Float, y0: Float, x1: Float, y1: Float, frac: Float, width: Float, color: Int) {
        val x = x0 + (x1 - x0) * frac
        val y = y0 + (y1 - y0) * frac
        // Outline first so a sleeve stands out from a jacket of the same colour.
        c.drawLine(x0, y0, x, y, line(darken(color, 0.4f), width + 0.005f))
        c.drawLine(x0, y0, x, y, line(color, width))
        // Cuff.
        val dx = x1 - x0
        val dy = y1 - y0
        val len = kotlin.math.hypot(dx, dy).coerceAtLeast(1e-4f)
        val nx = -dy / len * width / 2
        val ny = dx / len * width / 2
        c.drawLine(x - nx, y - ny, x + nx, y + ny, line(alpha(darken(color, 0.3f), 0.5f), 0.002f))
    }

    private fun drawHand(c: Canvas, x: Float, y: Float, dx: Float, dy: Float, side: Float, fist: Float) {
        val ang = Math.toDegrees(kotlin.math.atan2(dy.toDouble(), dx.toDouble())).toFloat() - 90f
        c.save()
        c.translate(x, y)
        c.rotate(ang)
        val r = if (female) 0.017f else 0.02f
        if (fist > 0.5f) {
            oval(c, 0f, r * 0.6f, r * 0.95f, r * 0.9f, solid(skin))
            for (k in 0 until 3) c.drawLine(-r * 0.6f + k * r * 0.6f, r * 1.1f, -r * 0.6f + k * r * 0.6f, r * 1.35f, line(alpha(skinShadow, 0.5f), 0.0015f))
        } else {
            // Palm and fingers, thumb towards the body.
            oval(c, 0f, r * 0.9f, r * 0.85f, r * 1.25f, solid(skin))
            oval(c, -side * r * 0.75f, r * 0.55f, r * 0.32f, r * 0.62f, solid(skin))
            for (k in 0 until 3) c.drawLine(-r * 0.45f + k * r * 0.45f, r * 1.4f, -r * 0.45f + k * r * 0.45f, r * 2f, line(alpha(skinShadow, 0.35f), 0.0012f))
        }
        c.restore()
    }

    // ── Head ─────────────────────────────────────────────────────────────

    private fun headTransform(c: Canvas, p: Pose) {
        c.translate(0f, p.bob * 0.06f)
        c.rotate(p.tilt * 0.8f, 0f, headY + headRy * 1.2f)
        c.translate(0f, headY)
    }

    private fun drawHead(c: Canvas, p: Pose, t: Float, sy: Float) {
        c.save()
        headTransform(c, p)
        val rx = headRx
        val ry = headRy
        // A nod moves the features down (looking down) or up.
        val nod = (p.pitch / 15f).coerceIn(-1f, 1f) * ry * 0.12f

        // Ears.
        for (s in intArrayOf(-1, 1)) {
            oval(c, s * rx * 0.98f, ry * 0.08f + nod * 0.5f, rx * 0.17f, ry * 0.22f, solid(skin))
            oval(c, s * rx * 0.99f, ry * 0.1f + nod * 0.5f, rx * 0.08f, ry * 0.13f, solid(alpha(skinShadow, 0.4f)))
        }
        // Head shape.
        headPath(tmp, rx, ry)
        c.drawPath(tmp, solid(skin))
        shaded.shader = RadialGradient(-rx * 0.25f, -ry * 0.2f, ry * 1.4f,
            intArrayOf(alpha(Color.WHITE, 0.12f), Color.TRANSPARENT, alpha(skinShadow, 0.35f)), floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP)
        c.drawPath(tmp, shaded)
        shaded.shader = null

        if (face != null) {
            face.draw(c, p, eyeY = -ry * 0.02f + nod, eyeDistance = rx * 0.84f)
        } else {
            drawFace(c, p, rx, ry, nod, t)
        }
        drawBeard(c, p, rx, ry, nod)
        drawHairFront(c, p, rx, ry)
        drawHeadExtras(c, rx, ry, nod)
        c.restore()
    }

    private fun headPath(out: Path, rx: Float, ry: Float) {
        out.reset()
        val jaw = if (female) 0.62f else 0.72f
        out.moveTo(0f, -ry)
        out.cubicTo(rx * 0.62f, -ry, rx, -ry * 0.62f, rx, -ry * 0.05f)
        out.cubicTo(rx, ry * 0.42f, rx * jaw, ry * 0.8f, rx * 0.3f, ry * 0.97f)
        out.quadTo(0f, ry * 1.04f, -rx * 0.3f, ry * 0.97f)
        out.cubicTo(-rx * jaw, ry * 0.8f, -rx, ry * 0.42f, -rx, -ry * 0.05f)
        out.cubicTo(-rx, -ry * 0.62f, -rx * 0.62f, -ry, 0f, -ry)
        out.close()
    }

    /** The drawn face: eyes, brows, nose, mouth and cheeks. */
    private fun drawFace(c: Canvas, p: Pose, rx: Float, ry: Float, nod: Float, t: Float) {
        val eyeY = -ry * 0.02f + nod
        val eyeX = rx * 0.42f
        val eyeW = rx * 0.25f
        val eyeH = ry * (if (female) 0.13f else 0.11f)
        val open = p.eyeOpen.coerceIn(0f, 1.25f)
        val squint = p.squint

        for (i in 0..1) {
            val s = if (i == 0) -1f else 1f
            val cx = s * eyeX
            val top = eyeH * open
            val bottom = eyeH * (0.75f - 0.55f * squint).coerceAtLeast(0.1f) * open.coerceAtMost(1f)
            if (open > 0.08f) {
                // White of the eye.
                tmp.reset()
                tmp.moveTo(cx - eyeW, eyeY)
                tmp.quadTo(cx, eyeY - top * 1.25f, cx + eyeW, eyeY)
                tmp.quadTo(cx, eyeY + bottom * 1.2f, cx - eyeW, eyeY)
                tmp.close()
                c.drawPath(tmp, solid(Color.parseColor("#F7F3EE")))
                c.save()
                c.clipPath(tmp)
                val ix = cx + p.lookX * eyeW * 0.4f
                val iy = eyeY + p.lookY * eyeH * 0.3f
                val ir = eyeH * 0.95f
                c.drawCircle(ix, iy, ir, solid(spec.eyeColor))
                c.drawCircle(ix, iy, ir, line(darken(spec.eyeColor, 0.5f), ir * 0.15f))
                c.drawCircle(ix, iy, ir * 0.45f, solid(Color.parseColor("#0B0B0F")))
                c.drawCircle(ix - ir * 0.35f, iy - ir * 0.35f, ir * 0.22f, solid(alpha(Color.WHITE, 0.9f)))
                // Upper lid shadow.
                c.drawRect(cx - eyeW, eyeY - top * 1.3f, cx + eyeW, eyeY - top * 0.85f, solid(alpha(skinShadow, 0.35f)))
                c.restore()
                // Lash line.
                tmp.reset()
                tmp.moveTo(cx - eyeW * 1.05f, eyeY + eyeH * 0.05f)
                tmp.quadTo(cx, eyeY - top * 1.3f, cx + eyeW * 1.05f, eyeY - eyeH * 0.05f)
                c.drawPath(tmp, line(Color.parseColor("#1B1416"), if (female) ry * 0.045f else ry * 0.03f))
                if (female) {
                    // Outer lash flick.
                    val ox = cx + s * eyeW * 1.02f
                    c.drawLine(ox, eyeY - eyeH * 0.1f * s * s, ox + s * eyeW * 0.25f, eyeY - eyeH * 0.55f, line(Color.parseColor("#1B1416"), ry * 0.025f))
                }
                // Lower lid crease when squinting or smiling.
                if (squint > 0.1f) {
                    tmp.reset()
                    tmp.moveTo(cx - eyeW * 0.8f, eyeY + bottom * 1.3f)
                    tmp.quadTo(cx, eyeY + bottom * 0.6f, cx + eyeW * 0.8f, eyeY + bottom * 1.3f)
                    c.drawPath(tmp, line(alpha(skinShadow, 0.5f * squint), ry * 0.018f))
                }
            } else {
                // Closed: a soft curved lash line.
                tmp.reset()
                tmp.moveTo(cx - eyeW, eyeY)
                tmp.quadTo(cx, eyeY + eyeH * 0.6f, cx + eyeW, eyeY)
                c.drawPath(tmp, line(Color.parseColor("#1B1416"), ry * 0.035f))
            }
        }

        // Brows: height, inner-end knit and one-sided raise.
        val browY = eyeY - ry * 0.24f - p.brow * ry * 0.08f
        for (i in 0..1) {
            val s = if (i == 0) -1f else 1f
            val asym = p.browAsym * s * ry * 0.07f
            val inner = p.browKnit * ry * 0.07f
            val y = browY - asym
            tmp.reset()
            tmp.moveTo(s * rx * 0.16f, y + inner)
            tmp.quadTo(s * rx * 0.42f, y - ry * 0.07f - inner * 0.2f, s * rx * 0.7f, y + ry * 0.02f)
            c.drawPath(tmp, line(darken(hairColor, 0.1f), ry * (if (female) 0.045f else 0.065f)))
        }

        // Nose.
        tmp.reset()
        tmp.moveTo(-rx * 0.05f, eyeY + ry * 0.05f)
        tmp.quadTo(-rx * 0.12f, eyeY + ry * 0.32f, -rx * 0.02f, eyeY + ry * 0.36f)
        c.drawPath(tmp, line(alpha(skinShadow, 0.45f), ry * 0.022f))
        oval(c, -rx * 0.08f, eyeY + ry * 0.38f, rx * 0.04f, ry * 0.022f, solid(alpha(skinShadow, 0.45f)))
        oval(c, rx * 0.08f, eyeY + ry * 0.38f, rx * 0.04f, ry * 0.022f, solid(alpha(skinShadow, 0.45f)))

        // Cheeks.
        val blush = (p.blush * 0.6f + if (female) 0.12f else 0f).coerceIn(0f, 0.7f)
        if (blush > 0.02f) for (s in intArrayOf(-1, 1)) {
            shaded.shader = RadialGradient(s * rx * 0.55f, eyeY + ry * 0.33f, rx * 0.26f,
                intArrayOf(Color.argb((150 * blush).toInt(), 235, 110, 120), Color.TRANSPARENT), null, Shader.TileMode.CLAMP)
            c.drawCircle(s * rx * 0.55f, eyeY + ry * 0.33f, rx * 0.26f, shaded)
        }
        shaded.shader = null

        drawMouth(c, p, rx, ry, eyeY + ry * 0.6f)
    }

    /** Lips with visemes: open, wide (ee) and round (oo), plus smile and sneer. */
    private fun drawMouth(c: Canvas, p: Pose, rx: Float, ry: Float, my: Float) {
        val baseW = rx * (if (female) 0.3f else 0.33f)
        val w = baseW * (1f + 0.22f * p.mouthWide - 0.38f * p.mouthRound + 0.18f * p.smile.coerceAtLeast(0f))
        val open = p.mouthOpen * ry * 0.32f * (1f - 0.35f * p.mouthWide + 0.15f * p.mouthRound)
        val corner = -p.smile * ry * 0.09f
        val sneer = p.sneer * ry * 0.06f
        val lip = if (female) Color.parseColor("#C25B67") else darken(skin, 0.28f)
        val upperLipH = ry * (if (female) 0.06f else 0.04f)
        val lowerLipH = ry * (if (female) 0.075f else 0.055f)

        if (open > ry * 0.02f) {
            // Mouth opening.
            tmp.reset()
            tmp.moveTo(-w, my + corner)
            tmp.cubicTo(-w * 0.5f, my - open * 0.35f - sneer, w * 0.5f, my - open * 0.35f, w, my + corner)
            tmp.cubicTo(w * 0.55f, my + open * 1.05f, -w * 0.55f, my + open * 1.05f, -w, my + corner)
            tmp.close()
            c.drawPath(tmp, solid(Color.parseColor("#3A0F14")))
            c.save()
            c.clipPath(tmp)
            // Upper teeth and tongue.
            rect.set(-w * 0.75f, my - open * 0.45f - sneer, w * 0.75f, my - open * 0.05f + ry * 0.02f)
            c.drawRoundRect(rect, ry * 0.02f, ry * 0.02f, solid(Color.parseColor("#F1ECE4")))
            oval(c, 0f, my + open * 0.95f, w * 0.6f, open * 0.45f, solid(Color.parseColor("#B04A57")))
            c.restore()
            // Lips around it.
            tmp.reset()
            tmp.moveTo(-w, my + corner)
            tmp.cubicTo(-w * 0.5f, my - open * 0.35f - sneer, w * 0.5f, my - open * 0.35f, w, my + corner)
            c.drawPath(tmp, line(lip, upperLipH))
            tmp.reset()
            tmp.moveTo(-w, my + corner)
            tmp.cubicTo(-w * 0.55f, my + open * 1.05f, w * 0.55f, my + open * 1.05f, w, my + corner)
            c.drawPath(tmp, line(lip, lowerLipH))
        } else {
            // Closed lips.
            tmp.reset()
            tmp.moveTo(-w, my + corner)
            tmp.cubicTo(-w * 0.4f, my - upperLipH * 1.4f - sneer, -w * 0.1f, my - upperLipH * 1.1f, 0f, my - upperLipH * 0.6f)
            tmp.cubicTo(w * 0.1f, my - upperLipH * 1.1f, w * 0.4f, my - upperLipH * 1.4f, w, my + corner)
            tmp.cubicTo(w * 0.5f, my + lowerLipH * 1.5f, -w * 0.5f, my + lowerLipH * 1.5f, -w, my + corner)
            tmp.close()
            c.drawPath(tmp, solid(lip))
            tmp.reset()
            tmp.moveTo(-w, my + corner)
            tmp.quadTo(0f, my + ry * 0.01f - corner * 0.3f, w, my + corner)
            c.drawPath(tmp, line(darken(lip, 0.45f), ry * 0.016f))
            // Highlight on the lower lip.
            oval(c, 0f, my + lowerLipH * 0.7f, w * 0.35f, lowerLipH * 0.25f, solid(alpha(Color.WHITE, 0.18f)))
        }
        if (p.smile > 0.4f) {
            // Smile lines.
            for (s in intArrayOf(-1, 1)) {
                c.drawLine(s * (w + rx * 0.04f), my + corner - ry * 0.04f, s * (w + rx * 0.07f), my + corner + ry * 0.03f,
                    line(alpha(skinShadow, 0.35f * (p.smile - 0.4f) / 0.6f), ry * 0.015f))
            }
        }
    }

    private fun drawBeard(c: Canvas, p: Pose, rx: Float, ry: Float, nod: Float) {
        if (face != null || female) return
        val my = ry * 0.58f + nod
        val col = hairColor
        when (spec.beard) {
            Beard.NONE -> Unit
            Beard.STUBBLE -> {
                beardPath(tmp, rx, ry, my, 0.12f, top = 0.42f)
                c.drawPath(tmp, solid(alpha(col, 0.14f)))
            }
            Beard.SHORT, Beard.FULL -> {
                beardPath(tmp, rx, ry, my, if (spec.beard == Beard.FULL) 0.42f else 0.24f)
                c.drawPath(tmp, solid(alpha(col, 0.92f)))
                mustache(c, rx, ry, my, col)
                drawMouth(c, p, rx, ry, my + ry * 0.02f)
            }
            Beard.MUSTACHE -> mustache(c, rx, ry, my, col)
        }
    }

    private fun beardPath(out: Path, rx: Float, ry: Float, my: Float, depth: Float, top: Float = 0.05f) {
        out.reset()
        out.moveTo(-rx * 0.97f, ry * top)
        out.cubicTo(-rx * 0.9f, ry * 0.6f, -rx * 0.55f, ry * (1f + depth), 0f, ry * (1.02f + depth))
        out.cubicTo(rx * 0.55f, ry * (1f + depth), rx * 0.9f, ry * 0.6f, rx * 0.97f, ry * top)
        out.lineTo(rx * 0.8f, ry * (top + 0.15f))
        out.quadTo(rx * 0.35f, my - ry * 0.05f, 0f, my + ry * 0.12f)
        out.quadTo(-rx * 0.35f, my - ry * 0.05f, -rx * 0.8f, ry * (top + 0.15f))
        out.close()
    }

    private fun mustache(c: Canvas, rx: Float, ry: Float, my: Float, col: Int) {
        tmp.reset()
        tmp.moveTo(-rx * 0.36f, my + ry * 0.02f)
        tmp.quadTo(-rx * 0.2f, my - ry * 0.12f, 0f, my - ry * 0.08f)
        tmp.quadTo(rx * 0.2f, my - ry * 0.12f, rx * 0.36f, my + ry * 0.02f)
        tmp.quadTo(rx * 0.2f, my - ry * 0.04f, 0f, my - ry * 0.03f)
        tmp.quadTo(-rx * 0.2f, my - ry * 0.04f, -rx * 0.36f, my + ry * 0.02f)
        tmp.close()
        c.drawPath(tmp, solid(alpha(col, 0.95f)))
    }

    // ── Hair ─────────────────────────────────────────────────────────────

    private fun drawHairBack(c: Canvas, p: Pose) {
        c.save()
        headTransform(c, p)
        val rx = headRx
        val ry = headRy
        val paint = solid(hairColor)
        when (spec.hair) {
            HairStyle.LONG_STRAIGHT, HairStyle.LONG_WAVY -> {
                val wavy = spec.hair == HairStyle.LONG_WAVY
                tmp.reset()
                tmp.moveTo(-rx * 1.12f, -ry * 0.2f)
                tmp.cubicTo(-rx * 1.25f, ry * 1.2f, -rx * 1.6f, ry * 2.4f, -rx * 1.5f, ry * 3.3f)
                val n = 6
                for (k in 1..n) {
                    val x0 = -rx * 1.5f + rx * 3f * (k - 0.5f) / n
                    val x1 = -rx * 1.5f + rx * 3f * k / n
                    tmp.quadTo(x0, ry * (if (wavy) 3.55f else 3.42f), x1, ry * 3.3f)
                }
                tmp.cubicTo(rx * 1.6f, ry * 2.4f, rx * 1.25f, ry * 1.2f, rx * 1.12f, -ry * 0.2f)
                tmp.quadTo(0f, -ry * 1.4f, -rx * 1.12f, -ry * 0.2f)
                tmp.close()
                c.drawPath(tmp, paint)
                hairStrands(c, tmp, -rx * 1.5f, rx * 1.5f, -ry, ry * 3.4f, wavy)
            }
            HairStyle.PONYTAIL -> {
                tmp.reset()
                tmp.moveTo(rx * 0.55f, -ry * 0.75f)
                tmp.cubicTo(rx * 1.7f, -ry * 0.6f, rx * 1.6f, ry * 1.6f, rx * 1.1f, ry * 2.6f)
                tmp.cubicTo(rx * 1.0f, ry * 1.6f, rx * 1.0f, ry * 0.2f, rx * 0.4f, -ry * 0.4f)
                tmp.close()
                c.drawPath(tmp, paint)
                hairStrands(c, tmp, rx * 0.4f, rx * 1.7f, -ry, ry * 2.6f, true)
            }
            HairStyle.BRAID -> {
                for (k in 0 until 9) {
                    val y = ry * 0.4f + k * ry * 0.3f
                    oval(c, rx * (0.95f + k * 0.03f), y, rx * 0.22f * (1f - k * 0.04f), ry * 0.2f, solid(if (k % 2 == 0) hairColor else hairDark))
                }
                c.drawCircle(rx * 1.22f, ry * 3.1f, rx * 0.08f, solid(outfit.accent))
            }
            HairStyle.BOB, HairStyle.LONG_MALE -> {
                val bottom = if (spec.hair == HairStyle.BOB) ry * 0.85f else ry * 1.5f
                tmp.reset()
                tmp.moveTo(-rx * 1.18f, -ry * 0.1f)
                tmp.quadTo(-rx * 1.3f, bottom * 0.7f, -rx * 1.15f, bottom)
                tmp.lineTo(rx * 1.15f, bottom)
                tmp.quadTo(rx * 1.3f, bottom * 0.7f, rx * 1.18f, -ry * 0.1f)
                tmp.quadTo(0f, -ry * 1.4f, -rx * 1.18f, -ry * 0.1f)
                tmp.close()
                c.drawPath(tmp, paint)
            }
            HairStyle.CURLY -> {
                val big = if (female) 1.0f else 0.75f
                for (k in 0 until 14) {
                    val a = PI * (0.9f + 1.2f * k / 13f)
                    val r = rx * (1.05f + 0.2f * big)
                    c.drawCircle((r * cos(a)).toFloat(), (-ry * 0.1f + r * 1.15f * sin(a)).toFloat() + ry * 0.2f, rx * 0.32f * big + rx * 0.08f, paint)
                }
                if (female) for (k in 0 until 8) {
                    val s = if (k % 2 == 0) -1f else 1f
                    c.drawCircle(s * rx * 1.15f, ry * (0.3f + 0.25f * (k / 2)), rx * 0.3f, paint)
                }
            }
            else -> Unit
        }
        c.restore()
    }

    private fun drawHairFront(c: Canvas, p: Pose, rx: Float, ry: Float) {
        val paint = solid(hairColor)
        when (spec.hair) {
            HairStyle.BALD -> {
                oval(c, -rx * 0.3f, -ry * 0.7f, rx * 0.25f, ry * 0.12f, solid(alpha(Color.WHITE, 0.12f)))
                return
            }
            HairStyle.BUZZ -> {
                capPath(tmp, rx, ry, -ry * 0.55f, 1.02f)
                c.drawPath(tmp, solid(alpha(hairColor, 0.8f)))
                return
            }
            else -> Unit
        }
        val hairline = when (spec.hair) {
            HairStyle.SHORT, HairStyle.SIDE_PART, HairStyle.QUIFF -> -ry * 0.52f
            HairStyle.PIXIE -> -ry * 0.4f
            else -> -ry * 0.5f
        }
        capPath(tmp, rx, ry, hairline, 1.08f)
        c.drawPath(tmp, paint)
        clipPath.set(tmp)
        hairStrands(c, clipPath, -rx * 1.1f, rx * 1.1f, -ry * 1.2f, 0f, false)

        when (spec.hair) {
            HairStyle.LONG_STRAIGHT, HairStyle.LONG_WAVY -> {
                // Middle parting and side curtains framing the face.
                c.drawLine(0f, -ry * 1.05f, -rx * 0.04f, -ry * 0.55f, line(alpha(skin, 0.6f), ry * 0.025f))
                for (s in intArrayOf(-1, 1)) {
                    tmp.reset()
                    tmp.moveTo(s * rx * 0.1f, -ry * 1.0f)
                    tmp.cubicTo(s * rx * 0.8f, -ry * 0.95f, s * rx * 0.85f, -ry * 0.2f, s * rx * 0.98f, ry * 0.9f)
                    tmp.lineTo(s * rx * 1.22f, ry * 1.1f)
                    tmp.cubicTo(s * rx * 1.25f, ry * 0.2f, s * rx * 1.15f, -ry * 0.9f, s * rx * 0.1f, -ry * 1.08f)
                    tmp.close()
                    c.drawPath(tmp, paint)
                }
            }
            HairStyle.PONYTAIL, HairStyle.BUN, HairStyle.BRAID -> {
                // Hair swept back neatly, with a soft side part.
                c.drawLine(-rx * 0.2f, -ry * 1.04f, -rx * 0.28f, -ry * 0.55f, line(alpha(hairLight, 0.6f), ry * 0.02f))
                if (spec.hair == HairStyle.BUN) {
                    c.drawCircle(0f, -ry * 1.12f, rx * 0.42f, paint)
                    c.drawCircle(-rx * 0.1f, -ry * 1.18f, rx * 0.2f, solid(alpha(hairLight, 0.35f)))
                }
                if (spec.hair == HairStyle.PONYTAIL) c.drawCircle(rx * 0.6f, -ry * 0.7f, rx * 0.12f, solid(outfit.accent))
            }
            HairStyle.BOB -> {
                // Fringe.
                tmp.reset()
                tmp.moveTo(-rx * 0.95f, -ry * 0.45f)
                tmp.quadTo(0f, -ry * 0.25f, rx * 0.95f, -ry * 0.45f)
                tmp.lineTo(rx * 0.9f, -ry * 0.9f)
                tmp.quadTo(0f, -ry * 1.15f, -rx * 0.9f, -ry * 0.9f)
                tmp.close()
                c.drawPath(tmp, paint)
                for (s in intArrayOf(-1, 1)) {
                    rect.set(if (s < 0) -rx * 1.2f else rx * 0.88f, -ry * 0.5f, if (s < 0) -rx * 0.88f else rx * 1.2f, ry * 0.85f)
                    c.drawRect(rect, paint)
                }
            }
            HairStyle.PIXIE -> {
                tmp.reset()
                tmp.moveTo(-rx * 0.95f, -ry * 0.35f)
                tmp.quadTo(-rx * 0.2f, -ry * 0.55f, rx * 0.6f, -ry * 0.25f)
                tmp.lineTo(rx * 0.9f, -ry * 0.8f)
                tmp.quadTo(0f, -ry * 1.15f, -rx * 0.95f, -ry * 0.8f)
                tmp.close()
                c.drawPath(tmp, paint)
            }
            HairStyle.SIDE_PART -> {
                tmp.reset()
                tmp.moveTo(-rx * 0.4f, -ry * 1.02f)
                tmp.cubicTo(rx * 0.3f, -ry * 1.25f, rx * 1.05f, -ry * 0.95f, rx * 1.05f, -ry * 0.4f)
                tmp.lineTo(rx * 0.7f, -ry * 0.62f)
                tmp.quadTo(0f, -ry * 0.7f, -rx * 0.4f, -ry * 0.62f)
                tmp.close()
                c.drawPath(tmp, paint)
                c.drawLine(-rx * 0.4f, -ry * 1.0f, -rx * 0.42f, -ry * 0.62f, line(alpha(skin, 0.5f), ry * 0.018f))
            }
            HairStyle.QUIFF -> {
                tmp.reset()
                tmp.moveTo(-rx * 0.8f, -ry * 0.6f)
                tmp.cubicTo(-rx * 0.6f, -ry * 1.45f, rx * 0.6f, -ry * 1.5f, rx * 0.85f, -ry * 0.75f)
                tmp.quadTo(0f, -ry * 0.75f, -rx * 0.8f, -ry * 0.6f)
                tmp.close()
                c.drawPath(tmp, paint)
                c.drawPath(tmp, line(alpha(hairLight, 0.4f), ry * 0.015f))
            }
            HairStyle.CURLY -> {
                for (k in 0 until 7) {
                    val x = -rx * 0.8f + k * rx * 0.27f
                    c.drawCircle(x, -ry * (0.72f + 0.08f * sin(k * 1.7f)), rx * 0.2f, paint)
                }
            }
            HairStyle.LONG_MALE -> {
                c.drawLine(rx * 0.2f, -ry * 1.04f, rx * 0.25f, -ry * 0.55f, line(alpha(skin, 0.5f), ry * 0.02f))
                for (s in intArrayOf(-1, 1)) {
                    rect.set(if (s < 0) -rx * 1.2f else rx * 0.86f, -ry * 0.5f, if (s < 0) -rx * 0.86f else rx * 1.2f, ry * 1.2f)
                    c.drawRoundRect(rect, rx * 0.15f, rx * 0.15f, paint)
                }
            }
            else -> Unit
        }
    }

    /** The hair covering the top of the head, down to [hairline] at the front. */
    private fun capPath(out: Path, rx: Float, ry: Float, hairline: Float, puff: Float) {
        out.reset()
        out.moveTo(-rx * 1.02f, ry * 0.05f)
        out.cubicTo(-rx * 1.08f * puff, -ry * 0.9f * puff, -rx * 0.5f, -ry * 1.12f * puff, 0f, -ry * 1.1f * puff)
        out.cubicTo(rx * 0.5f, -ry * 1.12f * puff, rx * 1.08f * puff, -ry * 0.9f * puff, rx * 1.02f, ry * 0.05f)
        out.lineTo(rx * 0.92f, ry * 0.05f)
        out.quadTo(rx * 0.85f, hairline + ry * 0.05f, rx * 0.5f, hairline)
        out.quadTo(0f, hairline - ry * 0.08f, -rx * 0.5f, hairline)
        out.quadTo(-rx * 0.85f, hairline + ry * 0.05f, -rx * 0.92f, ry * 0.05f)
        out.close()
    }

    private fun hairStrands(c: Canvas, path: Path, x0: Float, x1: Float, y0: Float, y1: Float, wavy: Boolean) {
        c.save()
        c.clipPath(path)
        shaded.shader = LinearGradient(0f, y0, 0f, y1, alpha(hairLight, 0.35f), alpha(hairDark, 0.4f), Shader.TileMode.CLAMP)
        c.drawRect(x0, y0, x1, y1, shaded)
        shaded.shader = null
        val n = 10
        val p = line(alpha(hairDark, 0.35f), (x1 - x0) * 0.008f)
        for (k in 0..n) {
            val x = x0 + (x1 - x0) * k / n
            tmp.reset()
            tmp.moveTo(x, y0)
            if (wavy) tmp.cubicTo(x + (x1 - x0) * 0.05f, y0 + (y1 - y0) * 0.33f, x - (x1 - x0) * 0.05f, y0 + (y1 - y0) * 0.66f, x, y1)
            else tmp.lineTo(x + (x1 - x0) * 0.02f, y1)
            c.drawPath(tmp, p)
        }
        c.restore()
    }

    // ── Head accessories ─────────────────────────────────────────────────

    private fun drawHeadExtras(c: Canvas, rx: Float, ry: Float, nod: Float) {
        val e = outfit.extras
        val gold = Color.parseColor("#E1B84F")
        val eyeY = -ry * 0.02f + nod
        if (Extra.BINDI in e) c.drawCircle(0f, eyeY - ry * 0.3f, ry * 0.035f, solid(Color.parseColor("#B3122E")))
        if (Extra.MAANG_TIKKA in e) {
            c.drawLine(0f, -ry * 1.02f, 0f, -ry * 0.55f, line(gold, ry * 0.015f))
            c.drawCircle(0f, -ry * 0.5f, ry * 0.05f, solid(gold))
            c.drawCircle(0f, -ry * 0.5f, ry * 0.025f, solid(Color.parseColor("#C2185B")))
        }
        if (Extra.EARRINGS in e) for (s in intArrayOf(-1, 1)) {
            c.drawLine(s * rx * 1.0f, ry * 0.28f, s * rx * 1.0f, ry * 0.45f, line(gold, ry * 0.014f))
            c.drawCircle(s * rx * 1.0f, ry * 0.5f, ry * 0.06f, solid(gold))
        }
        if (Extra.GLASSES in e || Extra.SUNGLASSES in e) {
            val sun = Extra.SUNGLASSES in e
            for (s in intArrayOf(-1, 1)) {
                rect.set(s * rx * 0.42f - rx * 0.27f, eyeY - ry * 0.13f, s * rx * 0.42f + rx * 0.27f, eyeY + ry * 0.15f)
                if (sun) c.drawRoundRect(rect, rx * 0.1f, rx * 0.1f, solid(alpha(Color.parseColor("#111317"), 0.88f)))
                c.drawRoundRect(rect, rx * 0.1f, rx * 0.1f, line(Color.parseColor("#1D1D22"), ry * 0.03f))
            }
            c.drawLine(-rx * 0.15f, eyeY - ry * 0.04f, rx * 0.15f, eyeY - ry * 0.04f, line(Color.parseColor("#1D1D22"), ry * 0.025f))
        }
        if (Extra.HEADPHONES in e) {
            tmp.reset()
            tmp.moveTo(-rx * 1.05f, ry * 0.05f)
            tmp.cubicTo(-rx * 1.2f, -ry * 1.5f, rx * 1.2f, -ry * 1.5f, rx * 1.05f, ry * 0.05f)
            c.drawPath(tmp, line(Color.parseColor("#2B2D42"), ry * 0.07f))
            for (s in intArrayOf(-1, 1)) oval(c, s * rx * 1.02f, ry * 0.1f, rx * 0.2f, ry * 0.25f, solid(accent))
        }
        if (Extra.CAP in e) {
            tmp.reset()
            tmp.moveTo(-rx * 1.05f, -ry * 0.45f)
            tmp.cubicTo(-rx * 1.05f, -ry * 1.35f, rx * 1.05f, -ry * 1.35f, rx * 1.05f, -ry * 0.45f)
            tmp.close()
            c.drawPath(tmp, solid(outfit.accent))
            oval(c, rx * 0.15f, -ry * 0.45f, rx * 0.95f, ry * 0.1f, solid(darken(outfit.accent, 0.2f)))
        }
        if (Extra.BEANIE in e) {
            tmp.reset()
            tmp.moveTo(-rx * 1.08f, -ry * 0.4f)
            tmp.cubicTo(-rx * 1.1f, -ry * 1.55f, rx * 1.1f, -ry * 1.55f, rx * 1.08f, -ry * 0.4f)
            tmp.close()
            c.drawPath(tmp, solid(outfit.accent))
            rect.set(-rx * 1.1f, -ry * 0.58f, rx * 1.1f, -ry * 0.36f)
            c.drawRoundRect(rect, ry * 0.08f, ry * 0.08f, solid(darken(outfit.accent, 0.15f)))
        }
        if (Extra.HAT in e) {
            oval(c, 0f, -ry * 0.62f, rx * 1.75f, ry * 0.2f, solid(outfit.accent))
            rect.set(-rx * 0.9f, -ry * 1.45f, rx * 0.9f, -ry * 0.62f)
            c.drawRoundRect(rect, rx * 0.3f, rx * 0.3f, solid(outfit.accent))
            rect.set(-rx * 0.9f, -ry * 0.82f, rx * 0.9f, -ry * 0.68f)
            c.drawRect(rect, solid(darken(outfit.accent, 0.3f)))
        }
        if (Extra.CHEF_HAT in e) {
            rect.set(-rx * 0.85f, -ry * 1.15f, rx * 0.85f, -ry * 0.6f)
            c.drawRect(rect, solid(Color.WHITE))
            for (k in -1..1) c.drawCircle(k * rx * 0.5f, -ry * 1.4f, rx * 0.5f, solid(Color.WHITE))
            c.drawLine(-rx * 0.85f, -ry * 0.75f, rx * 0.85f, -ry * 0.75f, line(Color.parseColor("#DDDDDD"), ry * 0.02f))
        }
        if (Extra.GRAD_CAP in e) {
            rect.set(-rx * 0.85f, -ry * 1.05f, rx * 0.85f, -ry * 0.62f)
            c.drawRoundRect(rect, rx * 0.2f, rx * 0.2f, solid(Color.parseColor("#111111")))
            tmp.reset()
            tmp.moveTo(-rx * 1.4f, -ry * 1.05f)
            tmp.lineTo(0f, -ry * 1.35f)
            tmp.lineTo(rx * 1.4f, -ry * 1.05f)
            tmp.lineTo(0f, -ry * 0.82f)
            tmp.close()
            c.drawPath(tmp, solid(Color.parseColor("#1A1A1A")))
            c.drawLine(0f, -ry * 1.08f, rx * 1.2f, -ry * 0.95f, line(gold, ry * 0.02f))
            c.drawLine(rx * 1.2f, -ry * 0.95f, rx * 1.2f, -ry * 0.55f, line(gold, ry * 0.03f))
        }
        if (Extra.SAFA in e) {
            // A wedding turban: layered folds and a jewel.
            for (k in 0 until 4) {
                rect.set(-rx * 1.12f, -ry * (1.3f - k * 0.17f), rx * 1.12f, -ry * (0.95f - k * 0.17f))
                c.drawRoundRect(rect, rx * 0.5f, rx * 0.5f, solid(if (k % 2 == 0) Color.parseColor("#D62828") else Color.parseColor("#F77F00")))
            }
            c.drawCircle(0f, -ry * 0.95f, ry * 0.08f, solid(gold))
            c.drawLine(0f, -ry * 1.0f, rx * 0.25f, -ry * 1.5f, line(Color.WHITE, ry * 0.05f))
        }
    }

    // ── Patterns and shading ─────────────────────────────────────────────

    private fun pattern(c: Canvas, path: Path, pattern: Pattern, t: Float, hemY: Float = -1f) {
        if (pattern == Pattern.PLAIN) return
        val acc = outfit.accent
        c.save()
        c.clipPath(path)
        when (pattern) {
            Pattern.STRIPES -> {
                var y = 0.18f
                while (y < 1f) {
                    c.drawLine(-0.3f, y, 0.3f, y, line(alpha(acc, 0.45f), 0.006f))
                    y += 0.028f
                }
            }
            Pattern.CHECKS -> {
                var y = 0.15f
                while (y < 1f) {
                    c.drawLine(-0.3f, y, 0.3f, y, line(alpha(acc, 0.35f), 0.005f))
                    y += 0.03f
                }
                var x = -0.3f
                while (x < 0.3f) {
                    c.drawLine(x, 0f, x, 1f, line(alpha(acc, 0.35f), 0.005f))
                    x += 0.03f
                }
            }
            Pattern.DOTS -> {
                var y = 0.18f
                var row = 0
                while (y < 1f) {
                    var x = -0.3f + if (row % 2 == 0) 0f else 0.012f
                    while (x < 0.3f) {
                        c.drawCircle(x, y, 0.0035f, solid(alpha(acc, 0.7f)))
                        x += 0.024f
                    }
                    y += 0.022f
                    row++
                }
            }
            Pattern.FLORAL -> {
                var y = 0.2f
                var row = 0
                while (y < 1f) {
                    var x = -0.28f + if (row % 2 == 0) 0f else 0.022f
                    while (x < 0.3f) {
                        for (k in 0 until 5) {
                            val a = k * 2 * PI / 5
                            c.drawCircle(x + 0.006f * cos(a).toFloat(), y + 0.006f * sin(a).toFloat(), 0.0042f, solid(alpha(acc, 0.75f)))
                        }
                        c.drawCircle(x, y, 0.003f, solid(alpha(Color.parseColor("#FFE08A"), 0.9f)))
                        x += 0.044f
                    }
                    y += 0.04f
                    row++
                }
            }
            Pattern.SEQUIN -> {
                var i = 0
                var y = 0.18f
                while (y < 1f) {
                    var x = -0.3f + (i % 3) * 0.006f
                    while (x < 0.3f) {
                        val tw = 0.5f + 0.5f * sin(t * 3f + x * 90f + y * 70f)
                        c.drawCircle(x, y, 0.0032f, solid(alpha(lighten(acc, 0.3f), 0.25f + 0.6f * tw)))
                        x += 0.018f
                    }
                    y += 0.016f
                    i++
                }
            }
            Pattern.BORDER, Pattern.EMBROIDERY -> {
                if (hemY > 0f) {
                    rect.set(-0.4f, hemY - 0.03f, 0.4f, hemY + 0.02f)
                    c.drawRect(rect, solid(acc))
                    var x = -0.3f
                    while (x < 0.3f) {
                        c.drawCircle(x, hemY - 0.015f, 0.004f, solid(lighten(acc, 0.4f)))
                        x += 0.016f
                    }
                }
                if (pattern == Pattern.EMBROIDERY) {
                    var y = 0.22f
                    val end = if (hemY > 0f) hemY - 0.04f else 0.6f
                    while (y < end) {
                        for (s in intArrayOf(-1, 1)) c.drawCircle(s * 0.03f, y, 0.004f, solid(alpha(acc, 0.85f)))
                        y += 0.03f
                    }
                }
            }
            else -> Unit
        }
        c.restore()
    }

    /** Darkens the sides of a shape so it reads as round, lit from the front. */
    private fun cylinder(c: Canvas, path: Path, halfWidth: Float, centerX: Float = 0f) {
        c.save()
        c.clipPath(path)
        shaded.shader = LinearGradient(centerX - halfWidth, 0f, centerX + halfWidth, 0f,
            intArrayOf(Color.argb(70, 0, 0, 0), Color.argb(0, 0, 0, 0), Color.argb(18, 255, 255, 255), Color.argb(0, 0, 0, 0), Color.argb(80, 0, 0, 0)),
            floatArrayOf(0f, 0.3f, 0.45f, 0.65f, 1f), Shader.TileMode.CLAMP)
        c.drawRect(centerX - halfWidth - 0.3f, -0.2f, centerX + halfWidth + 0.3f, 1.2f, shaded)
        shaded.shader = null
        c.restore()
    }

    private fun outlineOf(c: Canvas, path: Path, color: Int) {
        c.drawPath(path, line(alpha(darken(color, 0.45f), 0.55f), 0.0022f))
    }

    companion object {
        fun darken(color: Int, k: Float): Int = Color.rgb(
            (Color.red(color) * (1 - k)).toInt(), (Color.green(color) * (1 - k)).toInt(), (Color.blue(color) * (1 - k)).toInt(),
        )

        fun lighten(color: Int, k: Float): Int = Color.rgb(
            (Color.red(color) + (255 - Color.red(color)) * k).toInt(),
            (Color.green(color) + (255 - Color.green(color)) * k).toInt(),
            (Color.blue(color) + (255 - Color.blue(color)) * k).toInt(),
        )

        @Suppress("unused")
        private fun clamp(v: Float) = max(0f, min(1f, abs(v)))
    }
}

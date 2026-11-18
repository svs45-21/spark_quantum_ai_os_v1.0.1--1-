package com.spark.companion.ui.avatar

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.Typeface
import android.os.SystemClock
import android.view.View
import com.spark.companion.brain.Mood
import com.spark.companion.engine.Phase
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * Spark's animated face. Feed it the engine state; it sleeps, wakes up,
 * listens, thinks, lip-syncs and shows moods on its own.
 */
class AvatarView(context: Context) : View(context) {

    /** Which avatar to show: a built-in character or a custom portrait. */
    var profile: AvatarProfile = AvatarProfile.DEFAULT
        set(value) {
            if (field != value) {
                field = value
                painter = painterFor(value)
                invalidate()
            }
        }

    var awake = false
    var phase = Phase.IDLE
    var mood = Mood.CALM
    /** Mic level while listening, voice level while speaking. */
    var level = 0f
    /** Shape of the sound being spoken (visemes), when known. */
    var mouthShape: com.spark.companion.brain.MouthShape? = null

    /**
     * Full-body avatars stand in open space when this is on (the main
     * screen). Off, they show head and shoulders (bubble, lists).
     */
    var fullBody = false

    /** For tests and previews: draw at a fixed time instead of the clock. */
    var clock: () -> Long = { SystemClock.uptimeMillis() }

    private val rig = AvatarRig()
    private var painter: AvatarPainter = painterFor(profile)

    /** Shows an unsaved portrait (the studio's live preview). */
    fun preview(portrait: android.graphics.Bitmap, landmarks: FaceLandmarks, accent: Int) {
        painter = PhotoPainter(portrait, landmarks, accent)
        invalidate()
    }

    /** Shows an unsaved full-body avatar, optionally wearing a photo's face. */
    fun previewBody(body: BodySpec, accent: Int, portrait: android.graphics.Bitmap? = null, landmarks: FaceLandmarks? = null) {
        val face = if (portrait != null && landmarks != null) PhotoFace(portrait, landmarks) else null
        painter = BodyPainter(body, accent, face)
        invalidate()
    }
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val fxPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val zPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        textAlign = Paint.Align.CENTER
    }

    private fun phaseColor(): Int = when {
        !awake -> Color.parseColor("#5C6B8A")
        phase == Phase.LISTENING -> Color.parseColor("#3DDC97")
        phase == Phase.THINKING -> Color.parseColor("#7C5CFF")
        phase == Phase.ACTING -> Color.parseColor("#FFB347")
        else -> profile.accent
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val now = clock()
        val p = rig.update(now, AvatarRig.Input(awake, phase, level, mood, mouthShape))
        val t = now / 1000f
        val s = min(width, height).toFloat()
        if (s <= 0f) return
        val body = painter as? BodyPainter
        if (fullBody && body != null) {
            drawStanding(canvas, body, p, t)
            scheduleNext(p)
            return
        }
        val cx = width / 2f
        val cy = height / 2f + s * 0.04f
        val color = phaseColor()

        // Glow.
        glowPaint.shader = RadialGradient(
            cx, cy, s * 0.5f,
            intArrayOf(withAlpha(color, (110 * p.glow).toInt()), withAlpha(color, (35 * p.glow).toInt()), Color.TRANSPARENT),
            floatArrayOf(0.3f, 0.7f, 1f), Shader.TileMode.CLAMP
        )
        canvas.drawCircle(cx, cy, s * 0.5f, glowPaint)

        // Listening rings ripple out with your voice.
        if (phase == Phase.LISTENING && awake) {
            ringPaint.strokeWidth = s * 0.008f
            for (i in 0 until 3) {
                val k = (t * 0.8f + i / 3f) % 1f
                ringPaint.color = withAlpha(color, ((1f - k) * (90 + 120 * p.listenLevel)).toInt())
                canvas.drawCircle(cx, cy, s * (0.36f + 0.12f * k + 0.05f * p.listenLevel), ringPaint)
            }
        }

        // The character.
        canvas.save()
        if (painter.ownsHeadMotion) {
            canvas.translate(cx, cy)
        } else {
            canvas.translate(cx, cy + p.bob * s)
            canvas.rotate(p.tilt)
        }
        canvas.scale(s * p.scale, s * p.scale)
        painter.drawHead(canvas, p, t)
        canvas.restore()

        drawEffects(canvas, p, t, cx, cy, s, color)
        scheduleNext(p)
    }

    private fun scheduleNext(p: Pose) {
        if (isShown) {
            // Sleeping is slow breathing; a low frame rate saves battery.
            val dozing = !awake && phase == Phase.IDLE && p.eyeOpen < 0.05f && p.zzz > 0.95f
            if (dozing) postInvalidateDelayed(66) else postInvalidateOnAnimation()
        }
    }

    /** A full-body avatar standing in open space: soft light, a floor shadow, no frame. */
    private fun drawStanding(canvas: Canvas, body: BodyPainter, p: Pose, t: Float) {
        val color = phaseColor()
        // Figure height: room above the head for raised arms and effects.
        val h = min(height / 1.12f, width / 0.62f)
        val cx = width / 2f
        val top = (height - h) / 2f + h * 0.08f
        val headX = cx
        val headY = top + body.headCenterY * h

        // Soft spotlight behind the upper body, coloured by what Spark is doing.
        glowPaint.shader = RadialGradient(
            cx, top + h * 0.3f, h * 0.55f,
            intArrayOf(withAlpha(color, (70 * p.glow).toInt()), withAlpha(color, (18 * p.glow).toInt()), Color.TRANSPARENT),
            floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP
        )
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), glowPaint)

        // Listening: ripples spread across the floor around the feet.
        if (phase == Phase.LISTENING && awake) {
            ringPaint.strokeWidth = h * 0.004f
            for (i in 0 until 3) {
                val k = (t * 0.8f + i / 3f) % 1f
                ringPaint.color = withAlpha(color, ((1f - k) * (90 + 120 * p.listenLevel)).toInt())
                val r = h * (0.14f + 0.22f * k + 0.04f * p.listenLevel)
                canvas.drawOval(cx - r, top + h - r * 0.18f, cx + r, top + h + r * 0.18f, ringPaint)
            }
        }

        // The wake-up stretch scales the figure up from the feet.
        canvas.save()
        canvas.translate(cx, top + h)
        canvas.scale(h * p.scale, h * p.scale)
        canvas.translate(0f, -1f)
        body.drawFigure(canvas, p, t)
        canvas.restore()

        drawEffects(canvas, p, t, headX, headY + h * 0.02f, h * 0.36f, color, standing = true)
    }

    private fun drawEffects(canvas: Canvas, p: Pose, t: Float, cx: Float, cy: Float, s: Float, color: Int, standing: Boolean = false) {
        // Zzz while asleep.
        if (p.zzz > 0.02f) {
            for (i in 0 until 3) {
                val k = (t * 0.3f + i / 3f) % 1f
                zPaint.textSize = s * (0.06f + 0.05f * k)
                zPaint.color = withAlpha(Color.parseColor("#C9D6F2"), (255 * p.zzz * sin(PI.toFloat() * k)).toInt())
                canvas.drawText("z", cx + s * (0.25f + 0.13f * k), cy - s * (0.22f + 0.24f * k), zPaint)
            }
        }
        // Sparkle burst when waking up.
        if (p.sparkle > 0f && p.sparkle < 1f) {
            val k = p.sparkle
            fxPaint.color = withAlpha(profile.accent, (255 * (1f - k)).toInt())
            for (i in 0 until 10) {
                val a = i * (2 * PI / 10) + 0.3
                val r = s * (0.28f + 0.26f * k)
                star(canvas, cx + (r * cos(a)).toFloat(), cy + (r * sin(a)).toFloat(), s * 0.028f * (1.2f - k))
            }
        }
        // Thinking dots bob above the head.
        if (p.thinking > 0.02f) {
            for (i in 0 until 3) {
                val bounce = sin(t * 6f - i * 0.8f) * s * 0.015f
                fxPaint.color = withAlpha(Color.WHITE, (230 * p.thinking).toInt())
                canvas.drawCircle(cx + s * (0.2f + i * 0.055f), cy - s * (0.36f + i * 0.035f) + bounce, s * (0.014f + i * 0.006f), fxPaint)
            }
        }
        // Busy orbit while working on the phone (full-body avatars hold a phone instead).
        if (p.acting > 0.02f && !standing) {
            ringPaint.strokeWidth = s * 0.014f
            ringPaint.strokeCap = Paint.Cap.ROUND
            ringPaint.color = withAlpha(color, (230 * p.acting).toInt())
            val r = s * 0.43f
            val angle = (t * 200f) % 360f
            canvas.drawArc(cx - r, cy - r, cx + r, cy + r, angle, 50f, false, ringPaint)
            canvas.drawArc(cx - r, cy - r, cx + r, cy + r, angle + 180f, 50f, false, ringPaint)
            ringPaint.strokeCap = Paint.Cap.BUTT
        }
    }

    private fun star(canvas: Canvas, x: Float, y: Float, r: Float) {
        canvas.save()
        canvas.translate(x, y)
        canvas.rotate(45f)
        canvas.drawOval(-r, -r * 0.28f, r, r * 0.28f, fxPaint)
        canvas.drawOval(-r * 0.28f, -r, r * 0.28f, r, fxPaint)
        canvas.restore()
    }

    companion object {
        private val cache = HashMap<String, AvatarPainter>()

        /** Painters are cached so switching avatars doesn't re-decode portraits. */
        fun painterFor(profile: AvatarProfile): AvatarPainter {
            val key = profile.id + "@" + (profile.portrait?.lastModified() ?: 0) + "@" + profile.landmarks.hashCode() +
                "@" + profile.body.hashCode()
            cache[key]?.let { return it }
            if (profile.builtin != null && profile.body == null) return AvatarPainter.of(profile.builtin)
            val bitmap = profile.portrait?.let { AvatarRepository.loadPortrait(it) }
            val painter = when {
                profile.body != null -> {
                    // Full body; with a portrait, its face is swapped onto the body.
                    val face = if (bitmap != null && profile.landmarks != null) PhotoFace(bitmap, profile.landmarks) else null
                    BodyPainter(profile.body, profile.accent, face)
                }
                bitmap != null && profile.landmarks != null -> PhotoPainter(bitmap, profile.landmarks, profile.accent)
                else -> AvatarPainter.of(AvatarStyle.NOVA)
            }
            if (cache.size > 6) cache.clear()
            cache[key] = painter
            return painter
        }

        /** Forget cached painters (after an avatar was edited or deleted). */
        fun clearCache() = cache.clear()
    }

    private fun withAlpha(color: Int, alpha: Int): Int =
        Color.argb(alpha.coerceIn(0, 255), Color.red(color), Color.green(color), Color.blue(color))
}

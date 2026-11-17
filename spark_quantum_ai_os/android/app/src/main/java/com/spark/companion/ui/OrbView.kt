package com.spark.companion.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.os.SystemClock
import android.view.View
import com.spark.companion.engine.Phase
import kotlin.math.min
import kotlin.math.sin

/**
 * Spark's face: a glowing orb that breathes when idle, swells with your voice
 * while listening, spins while thinking and ripples while speaking.
 */
class OrbView(context: Context) : View(context) {

    var phase: Phase = Phase.IDLE
        set(value) {
            if (field != value) {
                field = value
                invalidate()
            }
        }

    /** Microphone level 0..1 (only matters while listening). */
    var level: Float = 0f

    private var smoothLevel = 0f
    private val core = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glow = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val arcBounds = RectF()
    private var shaderRadius = -1f
    private var shaderColor = 0

    private fun colorFor(phase: Phase): Int = when (phase) {
        Phase.IDLE -> Ui.ACCENT
        Phase.LISTENING -> Ui.OK
        Phase.THINKING -> Ui.VIOLET
        Phase.ACTING -> Color.parseColor("#FFB347")
        Phase.SPEAKING -> Ui.ACCENT
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val t = SystemClock.uptimeMillis() / 1000f
        val cx = width / 2f
        val cy = height / 2f
        val maxR = min(width, height) / 2f
        if (maxR <= 0f) return
        val base = maxR * 0.46f
        val color = colorFor(phase)

        smoothLevel += (level - smoothLevel) * 0.25f
        val pulse = when (phase) {
            Phase.IDLE -> 0.04f * sin(t * 1.6f)
            Phase.LISTENING -> 0.06f + 0.30f * smoothLevel + 0.03f * sin(t * 5f)
            Phase.THINKING -> 0.05f * sin(t * 4f)
            Phase.ACTING -> 0.05f * sin(t * 3f)
            Phase.SPEAKING -> 0.10f * sin(t * 9f) * sin(t * 2.3f) + 0.06f
        }
        val r = base * (1f + pulse)

        // Soft outer glow.
        glow.shader = RadialGradient(
            cx, cy, maxR,
            intArrayOf(withAlpha(color, 90), withAlpha(color, 25), Color.TRANSPARENT),
            floatArrayOf(0.35f, 0.7f, 1f), Shader.TileMode.CLAMP
        )
        canvas.drawCircle(cx, cy, maxR, glow)

        // Expanding rings.
        ring.strokeWidth = maxR * 0.012f
        val speed = if (phase == Phase.IDLE) 0.25f else 0.7f
        for (i in 0 until 3) {
            val p = ((t * speed + i / 3f) % 1f)
            ring.color = withAlpha(color, ((1f - p) * 110).toInt())
            canvas.drawCircle(cx, cy, r + (maxR - r) * p, ring)
        }

        // Core.
        if (shaderRadius != r || shaderColor != color) {
            core.shader = RadialGradient(
                cx - r * 0.25f, cy - r * 0.3f, r * 1.25f,
                intArrayOf(Color.WHITE, color, withAlpha(Ui.VIOLET, 255)),
                floatArrayOf(0f, 0.45f, 1f), Shader.TileMode.CLAMP
            )
            shaderRadius = r
            shaderColor = color
        }
        canvas.drawCircle(cx, cy, r, core)

        // Orbiting arcs while working.
        if (phase == Phase.THINKING || phase == Phase.ACTING) {
            ring.strokeWidth = maxR * 0.035f
            ring.strokeCap = Paint.Cap.ROUND
            ring.color = withAlpha(Color.WHITE, 220)
            val ar = r * 1.22f
            arcBounds.set(cx - ar, cy - ar, cx + ar, cy + ar)
            val angle = (t * 220f) % 360f
            canvas.drawArc(arcBounds, angle, 70f, false, ring)
            canvas.drawArc(arcBounds, angle + 180f, 70f, false, ring)
            ring.strokeCap = Paint.Cap.BUTT
        }

        // Keep animating while visible.
        if (isShown) postInvalidateOnAnimation()
    }

    private fun withAlpha(color: Int, alpha: Int): Int =
        Color.argb(alpha.coerceIn(0, 255), Color.red(color), Color.green(color), Color.blue(color))
}

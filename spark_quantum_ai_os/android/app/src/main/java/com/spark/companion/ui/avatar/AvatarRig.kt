package com.spark.companion.ui.avatar

import com.spark.companion.brain.Mood
import com.spark.companion.engine.Phase
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin
import kotlin.random.Random

/**
 * Face parameters for one frame. Every avatar is drawn from these, so all
 * characters move the same way. Values are unitless and roughly 0..1.
 */
class Pose {
    /** 0 = closed, 1 = open, a little over 1 = wide-eyed. */
    var eyeOpen = 0f
    /** Where the eyes look, -1..1 on each axis (positive = right / down). */
    var lookX = 0f
    var lookY = 0f
    /** 0 = closed mouth, 1 = wide open. */
    var mouthOpen = 0f
    /** -1 = frown, 1 = big smile. */
    var smile = 0f
    /** -1 = worried brows, 1 = raised brows. */
    var brow = 0f
    /** Head tilt in degrees. */
    var tilt = 0f
    /** Vertical bob as a fraction of the avatar's size. */
    var bob = 0f
    var scale = 1f
    /** Strength of the glow behind the avatar. */
    var glow = 0f
    /** Opacity of the floating "Zzz". */
    var zzz = 0f
    /** Progress of the wake-up sparkle burst; 0 when not bursting. */
    var sparkle = 0f
    var thinking = 0f
    var acting = 0f
    /** How upright the ears / antenna are. */
    var perk = 0f
    var blush = 0f
    /** Microphone level while listening, for the rings. */
    var listenLevel = 0f
}

/**
 * Turns Spark's state into a smoothly animated [Pose]: asleep, the wake-up
 * sequence, blinking, eye movement, lip-sync and mood expressions.
 */
class AvatarRig(
    private val random: Random = Random.Default,
    private val blinks: Boolean = true,
) {
    data class Input(
        val awake: Boolean,
        val phase: Phase,
        /** Mic level while listening, voice level while speaking (0..1). */
        val level: Float,
        val mood: Mood,
    )

    val pose = Pose()

    private var lastMs = -1L
    private var awake = false
    private var changedAt = -100_000L
    private var nextBlinkAt = 0L
    private var blinkAt = -100_000L

    // Smoothed values (the pose adds blinks, wake keyframes and motion on top).
    private var eye = 0f
    private var lookX = 0f
    private var lookY = 0.3f
    private var mouth = 0f
    private var smile = 0.15f
    private var brow = 0f
    private var tilt = -7f
    private var glow = 0.25f
    private var zzz = 1f
    private var thinking = 0f
    private var acting = 0f
    private var perk = 0.2f
    private var blush = 0f

    private class Shape(val smile: Float, val brow: Float, val eye: Float, val tilt: Float, val blush: Float, val mouth: Float)

    private fun shapeOf(mood: Mood): Shape = when (mood) {
        Mood.CALM -> Shape(0.3f, 0f, 1f, 0f, 0.1f, 0f)
        Mood.HAPPY -> Shape(0.8f, 0.25f, 1f, 3f, 0.35f, 0f)
        Mood.EXCITED -> Shape(1f, 0.7f, 1.15f, 0f, 0.5f, 0.15f)
        Mood.PLAYFUL -> Shape(0.75f, 0.35f, 0.9f, 8f, 0.35f, 0f)
        Mood.LOVING -> Shape(0.7f, 0.2f, 0.8f, 5f, 0.8f, 0f)
        Mood.CONCERNED -> Shape(-0.25f, -0.7f, 1f, -3f, 0f, 0f)
        Mood.SAD -> Shape(-0.6f, -0.5f, 0.75f, -6f, 0f, 0f)
        Mood.SURPRISED -> Shape(0.1f, 1f, 1.2f, 0f, 0.2f, 0.4f)
        Mood.THOUGHTFUL -> Shape(0.1f, 0.3f, 0.9f, -5f, 0f, 0f)
    }

    fun update(nowMs: Long, input: Input): Pose {
        val dt = if (lastMs < 0) 16f else (nowMs - lastMs).coerceIn(0L, 100L).toFloat()
        lastMs = nowMs
        if (input.awake != awake) {
            awake = input.awake
            changedAt = nowMs
            if (awake) nextBlinkAt = nowMs + WAKE_MS + 1200
        }
        val since = nowMs - changedAt
        val t = nowMs / 1000f
        val level = input.level.coerceIn(0f, 1f)
        val m = shapeOf(input.mood)

        // ── Targets ──────────────────────────────────────────────────────
        var tEye: Float
        var tLookX: Float
        var tLookY: Float
        var tMouth: Float
        var tSmile: Float
        var tBrow: Float
        var tTilt: Float
        var tGlow: Float
        var tZzz = 0f
        var tThinking = 0f
        var tActing = 0f
        var tPerk: Float
        var tBlush = m.blush
        if (!awake) {
            tEye = 0f; tLookX = 0f; tLookY = 0.3f; tMouth = 0f; tSmile = 0.15f; tBrow = 0f
            tTilt = -7f; tGlow = 0.22f; tPerk = 0.15f; tBlush = 0f
            tZzz = if (since > 700) 1f else 0f
        } else {
            tEye = m.eye; tSmile = m.smile; tBrow = m.brow; tTilt = m.tilt; tPerk = 0.8f
            tMouth = m.mouth
            tGlow = 0.55f
            // Idle wandering gaze.
            tLookX = 0.35f * sin(t * 0.37f) + 0.15f * sin(t * 1.3f)
            tLookY = 0.15f * sin(t * 0.53f)
            when (input.phase) {
                Phase.IDLE -> Unit
                Phase.LISTENING -> {
                    tLookX = 0.08f * sin(t * 0.8f); tLookY = 0.05f
                    tEye = maxOf(tEye, 1.08f); tBrow = maxOf(tBrow, 0.45f); tPerk = 1f
                    tTilt += 6f * sin(t * 0.6f) + 4f
                    tMouth = 0.06f; tGlow = 0.65f + 0.35f * level
                }
                Phase.THINKING -> {
                    tLookX = 0.6f; tLookY = -0.75f; tBrow = 0.35f; tTilt = -6f
                    tSmile = minOf(tSmile, 0.15f); tThinking = 1f; tGlow = 0.6f
                }
                Phase.ACTING -> {
                    tLookX = 0.2f * sin(t * 1.7f); tLookY = 0.55f; tEye = 0.75f; tBrow = -0.1f
                    tActing = 1f; tGlow = 0.65f
                }
                Phase.SPEAKING -> {
                    tLookX = 0.1f * sin(t * 0.9f); tLookY = 0.05f * sin(t * 1.4f)
                    tMouth = 0.08f + 0.92f * level
                    tTilt += 3f * sin(t * 1.1f)
                    tGlow = 0.55f + 0.3f * level
                }
            }
        }

        // ── Smoothing ────────────────────────────────────────────────────
        fun ease(current: Float, target: Float, tauMs: Float) =
            current + (target - current) * (1f - exp(-dt / tauMs))
        eye = ease(eye, tEye, if (awake) 120f else 450f)
        lookX = ease(lookX, tLookX, 160f)
        lookY = ease(lookY, tLookY, 160f)
        mouth = ease(mouth, tMouth, if (input.phase == Phase.SPEAKING) 45f else 120f)
        smile = ease(smile, tSmile, 260f)
        brow = ease(brow, tBrow, 200f)
        tilt = ease(tilt, tTilt, 400f)
        glow = ease(glow, tGlow, 250f)
        zzz = ease(zzz, tZzz, 500f)
        thinking = ease(thinking, tThinking, 200f)
        acting = ease(acting, tActing, 200f)
        perk = ease(perk, tPerk, 220f)
        blush = ease(blush, tBlush, 400f)

        // ── Pose ─────────────────────────────────────────────────────────
        val p = pose
        p.eyeOpen = eye
        p.sparkle = 0f
        p.scale = 1f
        if (awake && since < WAKE_MS) {
            // The wake-up: a sleepy half-blink, close, then eyes pop open with a stretch.
            p.eyeOpen = wakeEyes(since)
            val k = since / WAKE_MS.toFloat()
            p.scale = 1f + 0.09f * sin(PI.toFloat() * k)
            p.sparkle = k.coerceAtLeast(0.001f)
            eye = maxOf(eye, p.eyeOpen.coerceAtMost(1f))
        } else if (awake && blinks) {
            if (nowMs >= nextBlinkAt) {
                blinkAt = nowMs
                nextBlinkAt = nowMs + 2200 + random.nextLong(3000)
                // Occasionally a quick double blink.
                if (random.nextInt(5) == 0) nextBlinkAt = nowMs + 320
            }
            val b = nowMs - blinkAt
            if (b in 0 until BLINK_MS) p.eyeOpen = eye * (1f - sin(PI.toFloat() * b / BLINK_MS))
        }
        if (!awake) {
            p.scale = 1f + 0.018f * sin(t * 0.9f)
            p.bob = 0.018f * sin(t * 0.9f)
        } else {
            p.bob = 0.01f * sin(t * 2f) + if (input.phase == Phase.SPEAKING) 0.01f * level else 0f
        }
        p.lookX = lookX
        p.lookY = lookY
        p.mouthOpen = mouth.coerceIn(0f, 1f)
        p.smile = smile.coerceIn(-1f, 1f)
        p.brow = brow.coerceIn(-1f, 1f)
        p.tilt = tilt
        p.glow = glow
        p.zzz = zzz
        p.thinking = thinking
        p.acting = acting
        p.perk = perk
        p.blush = blush
        p.listenLevel = if (input.phase == Phase.LISTENING) level else 0f
        return p
    }

    companion object {
        const val WAKE_MS = 1100L
        const val BLINK_MS = 150L

        /** Eye openness during the wake-up sequence, by milliseconds since waking. */
        fun wakeEyes(ms: Long): Float {
            fun seg(a: Long, b: Long, from: Float, to: Float) = from + (to - from) * ((ms - a).toFloat() / (b - a))
            return when {
                ms < 0 -> 0f
                ms < 260 -> seg(0, 260, 0f, 0.35f)
                ms < 420 -> seg(260, 420, 0.35f, 0f)
                ms < 680 -> seg(420, 680, 0f, 1.12f)
                ms < 850 -> seg(680, 850, 1.12f, 1f)
                else -> 1f
            }
        }

    }
}

package com.spark.companion.ui.avatar

import com.spark.companion.brain.Mood
import com.spark.companion.brain.MouthShape
import com.spark.companion.engine.Phase
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin
import kotlin.random.Random

/**
 * Face and body parameters for one frame. Every avatar is drawn from these, so
 * all characters move the same way. Values are unitless and roughly 0..1.
 */
class Pose {
    /** 0 = closed, 1 = open, a little over 1 = wide-eyed. */
    var eyeOpen = 0f
    /** Where the eyes look, -1..1 on each axis (positive = right / down). */
    var lookX = 0f
    var lookY = 0f
    /** 0 = closed mouth, 1 = wide open. */
    var mouthOpen = 0f
    /** Lips stretched sideways (ee, s), 0..1. */
    var mouthWide = 0f
    /** Lips pursed forward (oo, o), 0..1. */
    var mouthRound = 0f
    /** -1 = frown, 1 = big smile. */
    var smile = 0f
    /** -1 = worried brows, 1 = raised brows. */
    var brow = 0f
    /** Inner ends of the brows: -1 = raised (sad, pleading), 1 = pulled down (angry). */
    var browKnit = 0f
    /** One brow up, the other down (confused, sceptical), -1..1. */
    var browAsym = 0f
    /** Lower lids pushed up (smiling eyes, anger, disgust), 0..1. */
    var squint = 0f
    /** Upper lip curled (disgust), 0..1. */
    var sneer = 0f
    /** Head tilt in degrees. */
    var tilt = 0f
    /** Head nod in degrees, positive = looking down. */
    var pitch = 0f
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

    // ── Body (full-body avatars) ─────────────────────────────────────────
    /** Breathing, -1..1 (chest rises at 1). */
    var breath = 0f
    /** Side-to-side weight shift, -1..1. */
    var sway = 0f
    /** Lean of the upper body in degrees (positive = to the viewer's right). */
    var lean = 0f
    /** Shoulders: -1 slumped, 1 raised (shrug, fear). */
    var shoulders = 0f
    /**
     * Arm angles for the arm on the viewer's left (index 0) and right (1):
     * shoulder swing out from hanging straight down, elbow bend inward (both
     * in degrees), and how much each segment is foreshortened (1 = full length).
     */
    val armSwing = FloatArray(2)
    val armBend = FloatArray(2)
    val armUpper = floatArrayOf(1f, 1f)
    val armFore = floatArrayOf(1f, 1f)
    /** 1 when the hands make fists (anger, determination). */
    var fist = 0f
    /** 1 while holding a phone (working on the phone). */
    var phone = 0f
}

/**
 * Turns Spark's state into a smoothly animated [Pose]: asleep, the wake-up
 * sequence, blinking, eye movement, lip-sync, mood expressions and body
 * language.
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
        /** The mouth shape of the sound being spoken, when known. */
        val mouth: MouthShape? = null,
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
    private var wide = 0f
    private var round = 0f
    private var smile = 0.15f
    private var brow = 0f
    private var knit = 0f
    private var asym = 0f
    private var squint = 0f
    private var sneer = 0f
    private var tilt = -7f
    private var pitch = 6f
    private var glow = 0.25f
    private var zzz = 1f
    private var thinking = 0f
    private var acting = 0f
    private var perk = 0.2f
    private var blush = 0f
    private var shoulders = -0.3f
    private var lean = 0f
    private var fist = 0f
    private var phoneHold = 0f
    private var wave = 0f
    private var talkHands = 0f
    private val swing = floatArrayOf(5f, 5f)
    private val bend = floatArrayOf(5f, 5f)
    private val upper = floatArrayOf(1f, 1f)
    private val fore = floatArrayOf(1f, 1f)

    /** A facial expression plus the body language that goes with it. */
    class Shape(
        val smile: Float, val brow: Float, val eye: Float, val tilt: Float, val blush: Float, val mouth: Float,
        val knit: Float = 0f, val asym: Float = 0f, val squint: Float = 0f, val sneer: Float = 0f,
        val pitch: Float = 0f, val shoulders: Float = 0f, val lean: Float = 0f, val round: Float = 0f,
        val gesture: Gesture = Gesture.RELAXED, val fist: Float = 0f, val breathRate: Float = 1f,
    )

    /** Arm positions. Values per arm: swing, bend, upper and fore length. */
    enum class Gesture(val left: FloatArray, val right: FloatArray) {
        RELAXED(arm(8f, 10f), arm(8f, 10f)),
        DROOP(arm(3f, 4f), arm(3f, 4f)),
        HIPS(arm(46f, 112f, 0.95f, 0.8f), arm(46f, 112f, 0.95f, 0.8f)),
        CROSSED(arm(14f, 122f, 0.8f, 0.85f), arm(14f, 118f, 0.8f, 0.8f)),
        HEART(arm(16f, 132f, 0.8f, 0.82f), arm(16f, 136f, 0.8f, 0.82f)),
        CLASPED(arm(12f, 52f, 1f, 0.9f), arm(12f, 52f, 1f, 0.9f)),
        WAVE(arm(8f, 10f), arm(135f, 40f, 0.95f, 0.95f)),
        CHEER(arm(150f, 22f), arm(150f, 22f)),
        CHIN(arm(14f, 118f, 0.8f, 0.75f), arm(12f, 168f, 0.62f, 0.95f)),
        FACE(arm(18f, 172f, 0.55f, 0.95f), arm(18f, 172f, 0.55f, 0.95f)),
        SHRUG(arm(26f, 82f, 0.95f, 0.85f), arm(26f, 82f, 0.95f, 0.85f)),
        EAR(arm(8f, 10f), arm(62f, 165f, 0.8f, 0.9f)),
        SCRATCH(arm(8f, 10f), arm(118f, 262f, 0.85f, 0.85f)),
        // Forearms come forward towards the listener, hands slightly open to the side.
        EXPLAIN(arm(10f, -18f, 1f, 0.62f), arm(16f, -34f, 1f, 0.55f)),
        PHONE(arm(18f, 98f, 0.9f, 0.8f), arm(18f, 98f, 0.9f, 0.8f)),
        FISTS(arm(18f, 58f, 1f, 0.9f), arm(18f, 58f, 1f, 0.9f)),
        THUMB(arm(8f, 10f), arm(30f, 125f, 0.85f, 0.8f)),
        STRETCH(arm(160f, 30f), arm(160f, 30f)),
    }

    private fun shapeOf(mood: Mood): Shape = when (mood) {
        Mood.CALM -> Shape(0.3f, 0f, 1f, 0f, 0.1f, 0f)
        Mood.HAPPY -> Shape(0.8f, 0.25f, 1f, 3f, 0.35f, 0f, squint = 0.3f, pitch = -2f, gesture = Gesture.WAVE, breathRate = 1.1f)
        Mood.EXCITED -> Shape(1f, 0.7f, 1.15f, 0f, 0.5f, 0.2f, pitch = -4f, shoulders = 0.3f, gesture = Gesture.CHEER, breathRate = 1.6f)
        Mood.PLAYFUL -> Shape(0.75f, 0.35f, 0.9f, 9f, 0.35f, 0f, asym = 0.4f, squint = 0.25f, lean = 4f, gesture = Gesture.HIPS)
        Mood.LOVING -> Shape(0.7f, 0.2f, 0.8f, 6f, 0.8f, 0f, knit = -0.2f, squint = 0.2f, pitch = 2f, gesture = Gesture.HEART)
        Mood.GRATEFUL -> Shape(0.65f, 0.15f, 0.85f, 4f, 0.5f, 0f, knit = -0.3f, pitch = 5f, gesture = Gesture.HEART)
        Mood.PROUD -> Shape(0.55f, 0.15f, 0.95f, 0f, 0.2f, 0f, squint = 0.15f, pitch = -7f, shoulders = 0.15f, gesture = Gesture.HIPS)
        Mood.CONFIDENT -> Shape(0.45f, 0.05f, 0.95f, 2f, 0.1f, 0f, squint = 0.2f, pitch = -4f, gesture = Gesture.THUMB)
        Mood.RELIEVED -> Shape(0.45f, 0.25f, 0.7f, -3f, 0.15f, 0.12f, knit = -0.3f, pitch = 3f, shoulders = -0.5f, gesture = Gesture.HEART, breathRate = 0.7f)
        Mood.CONCERNED -> Shape(-0.25f, -0.7f, 1f, -3f, 0f, 0f, knit = -0.7f, pitch = 2f, gesture = Gesture.CLASPED)
        Mood.SAD -> Shape(-0.6f, -0.5f, 0.72f, -6f, 0f, 0f, knit = -1f, pitch = 10f, shoulders = -0.8f, gesture = Gesture.DROOP, breathRate = 0.7f)
        Mood.ANGRY -> Shape(-0.5f, -0.3f, 0.95f, 0f, 0.25f, 0f, knit = 1f, squint = 0.55f, sneer = 0.2f, pitch = 4f, shoulders = 0.3f, gesture = Gesture.FISTS, fist = 1f, breathRate = 1.5f)
        Mood.SCARED -> Shape(-0.35f, 0.85f, 1.22f, 0f, 0f, 0.3f, knit = -0.6f, pitch = -2f, shoulders = 0.9f, lean = -3f, gesture = Gesture.FACE, breathRate = 2f)
        Mood.DISGUSTED -> Shape(-0.45f, -0.2f, 0.8f, -8f, 0f, 0.05f, knit = 0.6f, squint = 0.6f, sneer = 1f, pitch = -3f, lean = -5f, gesture = Gesture.CROSSED)
        Mood.SURPRISED -> Shape(0.1f, 1f, 1.22f, 0f, 0.2f, 0.45f, pitch = -3f, shoulders = 0.4f, round = 0.7f, gesture = Gesture.CHEER, breathRate = 1.4f)
        Mood.CONFUSED -> Shape(-0.1f, 0.2f, 0.95f, 10f, 0f, 0.05f, asym = 1f, knit = 0.2f, gesture = Gesture.SCRATCH)
        Mood.THOUGHTFUL -> Shape(0.1f, 0.3f, 0.9f, -5f, 0f, 0f, asym = 0.25f, gesture = Gesture.CHIN)
        Mood.SHY -> Shape(0.4f, -0.1f, 0.75f, 9f, 0.75f, 0f, knit = -0.3f, pitch = 11f, shoulders = 0.25f, gesture = Gesture.CLASPED)
        Mood.EMBARRASSED -> Shape(0.25f, -0.2f, 0.75f, -7f, 1f, 0f, knit = -0.4f, pitch = 8f, shoulders = 0.4f, gesture = Gesture.SCRATCH)
        Mood.NERVOUS -> Shape(-0.1f, 0.2f, 1.05f, 0f, 0.3f, 0.05f, knit = -0.6f, shoulders = 0.6f, gesture = Gesture.CLASPED, breathRate = 1.8f)
        Mood.BORED -> Shape(-0.1f, -0.15f, 0.62f, -9f, 0f, 0f, pitch = 4f, shoulders = -0.6f, lean = 5f, gesture = Gesture.CROSSED, breathRate = 0.7f)
        Mood.SLEEPY -> Shape(0.05f, -0.2f, 0.45f, -10f, 0f, 0.05f, pitch = 9f, shoulders = -0.7f, gesture = Gesture.DROOP, breathRate = 0.6f)
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
        var tWide = 0f
        var tRound = 0f
        var tSmile: Float
        var tBrow: Float
        var tKnit = m.knit
        var tAsym = m.asym
        var tSquint = m.squint
        var tSneer = m.sneer
        var tTilt: Float
        var tPitch = m.pitch
        var tGlow: Float
        var tZzz = 0f
        var tThinking = 0f
        var tActing = 0f
        var tPerk: Float
        var tBlush = m.blush
        var tShoulders = m.shoulders
        var tLean = m.lean
        var tFist = m.fist
        var tPhone = 0f
        var tWave = 0f
        var tTalk = 0f
        var gesture = m.gesture
        if (!awake) {
            tEye = 0f; tLookX = 0f; tLookY = 0.3f; tMouth = 0f; tSmile = 0.15f; tBrow = 0f
            tTilt = -7f; tGlow = 0.22f; tPerk = 0.15f; tBlush = 0f
            tKnit = 0f; tAsym = 0f; tSquint = 0f; tSneer = 0f; tPitch = 12f
            tShoulders = -0.4f; tLean = -2f; tFist = 0f
            gesture = Gesture.DROOP
            tZzz = if (since > 700) 1f else 0f
        } else {
            tEye = m.eye; tSmile = m.smile; tBrow = m.brow; tTilt = m.tilt; tPerk = 0.8f
            tMouth = m.mouth
            tRound = m.round
            tWide = (m.smile * 0.6f).coerceAtLeast(0f)
            tGlow = 0.55f
            // Idle wandering gaze.
            tLookX = 0.35f * sin(t * 0.37f) + 0.15f * sin(t * 1.3f)
            tLookY = 0.15f * sin(t * 0.53f)
            if (gesture == Gesture.WAVE) {
                // A wave is a greeting: wave for a couple of seconds, then relax.
                tWave = if (since < 2600 || input.phase == Phase.SPEAKING) 1f else 0f
                if (tWave == 0f) gesture = Gesture.RELAXED
            }
            when (input.phase) {
                Phase.IDLE -> Unit
                Phase.LISTENING -> {
                    tLookX = 0.08f * sin(t * 0.8f); tLookY = 0.05f
                    tEye = maxOf(tEye, 1.08f); tBrow = maxOf(tBrow, 0.45f); tPerk = 1f
                    tTilt += 6f * sin(t * 0.6f) + 4f
                    tMouth = 0.06f; tGlow = 0.65f + 0.35f * level
                    tPitch = minOf(tPitch, 2f)
                    gesture = Gesture.EAR
                    tWave = 0f
                    tLean = 3f
                }
                Phase.THINKING -> {
                    tLookX = 0.6f; tLookY = -0.75f; tBrow = 0.35f; tTilt = -6f
                    tSmile = minOf(tSmile, 0.15f); tThinking = 1f; tGlow = 0.6f
                    tAsym = 0.35f; tPitch = -4f
                    gesture = Gesture.CHIN
                    tWave = 0f
                }
                Phase.ACTING -> {
                    tLookX = 0.2f * sin(t * 1.7f); tLookY = 0.55f; tEye = 0.75f; tBrow = -0.1f
                    tActing = 1f; tGlow = 0.65f; tPitch = 12f
                    gesture = Gesture.PHONE
                    tPhone = 1f
                    tWave = 0f
                }
                Phase.SPEAKING -> {
                    tLookX = 0.1f * sin(t * 0.9f); tLookY = 0.05f * sin(t * 1.4f)
                    val shape = input.mouth
                    if (shape != null) {
                        // Real visemes: the jaw follows loudness and the sound's shape.
                        tMouth = 0.04f + 0.96f * level * (0.35f + 0.65f * shape.open)
                        tWide = shape.wide
                        tRound = shape.round
                    } else {
                        tMouth = 0.08f + 0.92f * level
                        // Without visemes, vary the lips a little so they don't just flap.
                        tWide = 0.35f + 0.3f * sin(t * 7.3f)
                        tRound = (0.3f * sin(t * 4.1f + 1f)).coerceAtLeast(0f)
                    }
                    tTilt += 3f * sin(t * 1.1f)
                    tPitch += 2.5f * sin(t * 1.7f) * level
                    tGlow = 0.55f + 0.3f * level
                    // Talk with the hands unless the mood already has a strong pose.
                    if (gesture == Gesture.RELAXED || gesture == Gesture.WAVE || gesture == Gesture.THUMB) {
                        gesture = Gesture.EXPLAIN
                        tWave = 0f
                    }
                    tTalk = 1f
                }
            }
        }

        // ── Smoothing ────────────────────────────────────────────────────
        fun ease(current: Float, target: Float, tauMs: Float) =
            current + (target - current) * (1f - exp(-dt / tauMs))
        val speaking = input.phase == Phase.SPEAKING
        eye = ease(eye, tEye, if (awake) 120f else 450f)
        lookX = ease(lookX, tLookX, 160f)
        lookY = ease(lookY, tLookY, 160f)
        mouth = ease(mouth, tMouth, if (speaking) 40f else 120f)
        wide = ease(wide, tWide, if (speaking) 45f else 200f)
        round = ease(round, tRound, if (speaking) 45f else 200f)
        smile = ease(smile, tSmile, 260f)
        brow = ease(brow, tBrow, 200f)
        knit = ease(knit, tKnit, 220f)
        asym = ease(asym, tAsym, 260f)
        squint = ease(squint, tSquint, 220f)
        sneer = ease(sneer, tSneer, 220f)
        tilt = ease(tilt, tTilt, 400f)
        pitch = ease(pitch, tPitch, 450f)
        glow = ease(glow, tGlow, 250f)
        zzz = ease(zzz, tZzz, 500f)
        thinking = ease(thinking, tThinking, 200f)
        acting = ease(acting, tActing, 200f)
        perk = ease(perk, tPerk, 220f)
        blush = ease(blush, tBlush, 400f)
        shoulders = ease(shoulders, tShoulders, 350f)
        lean = ease(lean, tLean, 600f)
        fist = ease(fist, tFist, 250f)
        phoneHold = ease(phoneHold, tPhone, 250f)
        wave = ease(wave, tWave, 250f)
        talkHands = ease(talkHands, tTalk, 300f)
        for (i in 0..1) {
            val g = if (i == 0) gesture.left else gesture.right
            swing[i] = ease(swing[i], g[0], 320f)
            bend[i] = ease(bend[i], g[1], 320f)
            upper[i] = ease(upper[i], g[2], 320f)
            fore[i] = ease(fore[i], g[3], 320f)
        }

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
            p.bob = 0.01f * sin(t * 2f) + if (speaking) 0.01f * level else 0f
        }
        p.lookX = lookX
        p.lookY = lookY
        p.mouthOpen = mouth.coerceIn(0f, 1f)
        p.mouthWide = wide.coerceIn(0f, 1f)
        p.mouthRound = round.coerceIn(0f, 1f)
        p.smile = smile.coerceIn(-1f, 1f)
        p.brow = brow.coerceIn(-1f, 1f)
        p.browKnit = knit.coerceIn(-1f, 1f)
        p.browAsym = asym.coerceIn(-1f, 1f)
        p.squint = squint.coerceIn(0f, 1f)
        p.sneer = sneer.coerceIn(0f, 1f)
        p.tilt = tilt
        p.pitch = pitch
        p.glow = glow
        p.zzz = zzz
        p.thinking = thinking
        p.acting = acting
        p.perk = perk
        p.blush = blush
        p.listenLevel = if (input.phase == Phase.LISTENING) level else 0f

        // Body: breathing, weight shift, posture and arms.
        val rate = if (awake) m.breathRate else 0.6f
        p.breath = sin(t * 2f * PI.toFloat() * 0.25f * rate)
        p.sway = 0.6f * sin(t * 0.45f) + 0.25f * sin(t * 0.17f + 1f)
        p.lean = lean + 1.2f * sin(t * 0.45f)
        p.shoulders = (shoulders + 0.08f * p.breath).coerceIn(-1f, 1f)
        p.fist = fist
        p.phone = phoneHold
        for (i in 0..1) {
            p.armSwing[i] = swing[i]
            p.armBend[i] = bend[i]
            p.armUpper[i] = upper[i]
            p.armFore[i] = fore[i]
        }
        // Waving hand and talking hands move on top of the held pose.
        p.armBend[1] += wave * 28f * sin(t * 2f * PI.toFloat() * 1.6f)
        if (talkHands > 0.01f) {
            val beat = talkHands * (0.4f + 0.6f * level)
            p.armBend[1] += beat * 18f * sin(t * 3.1f)
            p.armSwing[1] += beat * 6f * sin(t * 2.3f + 0.5f)
            p.armBend[0] += beat * 10f * sin(t * 2.6f + 2f)
        }
        // Scared avatars tremble a little.
        if (input.mood == Mood.SCARED && awake) {
            p.armBend[0] += 3f * sin(t * 31f)
            p.armBend[1] += 3f * sin(t * 29f)
        }
        return p
    }

    companion object {
        const val WAKE_MS = 1100L
        const val BLINK_MS = 150L

        private fun arm(swing: Float, bend: Float, upper: Float = 1f, fore: Float = 1f) = floatArrayOf(swing, bend, upper, fore)

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

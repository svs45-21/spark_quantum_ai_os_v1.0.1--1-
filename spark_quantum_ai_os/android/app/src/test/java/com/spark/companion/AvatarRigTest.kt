package com.spark.companion

import com.spark.companion.brain.Mood
import com.spark.companion.engine.Phase
import com.spark.companion.ui.avatar.AvatarRig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AvatarRigTest {
    private fun run(rig: AvatarRig, from: Long, to: Long, input: AvatarRig.Input) {
        var t = from
        while (t <= to) {
            rig.update(t, input)
            t += 16
        }
    }

    private val asleep = AvatarRig.Input(false, Phase.IDLE, 0f, Mood.CALM)
    private fun awake(phase: Phase = Phase.IDLE, level: Float = 0f, mood: Mood = Mood.CALM) =
        AvatarRig.Input(true, phase, level, mood)

    @Test fun sleepsWithEyesClosedAndZzz() {
        val rig = AvatarRig(blinks = false)
        run(rig, 0, 5_000, asleep)
        assertTrue(rig.pose.eyeOpen < 0.02f)
        assertTrue(rig.pose.zzz > 0.9f)
        assertEquals(0f, rig.pose.sparkle, 0f)
    }

    @Test fun wakeSequenceThenEyesOpen() {
        val rig = AvatarRig(blinks = false)
        run(rig, 0, 3_000, asleep)
        rig.update(3_016, awake())
        assertTrue("sparkles start on waking", rig.pose.sparkle > 0f)
        // Mid-sequence: the sleepy close between the two blinks.
        rig.update(3_016 + 400, awake())
        assertTrue(rig.pose.eyeOpen < 0.1f)
        run(rig, 3_016 + 432, 6_000, awake())
        assertTrue(rig.pose.eyeOpen > 0.95f)
        assertTrue(rig.pose.zzz < 0.05f)
        assertEquals(0f, rig.pose.sparkle, 0f)
    }

    @Test fun wakeKeyframes() {
        assertEquals(0f, AvatarRig.wakeEyes(0), 1e-6f)
        assertEquals(0.35f, AvatarRig.wakeEyes(260), 1e-6f)
        assertEquals(1.12f, AvatarRig.wakeEyes(680), 1e-6f)
        assertEquals(1f, AvatarRig.wakeEyes(5_000), 1e-6f)
    }

    @Test fun mouthFollowsVoiceWhenSpeaking() {
        val rig = AvatarRig(blinks = false)
        run(rig, 0, 2_000, awake(Phase.SPEAKING, 0f))
        val quiet = rig.pose.mouthOpen
        run(rig, 2_016, 2_400, awake(Phase.SPEAKING, 1f))
        assertTrue(quiet < 0.15f)
        assertTrue(rig.pose.mouthOpen > 0.9f)
    }

    @Test fun moodsChangeTheFace() {
        val happy = AvatarRig(blinks = false).also { run(it, 0, 3_000, awake(mood = Mood.HAPPY)) }.pose
        val sad = AvatarRig(blinks = false).also { run(it, 0, 3_000, awake(mood = Mood.SAD)) }.pose
        assertTrue(happy.smile > 0.7f)
        assertTrue(sad.smile < -0.5f)
        assertTrue(sad.brow < 0f)
    }

    @Test fun thinkingLooksUp() {
        val rig = AvatarRig(blinks = false)
        run(rig, 0, 3_000, awake(Phase.THINKING))
        assertTrue(rig.pose.lookY < -0.6f)
        assertTrue(rig.pose.thinking > 0.9f)
    }

    @Test fun fallsBackAsleep() {
        val rig = AvatarRig(blinks = false)
        run(rig, 0, 3_000, awake())
        run(rig, 3_016, 8_000, asleep)
        assertTrue(rig.pose.eyeOpen < 0.05f)
    }

    @Test fun bodyLanguageFollowsTheMood() {
        val angry = AvatarRig(blinks = false).also { run(it, 0, 3_000, awake(mood = Mood.ANGRY)) }.pose
        val sad = AvatarRig(blinks = false).also { run(it, 0, 3_000, awake(mood = Mood.SAD)) }.pose
        val excited = AvatarRig(blinks = false).also { run(it, 0, 3_000, awake(mood = Mood.EXCITED)) }.pose
        assertTrue("angry brows pull down", angry.browKnit > 0.8f)
        assertTrue("angry makes fists", angry.fist > 0.8f)
        assertTrue("sad brows lift inside", sad.browKnit < -0.8f)
        assertTrue("sad looks down", sad.pitch > 6f)
        assertTrue("sad shoulders slump", sad.shoulders < -0.4f)
        assertTrue("excited raises the arms", excited.armSwing[0] > 120f && excited.armSwing[1] > 120f)
    }

    @Test fun visemesShapeTheLips() {
        val rig = AvatarRig(blinks = false)
        run(rig, 0, 2_000, awake())
        run(rig, 2_000, 2_600, AvatarRig.Input(true, Phase.SPEAKING, 0.8f, Mood.CALM, com.spark.companion.brain.MouthShape(0.4f, 0f, 1f)))
        val round = rig.pose.mouthRound
        run(rig, 2_600, 3_200, AvatarRig.Input(true, Phase.SPEAKING, 0.8f, Mood.CALM, com.spark.companion.brain.MouthShape(0.3f, 1f, 0f)))
        assertTrue("oo rounds the lips", round > 0.8f)
        assertTrue("ee spreads the lips", rig.pose.mouthWide > 0.8f && rig.pose.mouthRound < 0.2f)
    }
}

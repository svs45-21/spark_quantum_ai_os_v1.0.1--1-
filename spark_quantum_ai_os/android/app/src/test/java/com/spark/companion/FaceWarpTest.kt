package com.spark.companion

import com.spark.companion.ui.avatar.FaceLandmarks
import com.spark.companion.ui.avatar.FaceWarp
import com.spark.companion.ui.avatar.Pose
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class FaceWarpTest {
    private val lm = FaceLandmarks(0.375f, 0.416f, 0.625f, 0.426f, 0.383f, 0.664f, 0.59f, 0.664f)
    private fun pose(block: Pose.() -> Unit = {}) = Pose().apply { eyeOpen = 1f; block() }

    @Test fun neutralPoseIsIdentity() {
        val w = FaceWarp(lm)
        w.update(pose())
        val out = FloatArray(2)
        for ((u, v) in listOf(0.1f to 0.1f, 0.5f to 0.4f, 0.48f to 0.7f, 0.9f to 0.95f)) {
            w.map(u, v, v > w.split, out)
            assertEquals((u - 0.5f) * w.frame, out[0], 1e-5f)
            assertEquals((v - 0.5f) * w.frame, out[1], 1e-5f)
        }
    }

    @Test fun openingTheMouthMakesAGapOnlyBetweenTheCorners() {
        val w = FaceWarp(lm)
        w.update(pose { mouthOpen = 1f })
        val up = FloatArray(2)
        val down = FloatArray(2)
        // Centre of the mouth: a clear gap between the halves.
        w.map(lm.mouthX, w.split, false, up)
        w.map(lm.mouthX, w.split, true, down)
        val gap = down[1] - up[1]
        assertTrue("gap $gap", gap > 0.12f * lm.eyeDistance * w.frame)
        // At and beyond the corners the halves still meet: no tear in the cheeks.
        for (u in listOf(lm.mouthLeftX, lm.mouthRightX, 0.2f, 0.8f)) {
            w.map(u, w.split, false, up)
            w.map(u, w.split, true, down)
            assertTrue("seam at $u", abs(down[1] - up[1]) < 1e-3f)
        }
    }

    @Test fun meshEdgesMatchAcrossTheSplitWhenClosed() {
        val w = FaceWarp(lm)
        w.update(pose { smile = 0.8f; brow = 0.5f; tilt = 6f })
        val lastTopRow = w.topRows * (w.cols + 1) * 2
        for (c in 0..w.cols) {
            assertEquals(w.top[lastTopRow + 2 * c], w.bottom[2 * c], 1e-5f)
            assertEquals(w.top[lastTopRow + 2 * c + 1], w.bottom[2 * c + 1], 1e-5f)
        }
    }

    @Test fun closedEyesPullTheLidDown() {
        val w = FaceWarp(lm)
        w.update(pose { eyeOpen = 0f })
        val out = FloatArray(2)
        // A point on the upper lid lands near the lash line.
        val d = lm.eyeDistance
        w.map(lm.leftEyeX, lm.leftEyeY - 0.08f * d, false, out)
        val lashY = (lm.leftEyeY + 0.085f * d - 0.5f) * w.frame
        assertEquals(lashY, out[1], 0.01f * d)
    }

    @Test fun smileLiftsTheCorners() {
        val w = FaceWarp(lm)
        w.update(pose { smile = 1f })
        val out = FloatArray(2)
        w.map(lm.mouthLeftX, lm.mouthLeftY, false, out)
        assertTrue(out[1] < (lm.mouthLeftY - 0.5f) * w.frame)
    }

    @Test fun landmarksRoundTripAsJson() {
        assertEquals(lm, FaceLandmarks.fromJson(lm.toJson()))
    }
}

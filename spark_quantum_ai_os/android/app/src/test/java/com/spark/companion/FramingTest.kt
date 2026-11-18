package com.spark.companion

import com.spark.companion.ui.avatar.Framing
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

class FramingTest {
    private val out = 640

    @Test fun uprightsTheEyeLine() {
        // Eyes tilted 20 degrees in the source photo.
        val cx = 300f
        val cy = 250f
        val half = 60f
        val ang = Math.toRadians(20.0)
        val lx = cx - half * cos(ang).toFloat()
        val ly = cy - half * sin(ang).toFloat()
        val rx = cx + half * cos(ang).toFloat()
        val ry = cy + half * sin(ang).toFloat()
        val f = Framing.forEyes(lx, ly, rx, ry, out)
        val le = f.mapFrac(lx, ly)
        val re = f.mapFrac(rx, ry)
        // After framing the eyes are level and centred horizontally.
        assertEquals(le[1], re[1], 1e-4f)
        assertEquals(0.5f, (le[0] + re[0]) / 2f, 1e-4f)
        assertEquals(0.40f, (le[1] + re[1]) / 2f, 1e-4f)
        // Left eye stays on the left.
        assertTrue(le[0] < re[0])
        // Eye distance is scaled to the target fraction.
        assertEquals(0.26f, hypot(re[0] - le[0], re[1] - le[1]), 1e-4f)
    }

    @Test fun centresAndScalesAnUprightFace() {
        val f = Framing.forEyes(200f, 400f, 360f, 400f, out, targetEyeFracY = 0.42f, targetEyeDistFrac = 0.25f)
        val le = f.mapFrac(200f, 400f)
        val re = f.mapFrac(360f, 400f)
        assertEquals(0.42f, le[1], 1e-4f)
        assertEquals(0.25f, re[0] - le[0], 1e-4f)
        // A point on the eye-centre maps to the horizontal middle.
        val mid = f.mapFrac(280f, 400f)
        assertEquals(0.5f, mid[0], 1e-4f)
    }

    @Test fun matrixValuesMatchMapping() {
        val f = Framing.forEyes(120f, 150f, 260f, 190f, out)
        val v = f.matrixValues()
        val x = 175f
        val y = 205f
        val expected = f.mapPx(x, y)
        assertEquals(v[0] * x + v[1] * y + v[2], expected[0], 1e-3f)
        assertEquals(v[3] * x + v[4] * y + v[5], expected[1], 1e-3f)
        assertEquals(9, v.size)
    }
}

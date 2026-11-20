package com.spark.companion

import com.spark.companion.ui.avatar.Cutout
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CutoutTest {
    private val grey = 0xFF9EA3A8.toInt()
    private val white = 0xFFFFFFFF.toInt()
    private val skin = 0xFFC68B62.toInt()

    /** A 40x60 grey backdrop with a "person": white shirt block and skin head. */
    private fun scene(): IntArray {
        val w = 40; val h = 60
        val px = IntArray(w * h) { grey }
        for (y in 10..18) for (x in 16..23) px[y * w + x] = skin
        for (y in 19..50) for (x in 12..27) px[y * w + x] = white
        // A grey patch inside the shirt (not connected to the backdrop) must stay.
        for (y in 30..32) for (x in 18..20) px[y * w + x] = grey
        return px
    }

    @Test fun removesOnlyTheConnectedBackdrop() {
        val out = Cutout.remove(scene(), 40, 60)
        assertEquals(0, out[0] ushr 24)
        assertEquals(0, out[59 * 40 + 39] ushr 24)
        assertEquals(255, out[14 * 40 + 20] ushr 24) // head
        assertEquals(255, out[35 * 40 + 20] ushr 24) // shirt
        assertEquals(255, out[31 * 40 + 19] ushr 24) // grey inside the shirt
        // Edges are softened, not cut hard.
        val edge = out[35 * 40 + 12] ushr 24
        assertTrue("edge alpha $edge", edge in 40..254)
    }

    @Test fun findsThePersonsBounds() {
        val out = Cutout.remove(scene(), 40, 60)
        assertArrayEquals(intArrayOf(12, 10, 28, 51), Cutout.bounds(out, 40, 60, minAlpha = 1))
    }

    @Test fun clearsEnclosedGapsBetweenTheLegs() {
        val w = 40; val h = 60
        val px = IntArray(w * h) { grey }
        // Two legs joined at the top: the grey gap between them touches no border.
        for (y in 20..58) for (x in 10..29) px[y * w + x] = white
        for (y in 30..50) for (x in 16..23) px[y * w + x] = grey
        val out = Cutout.remove(px, w, h)
        assertEquals("gap between the legs is cleared", 0, out[40 * w + 20] ushr 24)
        assertEquals(255, out[40 * w + 12] ushr 24)
    }
}

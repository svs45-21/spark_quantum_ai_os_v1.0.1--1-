package com.spark.companion.ui.avatar

import kotlin.math.abs

/**
 * Lifts a person out of a plain studio backdrop so they can stand in open
 * space. The backdrop colour is read from the image border, then everything
 * connected to the border that is close to that colour becomes transparent,
 * with a soft edge. Pure pixel maths (ARGB ints), so it is unit-tested.
 */
object Cutout {

    /** Average colour of the image border. */
    fun backdrop(px: IntArray, w: Int, h: Int): Int {
        var r = 0L; var g = 0L; var b = 0L; var n = 0L
        fun add(c: Int) { r += (c shr 16) and 0xFF; g += (c shr 8) and 0xFF; b += c and 0xFF; n++ }
        for (x in 0 until w) { add(px[x]); add(px[(h - 1) * w + x]) }
        for (y in 0 until h) { add(px[y * w]); add(px[y * w + w - 1]) }
        return (0xFF shl 24) or ((r / n).toInt() shl 16) or ((g / n).toInt() shl 8) or (b / n).toInt()
    }

    private fun distance(a: Int, b: Int): Int =
        abs(((a shr 16) and 0xFF) - ((b shr 16) and 0xFF)) +
            abs(((a shr 8) and 0xFF) - ((b shr 8) and 0xFF)) +
            abs((a and 0xFF) - (b and 0xFF))

    /**
     * Returns a copy of [px] with the backdrop removed. [tolerance] is the
     * summed RGB difference still counted as backdrop.
     */
    fun remove(px: IntArray, w: Int, h: Int, tolerance: Int = 60): IntArray {
        val bg = backdrop(px, w, h)
        val removed = BooleanArray(w * h)
        val queue = IntArray(w * h)
        var head = 0
        var tail = 0
        fun seed(i: Int) {
            if (!removed[i] && distance(px[i], bg) <= tolerance) {
                removed[i] = true
                queue[tail++] = i
            }
        }
        for (x in 0 until w) { seed(x); seed((h - 1) * w + x) }
        for (y in 0 until h) { seed(y * w); seed(y * w + w - 1) }
        // Flood fill: the backdrop is what touches the border and looks like it.
        while (head < tail) {
            val i = queue[head++]
            val x = i % w
            val y = i / w
            if (x > 0) seed(i - 1)
            if (x < w - 1) seed(i + 1)
            if (y > 0) seed(i - w)
            if (y < h - 1) seed(i + w)
        }
        // Enclosed gaps (between the legs, under an arm) don't touch the border:
        // clear sizeable patches that match the backdrop closely.
        val strict = tolerance / 2
        val minHole = (w * h * 0.0012f).toInt().coerceAtLeast(12)
        val visited = BooleanArray(w * h)
        val comp = IntArray(w * h)
        for (start in 0 until w * h) {
            if (removed[start] || visited[start] || distance(px[start], bg) > strict) continue
            var n = 0
            head = 0
            tail = 0
            queue[tail++] = start
            visited[start] = true
            while (head < tail) {
                val i = queue[head++]
                comp[n++] = i
                val x = i % w
                val y = i / w
                fun visit(j: Int) {
                    if (!visited[j] && !removed[j] && distance(px[j], bg) <= strict) {
                        visited[j] = true
                        queue[tail++] = j
                    }
                }
                if (x > 0) visit(i - 1)
                if (x < w - 1) visit(i + 1)
                if (y > 0) visit(i - w)
                if (y < h - 1) visit(i + w)
            }
            if (n >= minHole) for (k in 0 until n) removed[comp[k]] = true
        }

        val out = px.copyOf()
        for (i in out.indices) if (removed[i]) out[i] = 0
        // Soft edge: pixels next to the backdrop get partly transparent.
        for (i in out.indices) {
            if (removed[i]) continue
            val x = i % w
            val y = i / w
            var near = 0
            for (dy in -2..2) for (dx in -2..2) {
                val nx = x + dx
                val ny = y + dy
                if (nx in 0 until w && ny in 0 until h && removed[ny * w + nx]) near++
            }
            if (near > 0) {
                val alpha = (255 * (1f - near / 25f * 1.3f)).toInt().coerceIn(40, 255)
                out[i] = (alpha shl 24) or (out[i] and 0xFFFFFF)
            }
        }
        return out
    }

    /** Bounding box (left, top, right, bottom; exclusive) of visible pixels, or null if none. */
    fun bounds(px: IntArray, w: Int, h: Int, minAlpha: Int = 128): IntArray? {
        var l = w; var t = h; var r = -1; var b = -1
        for (y in 0 until h) for (x in 0 until w) {
            if ((px[y * w + x] ushr 24) >= minAlpha) {
                if (x < l) l = x
                if (x > r) r = x
                if (y < t) t = y
                if (y > b) b = y
            }
        }
        return if (r < 0) null else intArrayOf(l, t, r + 1, b + 1)
    }
}

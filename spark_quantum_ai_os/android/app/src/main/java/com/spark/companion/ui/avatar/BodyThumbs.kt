package com.spark.companion.ui.avatar

import android.graphics.Bitmap
import android.graphics.Canvas
import com.spark.companion.brain.Mood
import com.spark.companion.engine.Phase

/** Still pictures of a full-body avatar, for the outfit picker. */
object BodyThumbs {

    /** A relaxed, awake standing pose. */
    private val standing: Pose by lazy {
        val rig = AvatarRig(blinks = false)
        var t = 0L
        while (t <= 4000) {
            rig.update(t, AvatarRig.Input(t > 200, Phase.IDLE, 0f, Mood.CALM))
            t += 50
        }
        rig.pose
    }

    /** Draws [spec] standing, filling a [width] x [height] bitmap. Safe off the main thread. */
    fun render(spec: BodySpec, accent: Int, width: Int, height: Int, face: PhotoFace? = null): Bitmap {
        val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val h = minOf(height * 0.94f, width / 0.5f)
        c.translate(width / 2f, (height - h) / 2f + h * 0.03f)
        c.scale(h, h)
        val pose = Pose().apply {
            val s = standing
            eyeOpen = 1f; smile = 0.35f; armSwing[0] = s.armSwing[0]; armSwing[1] = s.armSwing[1]
            armBend[0] = s.armBend[0]; armBend[1] = s.armBend[1]
        }
        BodyPainter(spec, accent, face).drawFigure(c, pose, 0f)
        return bmp
    }
}

package com.spark.companion.ui.avatar

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PointF
import android.graphics.Rect
import android.graphics.RectF
import android.media.ExifInterface
import android.media.FaceDetector
import android.net.Uri
import android.os.Build
import java.io.ByteArrayOutputStream
import kotlin.math.max
import kotlin.math.min

/** Loading pictures, finding the face and framing a portrait for animation. */
object FaceTools {

    const val PORTRAIT_SIZE = 640

    /** Decodes an image the user picked, upright and at a sensible size. */
    fun load(context: Context, uri: Uri, maxSide: Int = 1600): Bitmap? = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val source = ImageDecoder.createSource(context.contentResolver, uri)
            ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                val w = info.size.width
                val h = info.size.height
                val scale = min(1f, maxSide.toFloat() / max(w, h))
                if (scale < 1f) decoder.setTargetSize((w * scale).toInt().coerceAtLeast(1), (h * scale).toInt().coerceAtLeast(1))
            }
        } else {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            var sample = 1
            while (max(bounds.outWidth, bounds.outHeight) / sample > maxSide * 2) sample *= 2
            val raw = context.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
            }
            val rotation = context.contentResolver.openInputStream(uri)?.use {
                when (ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                    ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                    ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                    ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                    else -> 0f
                }
            } ?: 0f
            raw?.let { scaleToMax(rotate(it, rotation), maxSide) }
        }
    } catch (e: Exception) {
        null
    } catch (e: OutOfMemoryError) {
        null
    }

    fun decode(bytes: ByteArray): Bitmap? = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)

    private fun rotate(b: Bitmap, degrees: Float): Bitmap {
        if (degrees == 0f) return b
        val m = Matrix().apply { postRotate(degrees) }
        return Bitmap.createBitmap(b, 0, 0, b.width, b.height, m, true)
    }

    private fun scaleToMax(b: Bitmap, maxSide: Int): Bitmap {
        val scale = maxSide.toFloat() / max(b.width, b.height)
        if (scale >= 1f) return b
        return Bitmap.createScaledBitmap(b, (b.width * scale).toInt(), (b.height * scale).toInt(), true)
    }

    /** Eye midpoint and eye distance in [b]'s pixels, from Android's built-in detector. */
    class Face(val eyesX: Float, val eyesY: Float, val eyeDistance: Float)

    fun detect(b: Bitmap): Face? = try {
        // The detector needs RGB_565 and an even width; work on a smaller copy.
        val scale = min(1f, 720f / max(b.width, b.height))
        var w = (b.width * scale).toInt()
        if (w % 2 == 1) w -= 1
        val h = (b.height * scale).toInt()
        val small = Bitmap.createBitmap(w.coerceAtLeast(2), h.coerceAtLeast(2), Bitmap.Config.RGB_565)
        Canvas(small).drawBitmap(b, null, Rect(0, 0, small.width, small.height), Paint(Paint.FILTER_BITMAP_FLAG))
        val faces = arrayOfNulls<FaceDetector.Face>(3)
        val found = FaceDetector(small.width, small.height, faces.size).findFaces(small, faces)
        val best = faces.take(found).filterNotNull().maxByOrNull { it.eyesDistance() }
        best?.let {
            val mid = PointF()
            it.getMidPoint(mid)
            val sx = b.width.toFloat() / small.width
            val sy = b.height.toFloat() / small.height
            Face(mid.x * sx, mid.y * sy, it.eyesDistance() * sx)
        }
    } catch (e: Exception) {
        null
    }

    /** A square portrait centred on the face, plus where its features are. */
    class Portrait(val bitmap: Bitmap, val landmarks: FaceLandmarks, val faceFound: Boolean)

    /**
     * Crops [b] to a square around the face (head and shoulders) and scales it
     * to [PORTRAIT_SIZE]. With no face found, takes the centre square.
     */
    fun portrait(b: Bitmap, tight: Boolean = true): Portrait {
        val face = detect(b)
        val side: Float
        val cx: Float
        val cy: Float
        if (face != null && tight) {
            side = min(min(b.width, b.height).toFloat(), face.eyeDistance * 3.6f)
            cx = face.eyesX
            cy = face.eyesY + face.eyeDistance * 0.45f
        } else {
            side = min(b.width, b.height).toFloat()
            cx = b.width / 2f
            cy = b.height / 2f
        }
        val left = (cx - side / 2).coerceIn(0f, b.width - side)
        val top = (cy - side / 2).coerceIn(0f, b.height - side)
        val out = Bitmap.createBitmap(PORTRAIT_SIZE, PORTRAIT_SIZE, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        canvas.drawColor(Color.BLACK)
        canvas.drawBitmap(
            b, Rect(left.toInt(), top.toInt(), (left + side).toInt(), (top + side).toInt()),
            RectF(0f, 0f, PORTRAIT_SIZE.toFloat(), PORTRAIT_SIZE.toFloat()), Paint(Paint.FILTER_BITMAP_FLAG)
        )
        val landmarks = if (face != null) {
            FaceLandmarks.estimate((face.eyesX - left) / side, (face.eyesY - top) / side, face.eyeDistance / side)
        } else {
            // Try once more on the cropped picture before guessing.
            detect(out)?.let { FaceLandmarks.estimate(it.eyesX / PORTRAIT_SIZE, it.eyesY / PORTRAIT_SIZE, it.eyeDistance / PORTRAIT_SIZE) }
                ?: FaceLandmarks.CENTERED
        }
        return Portrait(out, landmarks, face != null)
    }

    /** JPEG bytes to send to Gemini (at most 1024 px). */
    fun jpeg(b: Bitmap, maxSide: Int = 1024): ByteArray {
        val scaled = scaleToMax(b, maxSide)
        return ByteArrayOutputStream().also { scaled.compress(Bitmap.CompressFormat.JPEG, 90, it) }.toByteArray()
    }
}

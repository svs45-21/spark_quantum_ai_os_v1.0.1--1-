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
import android.net.Uri
import android.os.Build
import com.google.android.gms.tasks.Task
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import com.google.mlkit.vision.face.FaceLandmark
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.ByteArrayOutputStream
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
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

    /** The face features ML Kit found, in the source photo's pixels. */
    class Face(
        val leftEyeX: Float, val leftEyeY: Float,
        val rightEyeX: Float, val rightEyeY: Float,
        val mouthLeftX: Float, val mouthLeftY: Float,
        val mouthRightX: Float, val mouthRightY: Float,
    ) {
        val eyeDistance: Float get() = kotlin.math.hypot(rightEyeX - leftEyeX, rightEyeY - leftEyeY).coerceAtLeast(1f)
    }

    /**
     * Finds the most prominent face with ML Kit's on-device detector and
     * returns its eye and mouth-corner positions. Eyes are ordered by image x
     * (left first). Missing mouth corners are estimated from the eyes. Returns
     * null if no face is found or the detector isn't available.
     */
    suspend fun detect(b: Bitmap): Face? {
        val options = FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE)
            .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_ALL)
            .setMinFaceSize(0.12f)
            .build()
        val detector = FaceDetection.getClient(options)
        val faces = try {
            detector.process(InputImage.fromBitmap(b, 0)).await()
        } catch (e: Throwable) {
            return null
        } finally {
            try {
                detector.close()
            } catch (e: Exception) {
                // already closed
            }
        }
        val face = faces.maxByOrNull { it.boundingBox.width().toLong() * it.boundingBox.height() } ?: return null

        val eyes = listOfNotNull(
            face.getLandmark(FaceLandmark.LEFT_EYE)?.position,
            face.getLandmark(FaceLandmark.RIGHT_EYE)?.position,
        ).sortedBy { it.x }
        val box = face.boundingBox
        val (lEye, rEye) = if (eyes.size == 2) {
            eyes[0] to eyes[1]
        } else {
            // No eye landmarks: fall back to typical positions in the face box.
            android.graphics.PointF(box.left + box.width() * 0.33f, box.top + box.height() * 0.42f) to
                android.graphics.PointF(box.left + box.width() * 0.67f, box.top + box.height() * 0.42f)
        }
        val eyeDist = kotlin.math.hypot((rEye.x - lEye.x), (rEye.y - lEye.y)).coerceAtLeast(1f)

        val mouthPts = listOfNotNull(
            face.getLandmark(FaceLandmark.MOUTH_LEFT)?.position,
            face.getLandmark(FaceLandmark.MOUTH_RIGHT)?.position,
        ).sortedBy { it.x }
        val (lMouth, rMouth) = if (mouthPts.size == 2) {
            mouthPts[0] to mouthPts[1]
        } else {
            // Estimate the mouth below the eyes, along the face's own tilt.
            val cx = (lEye.x + rEye.x) / 2f
            val cy = (lEye.y + rEye.y) / 2f
            val ux = (rEye.x - lEye.x) / eyeDist
            val uy = (rEye.y - lEye.y) / eyeDist
            val downX = -uy
            val downY = ux
            val mcx = cx + downX * eyeDist * 1.05f
            val mcy = cy + downY * eyeDist * 1.05f
            android.graphics.PointF(mcx - ux * eyeDist * 0.4f, mcy - uy * eyeDist * 0.4f) to
                android.graphics.PointF(mcx + ux * eyeDist * 0.4f, mcy + uy * eyeDist * 0.4f)
        }
        return Face(lEye.x, lEye.y, rEye.x, rEye.y, lMouth.x, lMouth.y, rMouth.x, rMouth.y)
    }

    private suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { cont ->
        addOnCompleteListener { task ->
            val error = task.exception
            if (error != null) cont.resumeWithException(error)
            else cont.resume(task.result)
        }
    }

    /** A square portrait centred on the face, plus where its features are. */
    class Portrait(val bitmap: Bitmap, val landmarks: FaceLandmarks, val faceFound: Boolean)

    /**
     * Crops [b] to an upright, centred portrait around the face and scales it
     * to [PORTRAIT_SIZE]. Tilted and slightly angled faces are rotated so the
     * eyes are level, which is what the animation needs. With no face found,
     * takes the centre square and leaves the dots to be placed by hand.
     */
    suspend fun portrait(b: Bitmap, tight: Boolean = true): Portrait {
        @Suppress("UNUSED_PARAMETER") val ignored = tight
        val face = detect(b)
        val out = Bitmap.createBitmap(PORTRAIT_SIZE, PORTRAIT_SIZE, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        canvas.drawColor(Color.BLACK)
        if (face == null) {
            val side = min(b.width, b.height).toFloat()
            val left = (b.width - side) / 2
            val top = (b.height - side) / 2
            canvas.drawBitmap(
                b, Rect(left.toInt(), top.toInt(), (left + side).toInt(), (top + side).toInt()),
                RectF(0f, 0f, PORTRAIT_SIZE.toFloat(), PORTRAIT_SIZE.toFloat()), Paint(Paint.FILTER_BITMAP_FLAG)
            )
            return Portrait(out, FaceLandmarks.CENTERED, false)
        }
        val framing = Framing.forEyes(face.leftEyeX, face.leftEyeY, face.rightEyeX, face.rightEyeY)
        val matrix = Matrix().apply { setValues(framing.matrixValues()) }
        canvas.drawBitmap(b, matrix, Paint(Paint.FILTER_BITMAP_FLAG))
        fun f(x: Float, y: Float): FloatArray = framing.mapFrac(x, y).also {
            it[0] = it[0].coerceIn(0.02f, 0.98f); it[1] = it[1].coerceIn(0.02f, 0.98f)
        }
        val le = f(face.leftEyeX, face.leftEyeY)
        val re = f(face.rightEyeX, face.rightEyeY)
        val ml = f(face.mouthLeftX, face.mouthLeftY)
        val mr = f(face.mouthRightX, face.mouthRightY)
        val landmarks = FaceLandmarks(le[0], le[1], re[0], re[1], ml[0], ml[1], mr[0], mr[1])
        return Portrait(out, landmarks, true)
    }

    /** JPEG bytes for sending a photo to Claude (at most 1024 px). */
    fun jpeg(b: Bitmap, maxSide: Int = 1024): ByteArray {
        val scaled = scaleToMax(b, maxSide)
        return ByteArrayOutputStream().also { scaled.compress(Bitmap.CompressFormat.JPEG, 90, it) }.toByteArray()
    }
}

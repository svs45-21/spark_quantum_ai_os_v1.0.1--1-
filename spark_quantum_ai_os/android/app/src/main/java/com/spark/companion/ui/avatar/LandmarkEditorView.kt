package com.spark.companion.ui.avatar

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import kotlin.math.hypot
import kotlin.math.min

/**
 * Shows the portrait with four draggable dots: both eyes and both mouth
 * corners. Getting them right is what makes blinking and talking look real.
 */
class LandmarkEditorView(context: Context) : View(context) {

    var bitmap: Bitmap? = null
        set(value) {
            field = value
            invalidate()
        }

    var landmarks: FaceLandmarks = FaceLandmarks.CENTERED
        set(value) {
            field = value
            invalidate()
        }

    var onChange: (FaceLandmarks) -> Unit = {}

    private val image = RectF()
    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textAlign = Paint.Align.CENTER }
    private var dragging = -1
    private val density = resources.displayMetrics.density

    private val colors = intArrayOf(
        Color.parseColor("#00D4FF"), Color.parseColor("#00D4FF"),
        Color.parseColor("#FF6FA8"), Color.parseColor("#FF6FA8"),
    )
    private val names = arrayOf("Eye", "Eye", "Mouth", "Mouth")

    private fun points(): FloatArray = with(landmarks) {
        floatArrayOf(leftEyeX, leftEyeY, rightEyeX, rightEyeY, mouthLeftX, mouthLeftY, mouthRightX, mouthRightY)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        setMeasuredDimension(w, w)
    }

    override fun onDraw(canvas: Canvas) {
        val b = bitmap ?: return
        val side = min(width, height).toFloat()
        image.set((width - side) / 2, (height - side) / 2, (width + side) / 2, (height + side) / 2)
        canvas.drawBitmap(b, null, image, bitmapPaint)
        val p = points()
        label.textSize = 11f * density
        for (i in 0 until 4) {
            val x = image.left + p[2 * i] * image.width()
            val y = image.top + p[2 * i + 1] * image.height()
            dot.color = colors[i]
            dot.alpha = if (i == dragging) 255 else 200
            ring.color = Color.BLACK
            ring.strokeWidth = 1.5f * density
            canvas.drawCircle(x, y, 7f * density, dot)
            canvas.drawCircle(x, y, 7f * density, ring)
            canvas.drawText(names[i], x, y - 11f * density, label)
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (bitmap == null || image.width() <= 0f) return false
        val u = ((e.x - image.left) / image.width()).coerceIn(0.02f, 0.98f)
        val v = ((e.y - image.top) / image.height()).coerceIn(0.02f, 0.98f)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val p = points()
                var best = -1
                var bestDist = 36f * density
                for (i in 0 until 4) {
                    val dx = image.left + p[2 * i] * image.width() - e.x
                    val dy = image.top + p[2 * i + 1] * image.height() - e.y
                    val d = hypot(dx, dy)
                    if (d < bestDist) {
                        bestDist = d
                        best = i
                    }
                }
                dragging = best
                if (best >= 0) parent?.requestDisallowInterceptTouchEvent(true)
                invalidate()
                return best >= 0
            }
            MotionEvent.ACTION_MOVE -> if (dragging >= 0) {
                val p = points()
                p[2 * dragging] = u
                p[2 * dragging + 1] = v
                landmarks = FaceLandmarks(p[0], p[1], p[2], p[3], p[4], p[5], p[6], p[7])
                onChange(landmarks)
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                dragging = -1
                invalidate()
                return true
            }
        }
        return dragging >= 0
    }
}

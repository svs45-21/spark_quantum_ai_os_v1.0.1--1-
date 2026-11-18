package com.spark.companion.ui

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.widget.TextView

/** Shared colours and small view helpers. All UI is built in code. */
object Ui {
    // A vibrant palette: deep violet night with hot pink, sunset orange, teal and gold.
    val BG = Color.parseColor("#1A1040")
    val BG_TOP = Color.parseColor("#3A1C7A")
    val BG_BOTTOM = Color.parseColor("#0C2E4A")
    val SURFACE = Color.parseColor("#2A1D5C")
    val SURFACE_HI = Color.parseColor("#3B2A7A")
    val ACCENT = Color.parseColor("#FF5FA2")
    val ACCENT_2 = Color.parseColor("#FF9A3D")
    val TEAL = Color.parseColor("#22E0D0")
    val GOLD = Color.parseColor("#FFD24A")
    val VIOLET = Color.parseColor("#A47CFF")
    val TEXT = Color.parseColor("#FFFFFF")
    val TEXT_DIM = Color.parseColor("#CDBFF7")
    val DANGER = Color.parseColor("#FF6B6B")
    val OK = Color.parseColor("#2EE6A6")

    /** The screen background: a violet-to-ocean gradient. */
    fun backdrop(): GradientDrawable = GradientDrawable(
        GradientDrawable.Orientation.TL_BR, intArrayOf(BG_TOP, BG, BG_BOTTOM),
    )

    fun dp(context: Context, value: Float): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, context.resources.displayMetrics).toInt()

    fun dp(context: Context, value: Int): Int = dp(context, value.toFloat())

    fun rounded(color: Int, radiusPx: Float, strokeColor: Int? = null, strokePx: Int = 0): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = radiusPx
            setColor(color)
            if (strokeColor != null) setStroke(strokePx, strokeColor)
        }

    fun text(
        context: Context,
        value: CharSequence,
        sizeSp: Float = 15f,
        color: Int = TEXT,
        bold: Boolean = false,
    ): TextView = TextView(context).apply {
        text = value
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
        setTextColor(color)
        if (bold) typeface = Typeface.DEFAULT_BOLD
    }

    /** A pill-shaped button made from a TextView (keeps Material defaults out of the way). */
    fun button(
        context: Context,
        label: CharSequence,
        fill: Int = ACCENT,
        textColor: Int = BG,
        onClick: () -> Unit,
    ): TextView = text(context, label, 15f, if (fill == ACCENT) Color.WHITE else textColor, bold = true).apply {
        gravity = Gravity.CENTER
        // The main action gets a pink-to-orange gradient; others stay flat.
        background = if (fill == ACCENT) {
            GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(ACCENT, ACCENT_2)).apply {
                cornerRadius = dp(context, 22).toFloat()
            }
        } else rounded(fill, dp(context, 22).toFloat())
        setPadding(dp(context, 18), dp(context, 10), dp(context, 18), dp(context, 10))
        minHeight = dp(context, 44)
        isClickable = true
        isFocusable = true
        setOnClickListener { onClick() }
    }
}

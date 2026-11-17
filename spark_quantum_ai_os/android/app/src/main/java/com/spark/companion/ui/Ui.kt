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
    val BG = Color.parseColor("#070B14")
    val BG_TOP = Color.parseColor("#0D1426")
    val SURFACE = Color.parseColor("#131C30")
    val SURFACE_HI = Color.parseColor("#1B2742")
    val ACCENT = Color.parseColor("#00D4FF")
    val VIOLET = Color.parseColor("#7C5CFF")
    val TEXT = Color.parseColor("#E8EEF7")
    val TEXT_DIM = Color.parseColor("#8C9AB5")
    val DANGER = Color.parseColor("#FF5C7A")
    val OK = Color.parseColor("#3DDC97")

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
    ): TextView = text(context, label, 15f, textColor, bold = true).apply {
        gravity = Gravity.CENTER
        background = rounded(fill, dp(context, 22).toFloat())
        setPadding(dp(context, 18), dp(context, 10), dp(context, 18), dp(context, 10))
        minHeight = dp(context, 44)
        isClickable = true
        isFocusable = true
        setOnClickListener { onClick() }
    }
}

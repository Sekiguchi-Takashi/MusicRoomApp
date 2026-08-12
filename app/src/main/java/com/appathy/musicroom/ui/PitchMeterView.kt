package com.appathy.musicroom.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View

class PitchMeterView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    /** -50..+50 セント。voiced が false のときは針を描かない。 */
    var cents: Double = 0.0
        set(value) {
            field = value.coerceIn(-50.0, 50.0)
            postInvalidateOnAnimation()
        }

    var voiced: Boolean = false
        set(value) {
            field = value
            postInvalidateOnAnimation()
        }

    private val scalePaint = Paint().apply { color = Color.parseColor("#2A3242") }
    private val centerPaint = Paint().apply { color = Color.parseColor("#4ECDC4") }
    private val needlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FFD166")
        strokeWidth = 8f
        strokeCap = Paint.Cap.ROUND
    }
    private val inTunePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#4ECDC4")
        strokeWidth = 8f
        strokeCap = Paint.Cap.ROUND
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#8B94A7")
        textSize = 26f
        textAlign = Paint.Align.CENTER
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        val top = h * 0.18f
        val bottom = h * 0.72f

        for (i in -5..5) {
            val x = w / 2f + w * 0.44f * i / 5f
            val tall = i % 5 == 0
            canvas.drawRect(
                x - 2f,
                if (tall) top else top + (bottom - top) * 0.25f,
                x + 2f,
                bottom,
                if (i == 0) centerPaint else scalePaint
            )
        }
        canvas.drawText("♭ -50", w * 0.10f, h * 0.93f, labelPaint)
        canvas.drawText("0", w / 2f, h * 0.93f, labelPaint)
        canvas.drawText("+50 ♯", w * 0.90f, h * 0.93f, labelPaint)

        if (!voiced) return
        val x = w / 2f + w * 0.44f * (cents / 50.0).toFloat()
        canvas.drawLine(
            x, top - h * 0.10f, x, bottom + h * 0.05f,
            if (kotlin.math.abs(cents) <= 10) inTunePaint else needlePaint
        )
    }
}

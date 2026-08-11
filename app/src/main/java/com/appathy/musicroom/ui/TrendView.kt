package com.appathy.musicroom.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View

class TrendView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    /** 古い順に並べた 0.0..1.0 の値。 */
    var values: List<Double> = emptyList()
        set(value) {
            field = value
            invalidate()
        }

    private val gridPaint = Paint().apply { color = Color.parseColor("#2A3242") }
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#4ECDC4")
        style = Paint.Style.STROKE
        strokeWidth = 5f
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#FFD166") }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#8B94A7")
        textSize = 26f
    }

    private val path = Path()

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        val padLeft = 62f
        val padTop = 14f
        val padBottom = 20f
        val plotW = w - padLeft - 12f
        val plotH = h - padTop - padBottom

        for (i in 0..4) {
            val y = padTop + plotH * i / 4f
            canvas.drawLine(padLeft, y, w - 12f, y, gridPaint)
            canvas.drawText((100 - i * 25).toString() + "%", 6f, y + 9f, labelPaint)
        }

        if (values.size < 2) {
            canvas.drawText("データがまだ足りません", padLeft + 12f, padTop + plotH / 2f, labelPaint)
            return
        }

        path.reset()
        values.forEachIndexed { index, value ->
            val x = padLeft + plotW * index / (values.size - 1).toFloat()
            val y = padTop + plotH * (1f - value.coerceIn(0.0, 1.0).toFloat())
            if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        canvas.drawPath(path, linePaint)

        values.forEachIndexed { index, value ->
            val x = padLeft + plotW * index / (values.size - 1).toFloat()
            val y = padTop + plotH * (1f - value.coerceIn(0.0, 1.0).toFloat())
            canvas.drawCircle(x, y, 7f, dotPaint)
        }
    }
}

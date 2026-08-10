package com.appathy.musicroom.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import com.appathy.musicroom.audio.MusicTheory
import com.appathy.musicroom.game.ChartGenerator
import com.appathy.musicroom.game.NoteItem

class RhythmView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    interface Callback {
        fun onLaneTouched(lane: Int)
        fun currentTimeMs(): Double
        fun isRunning(): Boolean
    }

    var callback: Callback? = null
    var notes: List<NoteItem> = emptyList()
    var approachMs: Double = 1600.0

    private val lanes = ChartGenerator.LANES
    private val flashUntil = DoubleArray(lanes)

    private val bgPaint = Paint().apply { color = Color.parseColor("#0F1218") }
    private val lanePaint = Paint().apply { color = Color.parseColor("#171C27") }
    private val laneAltPaint = Paint().apply { color = Color.parseColor("#1D2431") }
    private val flashPaint = Paint().apply { color = Color.parseColor("#334ECDC4") }
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#4ECDC4")
        strokeWidth = 5f
    }
    private val notePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#FFD166") }
    private val noteAccentPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#EF6461") }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#8B94A7")
        textAlign = Paint.Align.CENTER
    }

    private val rect = RectF()

    fun flash(lane: Int) {
        if (lane in 0 until lanes) {
            flashUntil[lane] = (callback?.currentTimeMs() ?: 0.0) + 120.0
            postInvalidateOnAnimation()
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        val laneW = w / lanes
        val judgeY = h * 0.80f
        val now = callback?.currentTimeMs() ?: 0.0

        canvas.drawRect(0f, 0f, w, h, bgPaint)
        labelPaint.textSize = laneW * 0.26f

        for (i in 0 until lanes) {
            rect.set(i * laneW, 0f, (i + 1) * laneW, h)
            canvas.drawRect(rect, if (i % 2 == 0) lanePaint else laneAltPaint)
            if (now < flashUntil[i]) canvas.drawRect(rect, flashPaint)
            canvas.drawText(
                MusicTheory.pitchClassName(ChartGenerator.noteOfLane(i)),
                i * laneW + laneW / 2f,
                h - laneW * 0.12f,
                labelPaint
            )
        }

        canvas.drawLine(0f, judgeY, w, judgeY, linePaint)

        val noteH = laneW * 0.34f
        for (note in notes) {
            if (note.judged) continue
            val delta = note.timeMs - now
            if (delta > approachMs || delta < -400.0) continue
            val y = judgeY * (1.0 - delta / approachMs).toFloat()
            val cx = note.lane * laneW + laneW / 2f
            rect.set(cx - laneW * 0.38f, y - noteH / 2f, cx + laneW * 0.38f, y + noteH / 2f)
            canvas.drawRoundRect(rect, noteH * 0.35f, noteH * 0.35f, if (delta < 0) noteAccentPaint else notePaint)
        }

        if (callback?.isRunning() == true) postInvalidateOnAnimation()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN ||
            event.actionMasked == MotionEvent.ACTION_POINTER_DOWN
        ) {
            val index = event.actionIndex
            val laneW = width.toFloat() / lanes
            val lane = (event.getX(index) / laneW).toInt().coerceIn(0, lanes - 1)
            callback?.onLaneTouched(lane)
        }
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }
}

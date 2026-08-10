package com.appathy.musicroom.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import com.appathy.musicroom.midi.MusicEvent
import com.appathy.musicroom.game.Judgement
import com.appathy.musicroom.song.SongChart

class SongRollView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    interface Callback {
        fun currentTimeMs(): Double
        fun isRunning(): Boolean
        fun onPitchTouched(pitch: Int)
    }

    var callback: Callback? = null
    var chart: SongChart? = null
    var approachMs: Double = 2000.0

    private val flashUntil = HashMap<Int, Double>()

    private val bgPaint = Paint().apply { color = Color.parseColor("#0F1218") }
    private val lanePaint = Paint().apply { color = Color.parseColor("#171C27") }
    private val laneAltPaint = Paint().apply { color = Color.parseColor("#1D2431") }
    private val flashPaint = Paint().apply { color = Color.parseColor("#334ECDC4") }
    private val barPaint = Paint().apply { color = Color.parseColor("#3A445C") }
    private val judgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#4ECDC4")
        strokeWidth = 5f
    }
    private val notePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#FFD166") }
    private val donePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#3D7A72") }
    private val missPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#EF6461") }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#8B94A7")
        textAlign = Paint.Align.CENTER
    }
    private val barLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#5A6479")
        textAlign = Paint.Align.LEFT
    }

    private val rect = RectF()

    fun flash(pitch: Int) {
        flashUntil[pitch] = (callback?.currentTimeMs() ?: 0.0) + 130.0
        postInvalidateOnAnimation()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val c = chart ?: return
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f || c.pitches.isEmpty()) return

        val laneCount = c.pitches.size
        val laneW = w / laneCount
        val judgeY = h * 0.80f
        val now = callback?.currentTimeMs() ?: 0.0

        canvas.drawRect(0f, 0f, w, h, bgPaint)
        labelPaint.textSize = minOf(laneW * 0.32f, h * 0.05f)
        barLabelPaint.textSize = minOf(laneW * 0.26f, h * 0.04f)

        for (i in 0 until laneCount) {
            rect.set(i * laneW, 0f, (i + 1) * laneW, h)
            canvas.drawRect(rect, if (i % 2 == 0) lanePaint else laneAltPaint)
            val until = flashUntil[c.pitches[i]] ?: 0.0
            if (now < until) canvas.drawRect(rect, flashPaint)
            canvas.drawText(
                MusicEvent.noteName(c.pitches[i]),
                i * laneW + laneW / 2f,
                h - laneW * 0.10f,
                labelPaint
            )
        }

        c.barStartMs.forEachIndexed { index, t ->
            val delta = t - now
            if (delta > approachMs || delta < -300.0) return@forEachIndexed
            val y = judgeY * (1.0 - delta / approachMs).toFloat()
            canvas.drawLine(0f, y, w, y, barPaint)
            canvas.drawText(
                (c.measureNumbers.getOrElse(index) { 0 } + 1).toString() + "小節",
                8f,
                y - 6f,
                barLabelPaint
            )
        }

        canvas.drawLine(0f, judgeY, w, judgeY, judgePaint)

        for (note in c.notes) {
            val delta = note.timeMs - now
            if (delta > approachMs || delta < -600.0) continue
            val lane = c.pitches.indexOf(note.pitch)
            if (lane < 0) continue
            val yHead = judgeY * (1.0 - delta / approachMs).toFloat()
            val lengthPx = (note.durationMs / approachMs * judgeY).toFloat().coerceAtLeast(laneW * 0.22f)
            val cx = lane * laneW + laneW / 2f
            rect.set(cx - laneW * 0.34f, yHead - lengthPx, cx + laneW * 0.34f, yHead)
            val paint = when (note.judgement) {
                null -> notePaint
                Judgement.MISS -> missPaint
                else -> donePaint
            }
            canvas.drawRoundRect(rect, laneW * 0.14f, laneW * 0.14f, paint)
        }

        if (callback?.isRunning() == true) postInvalidateOnAnimation()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val c = chart ?: return true
        if (c.pitches.isEmpty()) return true
        if (event.actionMasked == MotionEvent.ACTION_DOWN ||
            event.actionMasked == MotionEvent.ACTION_POINTER_DOWN
        ) {
            val laneW = width.toFloat() / c.pitches.size
            val lane = (event.getX(event.actionIndex) / laneW).toInt().coerceIn(0, c.pitches.size - 1)
            callback?.onPitchTouched(c.pitches[lane])
        }
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }
}

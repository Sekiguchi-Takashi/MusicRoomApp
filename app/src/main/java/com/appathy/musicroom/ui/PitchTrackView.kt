package com.appathy.musicroom.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import com.appathy.musicroom.midi.MusicEvent
import com.appathy.musicroom.song.SongChart

class PitchTrackView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    interface Callback {
        fun currentTimeMs(): Double
        fun isRunning(): Boolean
    }

    class Sample(val timeMs: Double, val midi: Double)

    var callback: Callback? = null
    var chart: SongChart? = null

    /** 表示する時間幅 (ms)。now は画面左から 30% の位置。 */
    var spanMs: Double = 5000.0

    private val trail = ArrayList<Sample>()
    private var minMidi = 55.0
    private var maxMidi = 76.0

    private val bgPaint = Paint().apply { color = Color.parseColor("#0F1218") }
    private val rowPaint = Paint().apply { color = Color.parseColor("#171C27") }
    private val rowAltPaint = Paint().apply { color = Color.parseColor("#1D2431") }
    private val targetPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#3B4A63") }
    private val targetActivePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#FFD166") }
    private val nowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#4ECDC4")
        strokeWidth = 4f
    }
    private val trailPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#4ECDC4")
        style = Paint.Style.STROKE
        strokeWidth = 6f
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#EF6461") }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#7A8194")
        textSize = 24f
    }

    private val rect = RectF()
    private val path = Path()

    fun reset() {
        trail.clear()
        val c = chart
        if (c != null && c.pitches.isNotEmpty()) {
            minMidi = ((c.pitches.minOrNull() ?: 60) - 4).toDouble()
            maxMidi = ((c.pitches.maxOrNull() ?: 72) + 4).toDouble()
        }
        postInvalidateOnAnimation()
    }

    fun addSample(timeMs: Double, midi: Double) {
        trail.add(Sample(timeMs, midi))
        while (trail.size > 2400) trail.removeAt(0)
        postInvalidateOnAnimation()
    }

    private fun yOf(midi: Double, h: Float): Float {
        val ratio = ((midi - minMidi) / (maxMidi - minMidi)).coerceIn(0.0, 1.0)
        return (h * (1.0 - ratio)).toFloat()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        val c = chart ?: return
        val now = callback?.currentTimeMs() ?: 0.0
        val nowX = w * 0.30f

        canvas.drawRect(0f, 0f, w, h, bgPaint)

        val low = minMidi.toInt()
        val high = maxMidi.toInt()
        for (m in low..high) {
            val yTop = yOf(m + 0.5, h)
            val yBottom = yOf(m - 0.5, h)
            canvas.drawRect(0f, yTop, w, yBottom, if (m % 2 == 0) rowPaint else rowAltPaint)
            if (m % 12 == 0) {
                canvas.drawText(MusicEvent.noteName(m), 6f, yBottom - 6f, labelPaint)
            }
        }

        fun xOf(t: Double): Float = (nowX + (t - now) / spanMs * (w * 0.70f)).toFloat()

        c.notes.forEach { note ->
            val x1 = xOf(note.timeMs)
            val x2 = xOf(note.timeMs + note.durationMs)
            if (x2 < -20f || x1 > w + 20f) return@forEach
            val y = yOf(note.pitch.toDouble(), h)
            val half = (yOf(minMidi + 1, h) - yOf(minMidi + 1.7, h)) / 2f
            rect.set(x1, y - half, x2, y + half)
            val active = now >= note.timeMs && now <= note.timeMs + note.durationMs
            canvas.drawRoundRect(rect, half, half, if (active) targetActivePaint else targetPaint)
        }

        if (trail.size >= 2) {
            path.reset()
            var started = false
            var previousTime = 0.0
            trail.forEach { sample ->
                val x = xOf(sample.timeMs)
                if (x < -40f) {
                    started = false
                    return@forEach
                }
                val y = yOf(sample.midi, h)
                if (!started || sample.timeMs - previousTime > 200.0) {
                    path.moveTo(x, y)
                    started = true
                } else {
                    path.lineTo(x, y)
                }
                previousTime = sample.timeMs
            }
            canvas.drawPath(path, trailPaint)
        }

        trail.lastOrNull()?.let { sample ->
            if (now - sample.timeMs < 220.0) {
                canvas.drawCircle(xOf(sample.timeMs), yOf(sample.midi, h), 9f, dotPaint)
            }
        }

        canvas.drawLine(nowX, 0f, nowX, h, nowPaint)

        if (callback?.isRunning() == true) postInvalidateOnAnimation()
    }
}

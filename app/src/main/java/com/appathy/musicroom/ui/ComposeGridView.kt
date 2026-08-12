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
import com.appathy.musicroom.song.SongNote
import kotlin.math.floor

/**
 * 横=拍, 縦=音高のグリッド。タップで音の追加/削除、横ドラッグで長さの変更。
 */
class ComposeGridView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    interface Callback {
        fun onNotesChanged()
        fun onNotePreview(pitch: Int)
    }

    var callback: Callback? = null

    var notes: MutableList<SongNote> = ArrayList()
        set(value) {
            field = value
            postInvalidateOnAnimation()
        }

    var lowPitch = 60
    var highPitch = 72
    var beatsPerBar = 4
    var grid = 2
    var barCount = 4

    /** 再生位置 (拍)。負なら描かない。 */
    var playheadBeat: Double = -1.0
        set(value) {
            field = value
            postInvalidateOnAnimation()
        }

    var scrollBeats: Double = 0.0
        set(value) {
            field = value.coerceIn(0.0, maxOf(0.0, totalBeats() - visibleBeats))
            postInvalidateOnAnimation()
        }

    /** 一度に見せる拍数。 */
    var visibleBeats: Double = 8.0

    private val bgPaint = Paint().apply { color = Color.parseColor("#0F1218") }
    private val rowPaint = Paint().apply { color = Color.parseColor("#171C27") }
    private val rowBlackPaint = Paint().apply { color = Color.parseColor("#12161F") }
    private val gridPaint = Paint().apply { color = Color.parseColor("#232B3A") }
    private val barPaint = Paint().apply { color = Color.parseColor("#3A445C") }
    private val notePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#FFD166") }
    private val headPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#4ECDC4")
        strokeWidth = 4f
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#7A8194")
        textSize = 22f
    }

    private val rect = RectF()
    private val blackKeys = setOf(1, 3, 6, 8, 10)

    private var dragNote: SongNote? = null
    private var dragStartX = 0f
    private var dragOriginalLength = 0.0
    private var dragged = false
    private var lastScrollX = 0f
    private var scrolling = false

    private val labelWidth = 54f

    fun totalBeats(): Double = (barCount * beatsPerBar).toDouble()

    private fun rowCount(): Int = highPitch - lowPitch + 1

    private fun plotWidth(): Float = width - labelWidth

    private fun xOf(beat: Double): Float =
        labelWidth + ((beat - scrollBeats) / visibleBeats * plotWidth()).toFloat()

    private fun beatOf(x: Float): Double =
        scrollBeats + (x - labelWidth) / plotWidth() * visibleBeats

    private fun rowHeight(): Float = height.toFloat() / rowCount()

    private fun pitchOf(y: Float): Int =
        (highPitch - floor(y / rowHeight()).toInt()).coerceIn(lowPitch, highPitch)

    private fun yOf(pitch: Int): Float = (highPitch - pitch) * rowHeight()

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        canvas.drawRect(0f, 0f, w, h, bgPaint)

        val rh = rowHeight()
        for (pitch in lowPitch..highPitch) {
            val y = yOf(pitch)
            val black = (pitch % 12) in blackKeys
            canvas.drawRect(0f, y, w, y + rh, if (black) rowBlackPaint else rowPaint)
            if (pitch % 12 == 0 || pitch == lowPitch || pitch == highPitch) {
                canvas.drawText(MusicEvent.noteName(pitch), 6f, y + rh * 0.72f, labelPaint)
            }
        }

        val step = 1.0 / grid
        var beat = floor(scrollBeats / step) * step
        while (beat <= scrollBeats + visibleBeats) {
            val x = xOf(beat)
            val isBar = kotlin.math.abs(beat % beatsPerBar) < 0.001
            canvas.drawRect(x, 0f, x + (if (isBar) 3f else 1.5f), h, if (isBar) barPaint else gridPaint)
            beat += step
        }

        notes.forEach { note ->
            if (note.pitch < lowPitch || note.pitch > highPitch) return@forEach
            val x1 = xOf(note.beat)
            val x2 = xOf(note.beat + note.lengthBeats)
            if (x2 < labelWidth || x1 > w) return@forEach
            val y = yOf(note.pitch)
            rect.set(
                maxOf(x1, labelWidth) + 2f,
                y + rh * 0.14f,
                x2 - 2f,
                y + rh * 0.86f
            )
            canvas.drawRoundRect(rect, rh * 0.18f, rh * 0.18f, notePaint)
        }

        if (playheadBeat >= 0) {
            val x = xOf(playheadBeat)
            if (x >= labelWidth) canvas.drawLine(x, 0f, x, h, headPaint)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val step = 1.0 / grid
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                dragged = false
                scrolling = false
                lastScrollX = event.x
                if (event.x < labelWidth) {
                    scrolling = true
                    return true
                }
                val beat = beatOf(event.x)
                val pitch = pitchOf(event.y)
                dragNote = notes.firstOrNull {
                    it.pitch == pitch && beat >= it.beat && beat < it.beat + it.lengthBeats
                }
                dragStartX = event.x
                dragOriginalLength = dragNote?.lengthBeats ?: 0.0
            }

            MotionEvent.ACTION_MOVE -> {
                if (scrolling) {
                    val delta = (lastScrollX - event.x) / plotWidth() * visibleBeats
                    scrollBeats += delta
                    lastScrollX = event.x
                    return true
                }
                val note = dragNote ?: run {
                    if (kotlin.math.abs(event.x - dragStartX) > 24f) {
                        val delta = (lastScrollX - event.x) / plotWidth() * visibleBeats
                        scrollBeats += delta
                        lastScrollX = event.x
                        dragged = true
                    }
                    return true
                }
                val deltaBeats = (event.x - dragStartX) / plotWidth() * visibleBeats
                val target = dragOriginalLength + deltaBeats
                val snapped = (target / step).toInt().coerceAtLeast(1) * step
                if (kotlin.math.abs(snapped - note.lengthBeats) > 0.001) {
                    val index = notes.indexOf(note)
                    if (index >= 0) {
                        val updated = note.copy(lengthBeats = snapped)
                        notes[index] = updated
                        dragNote = updated
                        dragged = true
                        callback?.onNotesChanged()
                        postInvalidateOnAnimation()
                    }
                }
            }

            MotionEvent.ACTION_UP -> {
                parent?.requestDisallowInterceptTouchEvent(false)
                if (scrolling || dragged) {
                    dragNote = null
                    return true
                }
                val beat = floor(beatOf(event.x) / step) * step
                val pitch = pitchOf(event.y)
                val existing = dragNote
                if (existing != null) {
                    notes.remove(existing)
                } else if (beat >= 0 && beat < totalBeats()) {
                    notes.add(SongNote(beat, step, pitch))
                    notes.sortBy { it.beat }
                    callback?.onNotePreview(pitch)
                }
                dragNote = null
                callback?.onNotesChanged()
                postInvalidateOnAnimation()
            }

            MotionEvent.ACTION_CANCEL -> {
                parent?.requestDisallowInterceptTouchEvent(false)
                dragNote = null
            }
        }
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }
}

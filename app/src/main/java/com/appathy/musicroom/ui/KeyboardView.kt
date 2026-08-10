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
import kotlin.math.abs

class KeyboardView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    interface Callback {
        fun onKeyDown(note: Int, velocity: Int)
        fun onKeyUp(note: Int)
    }

    var callback: Callback? = null

    var baseNote: Int = 48
        set(value) {
            field = value.coerceIn(12, 96)
            invalidate()
        }

    var octaveCount: Int = 2
        set(value) {
            field = value.coerceIn(1, 4)
            invalidate()
        }

    private val whiteSemitones = intArrayOf(0, 2, 4, 5, 7, 9, 11)
    private val blackSemitones = intArrayOf(1, 3, 6, 8, 10)
    private val blackAfterWhite = intArrayOf(0, 1, 3, 4, 5)

    private val whitePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val whiteDownPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#FFD166") }
    private val blackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#151821") }
    private val blackDownPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#E08A2B") }
    private val midiPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#4ECDC4") }
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#40000000")
        style = Paint.Style.STROKE
        strokeWidth = 2f
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#7A8194")
        textAlign = Paint.Align.CENTER
    }

    private val rect = RectF()
    private val pointerNotes = HashMap<Int, Int>()
    private val touchDown = HashSet<Int>()
    private val externalDown = HashSet<Int>()

    private val whiteCount: Int get() = octaveCount * 7

    init {
        isFocusable = true
    }

    fun setExternalNote(note: Int, on: Boolean) {
        if (on) externalDown.add(note) else externalDown.remove(note)
        postInvalidateOnAnimation()
    }

    fun clearExternal() {
        externalDown.clear()
        postInvalidateOnAnimation()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        val whiteW = w / whiteCount
        labelPaint.textSize = whiteW * 0.28f

        for (i in 0 until whiteCount) {
            val note = baseNote + (i / 7) * 12 + whiteSemitones[i % 7]
            rect.set(i * whiteW, 0f, (i + 1) * whiteW, h)
            val paint = when {
                externalDown.contains(note) -> midiPaint
                touchDown.contains(note) -> whiteDownPaint
                else -> whitePaint
            }
            canvas.drawRoundRect(rect, whiteW * 0.08f, whiteW * 0.08f, paint)
            canvas.drawRoundRect(rect, whiteW * 0.08f, whiteW * 0.08f, linePaint)
            if (note % 12 == 0) {
                canvas.drawText(
                    MusicEvent.noteName(note),
                    i * whiteW + whiteW / 2f,
                    h - whiteW * 0.22f,
                    labelPaint
                )
            }
        }

        val blackW = whiteW * 0.62f
        val blackH = h * 0.62f
        for (o in 0 until octaveCount) {
            for (k in blackSemitones.indices) {
                val note = baseNote + o * 12 + blackSemitones[k]
                val cx = (o * 7 + blackAfterWhite[k] + 1) * whiteW
                rect.set(cx - blackW / 2f, 0f, cx + blackW / 2f, blackH)
                val paint = when {
                    externalDown.contains(note) -> midiPaint
                    touchDown.contains(note) -> blackDownPaint
                    else -> blackPaint
                }
                canvas.drawRoundRect(rect, blackW * 0.16f, blackW * 0.16f, paint)
            }
        }
    }

    private fun noteAt(x: Float, y: Float): Int {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f) return -1
        val whiteW = w / whiteCount
        val blackW = whiteW * 0.62f
        if (y < h * 0.62f) {
            for (o in 0 until octaveCount) {
                for (k in blackSemitones.indices) {
                    val cx = (o * 7 + blackAfterWhite[k] + 1) * whiteW
                    if (abs(x - cx) <= blackW / 2f) {
                        return baseNote + o * 12 + blackSemitones[k]
                    }
                }
            }
        }
        val index = (x / whiteW).toInt().coerceIn(0, whiteCount - 1)
        return baseNote + (index / 7) * 12 + whiteSemitones[index % 7]
    }

    private fun velocityAt(y: Float): Int {
        val ratio = (y / height.toFloat()).coerceIn(0f, 1f)
        return (54 + ratio * 70).toInt().coerceIn(1, 127)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val index = event.actionIndex
                press(event.getPointerId(index), event.getX(index), event.getY(index))
            }
            MotionEvent.ACTION_MOVE -> {
                for (i in 0 until event.pointerCount) {
                    val id = event.getPointerId(i)
                    val note = noteAt(event.getX(i), event.getY(i))
                    if (pointerNotes[id] != note) {
                        release(id)
                        press(id, event.getX(i), event.getY(i))
                    }
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                release(event.getPointerId(event.actionIndex))
            }
            MotionEvent.ACTION_CANCEL -> {
                pointerNotes.keys.toList().forEach { release(it) }
            }
        }
        return true
    }

    private fun press(pointerId: Int, x: Float, y: Float) {
        val note = noteAt(x, y)
        if (note < 0) return
        pointerNotes[pointerId] = note
        touchDown.add(note)
        callback?.onKeyDown(note, velocityAt(y))
        postInvalidateOnAnimation()
    }

    private fun release(pointerId: Int) {
        val note = pointerNotes.remove(pointerId) ?: return
        if (!pointerNotes.values.contains(note)) {
            touchDown.remove(note)
            callback?.onKeyUp(note)
        }
        postInvalidateOnAnimation()
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }
}

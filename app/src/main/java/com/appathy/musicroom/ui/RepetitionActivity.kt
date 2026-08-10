package com.appathy.musicroom.ui

import android.os.Bundle
import android.view.WindowManager
import android.widget.Button
import android.widget.ProgressBar
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.appathy.musicroom.R
import com.appathy.musicroom.audio.SynthEngine
import com.appathy.musicroom.midi.EventType
import com.appathy.musicroom.midi.MidiHub
import com.appathy.musicroom.midi.MusicEvent
import kotlin.math.sqrt

class RepetitionActivity : AppCompatActivity(), MidiHub.Listener {

    private lateinit var textTargetNote: TextView
    private lateinit var textProgress: TextView
    private lateinit var textDots: TextView
    private lateinit var textRate: TextView
    private lateinit var textStability: TextView
    private lateinit var barStability: ProgressBar
    private lateinit var textResult: TextView
    private lateinit var btnStart: Button

    private var targetCount = 20
    private var targetNote = -1
    private var running = false
    private val hits = ArrayList<Long>()
    private val velocities = ArrayList<Int>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_repetition)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        textTargetNote = findViewById(R.id.textTargetNote)
        textProgress = findViewById(R.id.textProgress)
        textDots = findViewById(R.id.textDots)
        textRate = findViewById(R.id.textRate)
        textStability = findViewById(R.id.textStability)
        barStability = findViewById(R.id.barStability)
        textResult = findViewById(R.id.textResult)
        btnStart = findViewById(R.id.btnStart)

        val seek = findViewById<SeekBar>(R.id.seekTarget)
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                targetCount = progress + 10
                if (!running) textProgress.text = "0 / " + targetCount
            }

            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })

        btnStart.setOnClickListener { if (running) reset() else begin() }
        findViewById<Button>(R.id.btnTap).setOnClickListener {
            if (running) registerHit(60, 100)
        }

        textProgress.text = "0 / " + targetCount
        renderDots()
    }

    override fun onResume() {
        super.onResume()
        SynthEngine.start()
        MidiHub.addListener(this)
        MidiHub.autoConnect()
    }

    override fun onPause() {
        super.onPause()
        MidiHub.removeListener(this)
        SynthEngine.stop()
    }

    private fun begin() {
        running = true
        targetNote = -1
        hits.clear()
        velocities.clear()
        btnStart.text = "■ リセット"
        textTargetNote.text = "待機中"
        textResult.text = "同じ鍵盤を連打してください。"
        textProgress.text = "0 / " + targetCount
        barStability.progress = 0
        textRate.text = "現在 0.0 回/秒"
        textStability.text = "安定度 —"
        renderDots()
    }

    private fun reset() {
        running = false
        btnStart.text = "▶ スタート"
        textTargetNote.text = "—"
    }

    override fun onMusicEvent(event: MusicEvent) {
        if (event.type != EventType.NOTE_ON) return
        SynthEngine.noteOn(event.note, event.velocity)
        if (running) registerHit(event.note, event.velocity)
    }

    private fun registerHit(note: Int, velocity: Int) {
        if (targetNote < 0) {
            targetNote = note
            textTargetNote.text = MusicEvent.noteName(note)
        }
        if (note != targetNote) return

        hits.add(System.nanoTime())
        velocities.add(velocity)
        textProgress.text = hits.size.toString() + " / " + targetCount
        renderDots()
        updateLive()

        if (hits.size >= targetCount) finishChallenge()
    }

    private fun intervalsMs(): List<Double> {
        if (hits.size < 2) return emptyList()
        return (1 until hits.size).map { (hits[it] - hits[it - 1]) / 1_000_000.0 }
    }

    private fun updateLive() {
        val intervals = intervalsMs()
        if (intervals.isEmpty()) return
        val mean = intervals.average()
        val rate = if (mean > 0) 1000.0 / mean else 0.0
        textRate.text = "現在 " + String.format("%.1f", rate) + " 回/秒"
        val stability = stability(intervals)
        textStability.text = "安定度 " + stability + "%"
        barStability.progress = stability
    }

    private fun stability(intervals: List<Double>): Int {
        if (intervals.size < 2) return 0
        val mean = intervals.average()
        if (mean <= 0.0) return 0
        val variance = intervals.sumOf { (it - mean) * (it - mean) } / intervals.size
        val cv = sqrt(variance) / mean
        return ((1.0 - cv) * 100.0).toInt().coerceIn(0, 100)
    }

    private fun renderDots() {
        val sb = StringBuilder()
        for (i in 0 until targetCount) {
            sb.append(if (i < hits.size) "●" else "○")
        }
        textDots.text = sb.toString()
    }

    private fun finishChallenge() {
        running = false
        btnStart.text = "▶ スタート"
        val intervals = intervalsMs()
        if (intervals.isEmpty()) return
        val mean = intervals.average()
        val rate = 1000.0 / mean
        val fastest = 1000.0 / (intervals.minOrNull() ?: mean)
        val stability = stability(intervals)
        val velocityAverage = if (velocities.isEmpty()) 0 else velocities.average().toInt()
        val comment = when {
            stability >= 85 -> "間隔が非常に均一です。テンポを上げても崩れにくい状態です。"
            stability >= 70 -> "おおむね均一です。速度を落として、さらに間隔をそろえる練習が有効です。"
            else -> "速度よりも均一性が課題です。まず遅いテンポで、粒をそろえることを優先しましょう。"
        }
        textResult.text = "結果\n\n" +
            "平均速度   " + String.format("%.2f", rate) + " 回/秒\n" +
            "最速間隔   " + String.format("%.2f", fastest) + " 回/秒相当\n" +
            "平均間隔   " + String.format("%.1f", mean) + " ms\n" +
            "安定度     " + stability + "%\n" +
            "平均強さ   " + velocityAverage + "\n\n" +
            comment
        textResult.setTextColor(getColor(R.color.text_primary))
    }
}

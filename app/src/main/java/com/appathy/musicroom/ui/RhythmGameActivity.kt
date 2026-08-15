package com.appathy.musicroom.ui

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.WindowManager
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.Spinner
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.appathy.musicroom.R
import com.appathy.musicroom.audio.SynthEngine
import com.appathy.musicroom.audio.Wave
import com.appathy.musicroom.data.Kind
import com.appathy.musicroom.data.PracticeDb
import com.appathy.musicroom.game.ChartGenerator
import com.appathy.musicroom.game.Judge
import com.appathy.musicroom.game.Judgement
import com.appathy.musicroom.game.NoteItem
import com.appathy.musicroom.midi.EventSource
import com.appathy.musicroom.midi.EventType
import com.appathy.musicroom.midi.MidiHub
import com.appathy.musicroom.midi.MusicEvent
import kotlin.math.abs

class RhythmGameActivity : AppCompatActivity(), MidiHub.Listener, RhythmView.Callback {

    private val bpmChoices = arrayOf("80 BPM", "100 BPM", "120 BPM", "140 BPM")
    private val bpmValues = intArrayOf(80, 100, 120, 140)
    private val difficultyChoices = arrayOf("やさしい", "ふつう", "むずかしい")
    private val densityValues = doubleArrayOf(0.30, 0.50, 0.75)

    private lateinit var rhythmView: RhythmView
    private lateinit var textScore: TextView
    private lateinit var textCombo: TextView
    private lateinit var textJudge: TextView
    private lateinit var panel: View
    private lateinit var textPanelTitle: TextView
    private lateinit var textPanelBody: TextView
    private lateinit var btnStart: Button
    private lateinit var spinnerBpm: Spinner
    private lateinit var spinnerDifficulty: Spinner

    private val handler = Handler(Looper.getMainLooper())
    private var notes: List<NoteItem> = emptyList()
    private var startNanos = 0L
    private var running = false

    private var score = 0
    private var previousBest: Pair<Int, Double>? = null
    private var combo = 0
    private var maxCombo = 0
    private val counts = HashMap<Judgement, Int>()
    private val errors = ArrayList<Double>()

    private val ticker = object : Runnable {
        override fun run() {
            if (!running) return
            sweepMisses()
            if (currentTimeMs() > (notes.lastOrNull()?.timeMs ?: 0.0) + 1500.0) {
                showResult()
                return
            }
            handler.postDelayed(this, 60)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_rhythm)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        rhythmView = findViewById(R.id.rhythmView)
        textScore = findViewById(R.id.textScore)
        textCombo = findViewById(R.id.textCombo)
        textJudge = findViewById(R.id.textJudge)
        panel = findViewById(R.id.panel)
        textPanelTitle = findViewById(R.id.textPanelTitle)
        textPanelBody = findViewById(R.id.textPanelBody)
        btnStart = findViewById(R.id.btnStartGame)
        spinnerBpm = findViewById(R.id.spinnerBpm)
        spinnerDifficulty = findViewById(R.id.spinnerDifficulty)

        rhythmView.callback = this

        spinnerBpm.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, bpmChoices)
        spinnerBpm.setSelection(1)
        spinnerDifficulty.adapter =
            ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, difficultyChoices)
        spinnerDifficulty.setSelection(1)

        btnStart.setOnClickListener { startGame() }
    }

    override fun onResume() {
        super.onResume()
        SynthEngine.start()
        MidiHub.addListener(this)
        MidiHub.autoConnect()
    }

    override fun onPause() {
        super.onPause()
        running = false
        handler.removeCallbacks(ticker)
        panel.visibility = View.VISIBLE
        MidiHub.removeListener(this)
        SynthEngine.stop()
    }

    // ------------------------------------------------------------------ game

    private fun startGame() {
        val bpm = bpmValues[spinnerBpm.selectedItemPosition]
        val density = densityValues[spinnerDifficulty.selectedItemPosition]
        notes = ChartGenerator.generate(bpm, 16, density)
        score = 0
        combo = 0
        maxCombo = 0
        counts.clear()
        errors.clear()
        Judgement.values().forEach { counts[it] = 0 }
        rhythmView.notes = notes
        rhythmView.approachMs = 1600.0
        panel.visibility = View.GONE
        textJudge.text = ""
        updateHud()
        startNanos = System.nanoTime()
        running = true
        rhythmView.postInvalidateOnAnimation()
        handler.post(ticker)
    }

    override fun currentTimeMs(): Double = (System.nanoTime() - startNanos) / 1_000_000.0

    override fun isRunning(): Boolean = running

    override fun onLaneTouched(lane: Int) = hit(lane, 100)

    override fun onMusicEvent(event: MusicEvent) {
        if (event.type != EventType.NOTE_ON) return
        if (event.source != EventSource.MIDI) return
        val lane = ChartGenerator.laneOfNote(event.note)
        if (lane < 0) return
        hit(lane, event.velocity)
    }

    private fun hit(lane: Int, velocity: Int) {
        val token = SynthEngine.noteOn(ChartGenerator.noteOfLane(lane), velocity, Wave.PIANO)
        handler.postDelayed({ SynthEngine.releaseToken(token) }, 180)
        rhythmView.flash(lane)
        if (!running) return

        val now = currentTimeMs()
        var target: NoteItem? = null
        var best = Double.MAX_VALUE
        for (note in notes) {
            if (note.judged || note.lane != lane) continue
            val diff = abs(note.timeMs - now)
            if (diff < best) {
                best = diff
                target = note
            }
        }
        val note = target ?: return
        if (best > Judge.windowMs) return

        val error = now - note.timeMs
        val judgement = Judge.of(error)
        note.judged = true
        note.judgement = judgement
        note.errorMs = error
        errors.add(error)
        apply(judgement)
    }

    private fun sweepMisses() {
        val now = currentTimeMs()
        var changed = false
        for (note in notes) {
            if (note.judged) continue
            if (now - note.timeMs > Judge.windowMs) {
                note.judged = true
                note.judgement = Judgement.MISS
                apply(Judgement.MISS)
                changed = true
            }
        }
        if (changed) updateHud()
    }

    private fun apply(judgement: Judgement) {
        counts[judgement] = (counts[judgement] ?: 0) + 1
        if (judgement == Judgement.MISS) {
            combo = 0
        } else {
            combo++
            if (combo > maxCombo) maxCombo = combo
            score += judgement.score * (1 + combo / 25)
        }
        textJudge.text = judgement.label
        updateHud()
    }

    private fun updateHud() {
        textScore.text = "SCORE " + score
        textCombo.text = "COMBO " + combo
    }

    private fun showResult() {
        running = false
        handler.removeCallbacks(ticker)
        val total = notes.size
        val perfect = counts[Judgement.PERFECT] ?: 0
        val great = counts[Judgement.GREAT] ?: 0
        val good = counts[Judgement.GOOD] ?: 0
        val miss = counts[Judgement.MISS] ?: 0
        val accuracy = if (total == 0) 0.0 else (perfect * 1.0 + great * 0.7 + good * 0.4) / total
        val meanError = if (errors.isEmpty()) 0.0 else errors.average()

        PracticeDb.get(this).insertSession(
            kind = Kind.RHYTHM,
            label = difficultyChoices[spinnerDifficulty.selectedItemPosition],
            bpm = bpmValues[spinnerBpm.selectedItemPosition],
            accuracy = accuracy,
            meanErrorMs = meanError,
            score = score,
            itemCount = total
        )

        val best = previousBest
        val bestLine = if (best == null) {
            "初プレイです。次から前回との比較を出します。"
        } else {
            val diff = score - best.first
            "自己ベスト " + best.first + " 点 (正確性 " + (best.second * 100).toInt() + "%)\n" +
                when {
                    diff > 0 -> "自己ベスト更新！ +" + diff + " 点"
                    diff == 0 -> "自己ベストと同点です。"
                    else -> "自己ベストまで あと " + (-diff) + " 点"
                }
        }

        textPanelTitle.text = "RESULT  " + Judge.rank(accuracy)
        textPanelBody.text = score.toString() + " 点\n\n" +
            "PERFECT " + perfect + "\n" +
            "GREAT   " + great + "\n" +
            "GOOD    " + good + "\n" +
            "MISS    " + miss + "\n\n" +
            "最大COMBO " + maxCombo + "\n" +
            "正確性 " + (accuracy * 100).toInt() + "%\n" +
            "平均ズレ " + String.format("%+.0f", meanError) + " ms\n\n" +
            bestLine + "\n\n" +
            Judge.tendency(errors)
        btnStart.text = "▶ もう一度"
        panel.visibility = View.VISIBLE
    }
}

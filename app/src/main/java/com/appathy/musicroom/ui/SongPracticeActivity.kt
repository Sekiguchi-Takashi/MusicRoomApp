package com.appathy.musicroom.ui

import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.WindowManager
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.appathy.musicroom.R
import com.appathy.musicroom.audio.SynthEngine
import com.appathy.musicroom.audio.Wave
import com.appathy.musicroom.data.Kind
import com.appathy.musicroom.data.MeasureRow
import com.appathy.musicroom.data.PracticeDb
import com.appathy.musicroom.game.Judge
import com.appathy.musicroom.game.Judgement
import com.appathy.musicroom.midi.EventSource
import com.appathy.musicroom.midi.EventType
import com.appathy.musicroom.midi.MidiHub
import com.appathy.musicroom.midi.MusicEvent
import com.appathy.musicroom.song.ChartBuilder
import com.appathy.musicroom.song.MeasureStat
import com.appathy.musicroom.song.PlayNote
import com.appathy.musicroom.song.SongChart
import com.appathy.musicroom.song.SongEvaluator
import com.appathy.musicroom.song.SongLibrary
import kotlin.math.abs
import kotlin.math.roundToInt

class SongPracticeActivity : AppCompatActivity(), MidiHub.Listener, SongRollView.Callback {

    private val tempoLabels = arrayOf("ゆっくり (60%)", "すこし遅め (80%)", "原速 (100%)", "速め (115%)")
    private val tempoFactors = doubleArrayOf(0.6, 0.8, 1.0, 1.15)

    private lateinit var rollView: SongRollView
    private lateinit var textTitle: TextView
    private lateinit var textJudge: TextView
    private lateinit var textProgress: TextView
    private lateinit var panel: View
    private lateinit var setupArea: View
    private lateinit var resultArea: LinearLayout
    private lateinit var textPanelTitle: TextView
    private lateinit var textPanelBody: TextView
    private lateinit var spinnerSong: Spinner
    private lateinit var spinnerTempo: Spinner

    private val handler = Handler(Looper.getMainLooper())
    private var catalog: List<com.appathy.musicroom.song.Song> = emptyList()
    private var chart: SongChart? = null
    private var startNanos = 0L
    private var running = false
    private var demoMode = false
    private var practiceMeasures: IntRange? = null

    private val ticker = object : Runnable {
        override fun run() {
            if (!running) return
            val c = chart
            if (c == null) {
                running = false
                return
            }
            if (!demoMode) sweepMisses(c)
            updateProgress(c)
            if (currentTimeMs() > c.endMs + 800.0) {
                if (demoMode) endDemo() else showResult()
                return
            }
            handler.postDelayed(this, 60)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_song)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        rollView = findViewById(R.id.rollView)
        textTitle = findViewById(R.id.textTitle)
        textJudge = findViewById(R.id.textJudge)
        textProgress = findViewById(R.id.textProgress)
        panel = findViewById(R.id.panel)
        setupArea = findViewById(R.id.setupArea)
        resultArea = findViewById(R.id.resultArea)
        textPanelTitle = findViewById(R.id.textPanelTitle)
        textPanelBody = findViewById(R.id.textPanelBody)
        spinnerSong = findViewById(R.id.spinnerSong)
        spinnerTempo = findViewById(R.id.spinnerTempo)

        rollView.callback = this

        reloadCatalog()
        spinnerTempo.adapter =
            ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, tempoLabels)
        spinnerTempo.setSelection(1)

        findViewById<Button>(R.id.btnStartSong).setOnClickListener { start(false, null) }
        findViewById<Button>(R.id.btnDemo).setOnClickListener { start(true, null) }
    }

    private fun reloadCatalog() {
        val previous = catalog.getOrNull(
            if (::spinnerSong.isInitialized) spinnerSong.selectedItemPosition else 0
        )?.title
        catalog = SongLibrary.all(this)
        spinnerSong.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            SongLibrary.titlesOf(catalog)
        )
        val index = catalog.indexOfFirst { it.title == previous }
        if (index >= 0) spinnerSong.setSelection(index)
    }

    override fun onResume() {
        super.onResume()
        if (!running) reloadCatalog()
        SynthEngine.start()
        MidiHub.addListener(this)
        MidiHub.autoConnect()
    }

    override fun onPause() {
        super.onPause()
        running = false
        handler.removeCallbacks(ticker)
        SynthEngine.allNotesOff()
        panel.visibility = View.VISIBLE
        MidiHub.removeListener(this)
        SynthEngine.stop()
    }

    // ------------------------------------------------------------------ play

    private fun selectedSong(): com.appathy.musicroom.song.Song =
        catalog.getOrElse(spinnerSong.selectedItemPosition) { SongLibrary.songs.first() }

    private fun currentBpm(): Int {
        val song = selectedSong()
        val factor = tempoFactors[spinnerTempo.selectedItemPosition]
        return (song.defaultBpm * factor).roundToInt().coerceIn(30, 240)
    }

    private fun start(demo: Boolean, measures: IntRange?) {
        val song = selectedSong()
        val repeats = if (measures != null) 4 else 1
        val built = ChartBuilder.build(song, currentBpm(), measures, repeats)
        chart = built
        practiceMeasures = measures
        demoMode = demo
        rollView.chart = built
        rollView.approachMs = 2200.0
        textTitle.text = song.title +
            (if (measures != null) "  " + (measures.first + 1) + "小節 ×4" else "") +
            (if (demo) "  (お手本)" else "")
        textJudge.text = ""
        panel.visibility = View.GONE
        startNanos = System.nanoTime()
        running = true
        rollView.postInvalidateOnAnimation()
        handler.post(ticker)
        scheduleBacking(built)
        if (demo) scheduleDemo(built)
    }

    /** 自作曲の伴奏トラック。判定はせず、後ろで鳴らすだけ。 */
    private fun scheduleBacking(c: SongChart) {
        c.backing.forEach { note ->
            handler.postDelayed({
                if (!running) return@postDelayed
                SynthEngine.noteOn(note.pitch, 58, Wave.PIANO)
                handler.postDelayed(
                    { SynthEngine.noteOff(note.pitch) },
                    note.durationMs.toLong().coerceAtLeast(120L)
                )
            }, note.timeMs.toLong())
        }
    }

    private fun scheduleDemo(c: SongChart) {
        c.notes.forEach { note ->
            handler.postDelayed({
                if (!running) return@postDelayed
                SynthEngine.noteOn(note.pitch, 100, Wave.PIANO)
                rollView.flash(note.pitch)
                handler.postDelayed(
                    { SynthEngine.noteOff(note.pitch) },
                    note.durationMs.toLong().coerceAtLeast(120L)
                )
            }, note.timeMs.toLong())
        }
    }

    private fun endDemo() {
        running = false
        handler.removeCallbacks(ticker)
        SynthEngine.allNotesOff()
        textPanelTitle.text = "🎼 楽曲練習"
        textPanelBody.text = "お手本を再生しました。今度は自分で弾いてみましょう。"
        setupArea.visibility = View.VISIBLE
        resultArea.removeAllViews()
        panel.visibility = View.VISIBLE
    }

    override fun currentTimeMs(): Double = (System.nanoTime() - startNanos) / 1_000_000.0

    override fun isRunning(): Boolean = running

    override fun onPitchTouched(pitch: Int) = hit(pitch, 100)

    override fun onMusicEvent(event: MusicEvent) {
        if (event.source != EventSource.MIDI) return
        when (event.type) {
            EventType.NOTE_ON -> hit(event.note, event.velocity)
            EventType.NOTE_OFF -> SynthEngine.noteOff(event.note)
            else -> {}
        }
    }

    private fun hit(pitch: Int, velocity: Int) {
        SynthEngine.noteOn(pitch, velocity, Wave.PIANO)
        handler.postDelayed({ SynthEngine.noteOff(pitch) }, 260)
        rollView.flash(pitch)
        val c = chart ?: return
        if (!running || demoMode) return

        val now = currentTimeMs()
        var target: PlayNote? = null
        var best = Double.MAX_VALUE
        for (note in c.notes) {
            if (note.judged || note.pitch != pitch) continue
            val diff = abs(note.timeMs - now)
            if (diff < best) {
                best = diff
                target = note
            }
        }
        val note = target
        if (note == null || best > Judge.windowMs * 1.6) {
            val measure = c.measureAt(now)
            c.wrongNotes[measure] = (c.wrongNotes[measure] ?: 0) + 1
            textJudge.text = "WRONG"
            return
        }
        val error = now - note.timeMs
        val judgement = Judge.of(error)
        note.judged = true
        note.judgement = judgement
        note.errorMs = error
        textJudge.text = judgement.label
    }

    private fun sweepMisses(c: SongChart) {
        val now = currentTimeMs()
        for (note in c.notes) {
            if (note.judged) continue
            if (now - note.timeMs > Judge.windowMs * 1.6) {
                note.judged = true
                note.judgement = Judgement.MISS
            }
        }
    }

    private fun updateProgress(c: SongChart) {
        val done = c.notes.count { it.judged }
        textProgress.text = done.toString() + " / " + c.notes.size
    }

    // ---------------------------------------------------------------- result

    private fun showResult() {
        running = false
        handler.removeCallbacks(ticker)
        SynthEngine.allNotesOff()
        val c = chart ?: return

        val stats = SongEvaluator.evaluate(c)
        val overall = if (stats.isEmpty()) 0.0 else stats.map { it.accuracy }.average()
        val weak = SongEvaluator.weakMeasures(stats)

        textPanelTitle.text = "RESULT  " + Judge.rank(overall)
        textPanelBody.text = "全体の正確度 " + (overall * 100).toInt() + "%\n" +
            (if (weak.isEmpty()) {
                "苦手な小節は見つかりませんでした。テンポを上げてみましょう。"
            } else {
                "苦手な小節: " + weak.joinToString("、") { (it.measure + 1).toString() + "小節目" } +
                    "\n" + SongEvaluator.comment(weak.first()) +
                    "\n下の行をタップすると、その小節だけを4回くり返して練習できます。"
            })

        saveSession(c, stats, overall)

        setupArea.visibility = View.VISIBLE
        resultArea.removeAllViews()
        resultArea.addView(header())
        stats.forEach { stat -> resultArea.addView(row(stat, weak.contains(stat))) }
        panel.visibility = View.VISIBLE
    }

    private fun saveSession(c: SongChart, stats: List<MeasureStat>, overall: Double) {
        val errors = c.notes.filter { it.judgement != null && it.judgement != Judgement.MISS }
            .map { it.errorMs }
        val db = PracticeDb.get(this)
        val sessionId = db.insertSession(
            kind = Kind.SONG,
            label = c.song.title,
            bpm = c.bpm,
            accuracy = overall,
            meanErrorMs = if (errors.isEmpty()) 0.0 else errors.average(),
            itemCount = c.notes.size
        )
        if (sessionId > 0) {
            db.insertMeasures(sessionId, stats.map { stat ->
                MeasureRow(stat.measure, stat.accuracy, stat.meanErrorMs, stat.miss, stat.wrong)
            })
        }
    }

    private fun header(): View = TextView(this).apply {
        text = "小節   正確度   平均ズレ   ミス/誤音"
        setTextColor(getColor(R.color.text_secondary))
        textSize = 12f
        typeface = android.graphics.Typeface.MONOSPACE
        setPadding(dp(12), dp(8), dp(12), dp(6))
    }

    private fun row(stat: MeasureStat, weak: Boolean): View {
        val bar = "█".repeat((stat.accuracy * 10).toInt().coerceIn(0, 10))
            .padEnd(10, '·')
        val view = TextView(this).apply {
            text = String.format(
                "%3d   %s %3d%%   %+5.0fms   %d/%d",
                stat.measure + 1,
                bar,
                (stat.accuracy * 100).toInt(),
                stat.meanErrorMs,
                stat.miss,
                stat.wrong
            )
            typeface = android.graphics.Typeface.MONOSPACE
            textSize = 12f
            setTextColor(if (weak) Color.parseColor("#EF6461") else getColor(R.color.text_primary))
            setBackgroundResource(R.drawable.bg_menu)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            setOnClickListener { start(false, stat.measure..stat.measure) }
        }
        val lp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )
        lp.bottomMargin = dp(6)
        view.layoutParams = lp
        return view
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}

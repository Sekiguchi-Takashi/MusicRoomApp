package com.appathy.musicroom.ui

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.WindowManager
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import com.appathy.musicroom.R
import com.appathy.musicroom.audio.MicEngine
import com.appathy.musicroom.audio.SynthEngine
import com.appathy.musicroom.audio.Wave
import com.appathy.musicroom.data.Kind
import com.appathy.musicroom.data.MeasureRow
import com.appathy.musicroom.data.PracticeDb
import com.appathy.musicroom.game.Judgement
import com.appathy.musicroom.midi.MusicEvent
import com.appathy.musicroom.song.ChartBuilder
import com.appathy.musicroom.song.PlayNote
import com.appathy.musicroom.song.SongChart
import com.appathy.musicroom.song.SongLibrary
import kotlin.math.abs
import kotlin.math.roundToInt

class SingActivity : AppCompatActivity(), MicEngine.Listener, PitchTrackView.Callback {

    companion object {
        /** 音の前後を評価から外す割合。移り変わりの混入を避けるため。 */
        private const val ONSET_MARGIN = 0.18
    }

    private val keyLabels = arrayOf("1オクターブ下", "5度下", "そのまま", "5度上")
    private val keyShifts = intArrayOf(-12, -7, 0, 7)
    private val tempoLabels = arrayOf("ゆっくり (70%)", "すこし遅め (85%)", "原速 (100%)")
    private val tempoFactors = doubleArrayOf(0.7, 0.85, 1.0)

    private lateinit var trackView: PitchTrackView
    private lateinit var textTitle: TextView
    private lateinit var textNow: TextView
    private lateinit var textProgress: TextView
    private lateinit var panel: View
    private lateinit var setupArea: View
    private lateinit var resultArea: LinearLayout
    private lateinit var textPanelTitle: TextView
    private lateinit var textPanelBody: TextView
    private lateinit var spinnerSong: Spinner
    private lateinit var spinnerKey: Spinner
    private lateinit var spinnerTempo: Spinner
    private lateinit var checkGuide: CheckBox

    private val handler = Handler(Looper.getMainLooper())
    private var catalog: List<com.appathy.musicroom.song.Song> = emptyList()
    private var chart: SongChart? = null
    private var startNanos = 0L
    private var running = false

    /** ノートごとに拾った歌唱ピッチ (MIDI実数)。 */
    private val samples = HashMap<PlayNote, MutableList<Double>>()
    private val voicedFrames = HashMap<PlayNote, Int>()
    private val totalFrames = HashMap<PlayNote, Int>()

    private val ticker = object : Runnable {
        override fun run() {
            if (!running) return
            val c = chart ?: return
            textProgress.text = c.notes.count { it.timeMs < currentTimeMs() }.toString() +
                " / " + c.notes.size
            if (currentTimeMs() > c.endMs + 700.0) {
                showResult()
                return
            }
            handler.postDelayed(this, 80)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_sing)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        trackView = findViewById(R.id.trackView)
        textTitle = findViewById(R.id.textTitle)
        textNow = findViewById(R.id.textNow)
        textProgress = findViewById(R.id.textProgress)
        panel = findViewById(R.id.panel)
        setupArea = findViewById(R.id.setupArea)
        resultArea = findViewById(R.id.resultArea)
        textPanelTitle = findViewById(R.id.textPanelTitle)
        textPanelBody = findViewById(R.id.textPanelBody)
        spinnerSong = findViewById(R.id.spinnerSong)
        spinnerKey = findViewById(R.id.spinnerKey)
        spinnerTempo = findViewById(R.id.spinnerTempo)
        checkGuide = findViewById(R.id.checkGuide)

        trackView.callback = this

        reloadCatalog()
        spinnerKey.adapter =
            ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, keyLabels)
        spinnerKey.setSelection(2)
        spinnerTempo.adapter =
            ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, tempoLabels)
        spinnerTempo.setSelection(1)

        findViewById<Button>(R.id.btnStartSing).setOnClickListener { start() }
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

    private fun selectedSong(): com.appathy.musicroom.song.Song =
        catalog.getOrElse(spinnerSong.selectedItemPosition) { SongLibrary.songs.first() }

    override fun onResume() {
        super.onResume()
        if (!running) reloadCatalog()
        MicEngine.addListener(this)
        ensureMic()
    }

    override fun onPause() {
        super.onPause()
        running = false
        handler.removeCallbacks(ticker)
        MicEngine.removeListener(this)
        MicEngine.stop()
        SynthEngine.allNotesOff()
        SynthEngine.stop()
        panel.visibility = View.VISIBLE
    }

    private fun ensureMic() {
        if (!MicEngine.hasPermission(this)) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), 1)
            textPanelBody.text = "マイクの使用を許可してください。歌った音程を測るために必要です。"
            return
        }
        if (!MicEngine.start(this)) {
            textPanelBody.text = "マイクを開始できませんでした。他のアプリが使用中かもしれません。"
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            ensureMic()
        } else {
            textPanelBody.text = "マイクが許可されていないため、うた練習は使えません。"
        }
    }

    // ------------------------------------------------------------------ play

    private fun start() {
        if (!MicEngine.isRunning && !MicEngine.start(this)) {
            ensureMic()
            return
        }
        val base = selectedSong()
        val shift = keyShifts[spinnerKey.selectedItemPosition]
        val song = if (shift == 0) base else base.copy(
            notes = base.notes.map { it.copy(pitch = it.pitch + shift) }
        )
        val bpm = (base.defaultBpm * tempoFactors[spinnerTempo.selectedItemPosition])
            .roundToInt().coerceIn(30, 200)

        val built = ChartBuilder.build(song, bpm)
        chart = built
        samples.clear()
        voicedFrames.clear()
        totalFrames.clear()

        trackView.chart = built
        trackView.reset()
        textTitle.text = base.title + "  " + keyLabels[spinnerKey.selectedItemPosition]
        textNow.text = ""
        panel.visibility = View.GONE

        startNanos = System.nanoTime()
        running = true
        trackView.postInvalidateOnAnimation()
        handler.post(ticker)

        SynthEngine.start()
        scheduleCountIn(bpm)
        scheduleBacking(built)
        if (checkGuide.isChecked) scheduleGuide(built)
    }

    private fun scheduleCountIn(bpm: Int) {
        val msPerBeat = 60_000.0 / bpm
        for (i in 0 until 4) {
            handler.postDelayed({
                if (running) SynthEngine.blip(if (i == 0) 1760.0 else 1175.0, 0.7, Wave.SINE, 0.04)
            }, (i * msPerBeat).toLong())
        }
    }

    private fun scheduleBacking(c: SongChart) {
        c.backing.forEach { note ->
            handler.postDelayed({
                if (!running) return@postDelayed
                val token = SynthEngine.noteOn(note.pitch, 52, Wave.PIANO)
                handler.postDelayed(
                    { SynthEngine.releaseToken(token) },
                    note.durationMs.toLong().coerceAtLeast(120L)
                )
            }, note.timeMs.toLong())
        }
    }

    private fun scheduleGuide(c: SongChart) {
        c.notes.forEach { note ->
            handler.postDelayed({
                if (!running) return@postDelayed
                val token = SynthEngine.noteOn(note.pitch, 78, Wave.SINE)
                handler.postDelayed(
                    { SynthEngine.releaseToken(token) },
                    note.durationMs.toLong().coerceAtLeast(120L)
                )
            }, note.timeMs.toLong())
        }
    }

    override fun currentTimeMs(): Double = (System.nanoTime() - startNanos) / 1_000_000.0

    override fun isRunning(): Boolean = running

    // ------------------------------------------------------------- mic input

    override fun onPitch(
        hz: Double,
        midi: Double,
        confidence: Double,
        level: Double,
        timestampNanos: Long
    ) {
        if (!running) {
            if (hz > 0) textNow.text = MusicEvent.noteName(midi.roundToInt())
            return
        }
        val c = chart ?: return
        // 判定にはマイク側の録音時刻を使う。UI へ届くまでの遅れを含めないため。
        val now = (timestampNanos - startNanos) / 1_000_000.0

        // 音の入りと終わりは、前後の音への移り変わりやしゃくり上げが混ざる。
        // 中央の区間だけを評価に使い、表示用の軌跡は全区間そのまま描く。
        val active = c.notes.firstOrNull { note ->
            val margin = note.durationMs * ONSET_MARGIN
            now >= note.timeMs + margin && now <= note.timeMs + note.durationMs - margin
        }
        if (active != null) {
            totalFrames[active] = (totalFrames[active] ?: 0) + 1
            if (hz > 0) {
                voicedFrames[active] = (voicedFrames[active] ?: 0) + 1
                samples.getOrPut(active) { ArrayList() }.add(midi)
            }
        }
        if (hz > 0) {
            trackView.addSample(now, midi)
            textNow.text = MusicEvent.noteName(midi.roundToInt())
        }
    }

    // ---------------------------------------------------------------- result

    private fun showResult() {
        running = false
        handler.removeCallbacks(ticker)
        SynthEngine.allNotesOff()
        val c = chart ?: return

        c.notes.forEach { note -> judge(note) }

        val stats = com.appathy.musicroom.song.SongEvaluator.evaluate(c)
        val overall = if (stats.isEmpty()) 0.0 else stats.map { it.accuracy }.average()
        val centsErrors = c.notes.mapNotNull { note ->
            val list = samples[note]
            if (list.isNullOrEmpty()) null else (median(list) - note.pitch) * 100.0
        }
        val meanCents = if (centsErrors.isEmpty()) 0.0 else centsErrors.average()

        val db = PracticeDb.get(this)
        val sessionId = db.insertSession(
            kind = Kind.SING,
            label = "うた・" + selectedSong().title,
            bpm = c.bpm,
            accuracy = overall,
            meanErrorMs = meanCents,
            itemCount = c.notes.size
        )
        if (sessionId > 0) {
            db.insertMeasures(sessionId, stats.map {
                MeasureRow(it.measure, it.accuracy, it.meanErrorMs, it.miss, it.wrong)
            })
        }

        textPanelTitle.text = "RESULT  " + com.appathy.musicroom.game.Judge.rank(overall)
        textPanelBody.text = "音程の正確度 " + (overall * 100).toInt() + "%\n" +
            "平均のズレ " + (if (meanCents >= 0) "+" else "") + meanCents.roundToInt() + " セント\n" +
            comment(meanCents, overall) +
            "\n\n下の行は小節ごとの結果です。"

        setupArea.visibility = View.VISIBLE
        resultArea.removeAllViews()
        stats.forEach { stat ->
            val weak = stat.accuracy < 0.7
            resultArea.addView(
                row(
                    (stat.measure + 1).toString() + "小節目   正確度 " +
                        (stat.accuracy * 100).toInt() + "%   平均 " +
                        stat.meanErrorMs.roundToInt() + "セント   外れ " + stat.miss,
                    weak
                )
            )
        }
        panel.visibility = View.VISIBLE
    }

    private fun judge(note: PlayNote) {
        val total = totalFrames[note] ?: 0
        val voiced = voicedFrames[note] ?: 0
        val list = samples[note]
        note.judged = true
        if (total == 0 || list.isNullOrEmpty() || voiced.toDouble() / total < 0.35) {
            note.judgement = Judgement.MISS
            note.errorMs = 0.0
            return
        }
        val cents = (median(list) - note.pitch) * 100.0
        note.errorMs = cents
        note.judgement = when {
            abs(cents) <= 30 -> Judgement.PERFECT
            abs(cents) <= 60 -> Judgement.GREAT
            abs(cents) <= 110 -> Judgement.GOOD
            else -> Judgement.MISS
        }
    }

    private fun median(values: List<Double>): Double {
        val sorted = values.sorted()
        val size = sorted.size
        return if (size % 2 == 1) sorted[size / 2]
        else (sorted[size / 2 - 1] + sorted[size / 2]) / 2.0
    }

    private fun comment(meanCents: Double, accuracy: Double): String = when {
        accuracy < 0.4 -> "まだ音が取れていません。キーを下げて、ガイド音を鳴らしながら合わせるところから始めましょう。"
        meanCents < -35 -> "全体にぶら下がっています。息を支えて、目標より少し高めを狙う意識が有効です。"
        meanCents > 35 -> "全体に上ずっています。力みを抜いて、音の頭を軽く入れてみてください。"
        accuracy >= 0.85 -> "よく取れています。キーを上げるか、テンポを原速にしてみましょう。"
        else -> "おおむね取れています。外れた小節だけを繰り返すと安定します。"
    }

    private fun row(text: String, weak: Boolean): View {
        val view = TextView(this).apply {
            this.text = text
            typeface = android.graphics.Typeface.MONOSPACE
            textSize = 12f
            setTextColor(if (weak) Color.parseColor("#EF6461") else getColor(R.color.text_primary))
            setBackgroundResource(R.drawable.bg_card)
            setPadding(dp(12), dp(10), dp(12), dp(10))
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

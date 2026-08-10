package com.appathy.musicroom.ui

import android.os.Bundle
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.appathy.musicroom.R
import com.appathy.musicroom.audio.OneShotPlayer
import com.appathy.musicroom.audio.SePresets
import com.appathy.musicroom.audio.SeRenderer
import com.appathy.musicroom.audio.SeSpec
import com.appathy.musicroom.audio.Wave
import com.appathy.musicroom.audio.WavExporter
import com.appathy.musicroom.midi.EventType
import com.appathy.musicroom.midi.MidiHub
import com.appathy.musicroom.midi.MusicEvent
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.ln
import kotlin.math.pow

class SoundLabActivity : AppCompatActivity(), MidiHub.Listener {

    private var spec: SeSpec = SePresets.all[0].copySpec()
    private var suppress = false
    private var presetInitialized = false

    private lateinit var spinnerWave: Spinner
    private lateinit var labelStart: TextView
    private lateinit var labelEnd: TextView
    private lateinit var labelDuration: TextView
    private lateinit var labelDecay: TextView
    private lateinit var labelSteps: TextView
    private lateinit var labelNoise: TextView
    private lateinit var textStatus: TextView
    private lateinit var textPadHint: TextView

    private lateinit var seekStart: SeekBar
    private lateinit var seekEnd: SeekBar
    private lateinit var seekDuration: SeekBar
    private lateinit var seekDecay: SeekBar
    private lateinit var seekSteps: SeekBar
    private lateinit var seekNoise: SeekBar

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_soundlab)

        labelStart = findViewById(R.id.labelStart)
        labelEnd = findViewById(R.id.labelEnd)
        labelDuration = findViewById(R.id.labelDuration)
        labelDecay = findViewById(R.id.labelDecay)
        labelSteps = findViewById(R.id.labelSteps)
        labelNoise = findViewById(R.id.labelNoise)
        textStatus = findViewById(R.id.textStatus)
        textPadHint = findViewById(R.id.textPadHint)

        seekStart = findViewById(R.id.seekStart)
        seekEnd = findViewById(R.id.seekEnd)
        seekDuration = findViewById(R.id.seekDuration)
        seekDecay = findViewById(R.id.seekDecay)
        seekSteps = findViewById(R.id.seekSteps)
        seekNoise = findViewById(R.id.seekNoise)

        val presetSpinner = findViewById<Spinner>(R.id.spinnerPreset)
        presetSpinner.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item, SePresets.names
        )
        presetSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: android.view.View?, position: Int, id: Long) {
                spec = SePresets.all[position].copySpec()
                pushToControls()
                if (presetInitialized) preview() else presetInitialized = true
            }

            override fun onNothingSelected(p: AdapterView<*>?) {}
        }

        spinnerWave = findViewById(R.id.spinnerWave)
        spinnerWave.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item, Wave.labels
        )
        spinnerWave.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: android.view.View?, position: Int, id: Long) {
                if (suppress) return
                spec.wave = Wave.values()[position]
                spec.duty = if (spec.wave == Wave.PULSE25) 0.25 else 0.5
            }

            override fun onNothingSelected(p: AdapterView<*>?) {}
        }

        attach(seekStart) { spec.startFreq = freqOf(it); labelStart.text = "開始周波数  " + freqOf(it).toInt() + " Hz" }
        attach(seekEnd) { spec.endFreq = freqOf(it); labelEnd.text = "終了周波数  " + freqOf(it).toInt() + " Hz" }
        attach(seekDuration) { spec.durationMs = durationOf(it); labelDuration.text = "長さ  " + durationOf(it) + " ms" }
        attach(seekDecay) { spec.decayTau = decayOf(it); labelDecay.text = "減衰  " + String.format("%.2f", decayOf(it)) + " 秒" }
        attach(seekSteps) { spec.steps = it + 1; labelSteps.text = "ピッチ階段  " + (it + 1) + " 段" }
        attach(seekNoise) { spec.noiseMix = it / 100.0; labelNoise.text = "ノイズ量  " + it + "%" }

        findViewById<Button>(R.id.btnPreview).setOnClickListener { preview() }
        findViewById<Button>(R.id.btnSave).setOnClickListener { save() }

        pushToControls()
    }

    private fun attach(seek: SeekBar, apply: (Int) -> Unit) {
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                apply(progress)
            }

            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {
                preview()
            }
        })
    }

    // 40Hz .. 4000Hz の対数マッピング
    private fun freqOf(progress: Int): Double = 40.0 * 100.0.pow(progress / 100.0)

    private fun progressOfFreq(freq: Double): Int =
        (ln(freq.coerceIn(40.0, 4000.0) / 40.0) / ln(100.0) * 100.0).toInt().coerceIn(0, 100)

    private fun durationOf(progress: Int): Int = 50 + progress * 15

    private fun progressOfDuration(ms: Int): Int = ((ms - 50) / 15).coerceIn(0, 100)

    private fun decayOf(progress: Int): Double = 0.02 * 75.0.pow(progress / 100.0)

    private fun progressOfDecay(tau: Double): Int =
        (ln(tau.coerceIn(0.02, 1.5) / 0.02) / ln(75.0) * 100.0).toInt().coerceIn(0, 100)

    private fun pushToControls() {
        suppress = true
        spinnerWave.setSelection(Wave.values().indexOf(spec.wave))
        seekStart.progress = progressOfFreq(spec.startFreq)
        seekEnd.progress = progressOfFreq(spec.endFreq)
        seekDuration.progress = progressOfDuration(spec.durationMs)
        seekDecay.progress = progressOfDecay(spec.decayTau)
        seekSteps.progress = (spec.steps - 1).coerceIn(0, 7)
        seekNoise.progress = (spec.noiseMix * 100).toInt().coerceIn(0, 100)
        labelStart.text = "開始周波数  " + spec.startFreq.toInt() + " Hz"
        labelEnd.text = "終了周波数  " + spec.endFreq.toInt() + " Hz"
        labelDuration.text = "長さ  " + spec.durationMs + " ms"
        labelDecay.text = "減衰  " + String.format("%.2f", spec.decayTau) + " 秒"
        labelSteps.text = "ピッチ階段  " + spec.steps + " 段"
        labelNoise.text = "ノイズ量  " + (spec.noiseMix * 100).toInt() + "%"
        suppress = false
    }

    private fun preview() {
        OneShotPlayer.play(SeRenderer.render(spec))
    }

    private fun save() {
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val fileName = "se_" + spec.wave.name.lowercase(Locale.US) + "_" + stamp + ".wav"
        val pcm = SeRenderer.render(spec)
        val path = WavExporter.saveToDownloads(this, fileName, pcm)
        textStatus.text = if (path != null) {
            "保存しました\n" + path
        } else {
            "保存に失敗しました。ストレージの権限か空き容量を確認してください。"
        }
    }

    override fun onResume() {
        super.onResume()
        MidiHub.addListener(this)
        MidiHub.autoConnect()
        updatePadHint()
    }

    override fun onPause() {
        super.onPause()
        MidiHub.removeListener(this)
        OneShotPlayer.release()
    }

    override fun onConnectionChanged() = updatePadHint()

    private fun updatePadHint() {
        textPadHint.text = if (MidiHub.isConnected) {
            "MIDI ● " + MidiHub.connectedName + " — パッドと鍵盤で鳴らせます"
        } else {
            "MIDI未接続 — 画面から試聴できます"
        }
    }

    override fun onMusicEvent(event: MusicEvent) {
        if (event.type != EventType.NOTE_ON) return
        if (event.note in 36..43) {
            OneShotPlayer.play(SeRenderer.render(SePresets.all[event.note - 36]))
        } else {
            OneShotPlayer.play(SeRenderer.render(SeRenderer.transposed(spec, event.note)))
        }
    }
}

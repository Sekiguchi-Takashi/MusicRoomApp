package com.appathy.musicroom.ui

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.WindowManager
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.appathy.musicroom.R
import com.appathy.musicroom.audio.SynthEngine
import com.appathy.musicroom.audio.Wave

class MetronomeActivity : AppCompatActivity() {

    private val meters = arrayOf("2 / 4", "3 / 4", "4 / 4", "5 / 4", "6 / 8")
    private val meterBeats = intArrayOf(2, 3, 4, 5, 6)
    private val divisions = arrayOf("4分音符", "8分音符", "3連符", "16分音符")
    private val divisionValues = intArrayOf(1, 2, 3, 4)

    private lateinit var textBpm: TextView
    private lateinit var textBeats: TextView
    private lateinit var btnStart: Button
    private lateinit var checkAccent: CheckBox

    private val handler = Handler(Looper.getMainLooper())

    @Volatile private var bpm = 100
    @Volatile private var beatsPerMeasure = 4
    @Volatile private var subdivision = 1
    @Volatile private var accent = true
    @Volatile private var running = false
    private var thread: Thread? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_metronome)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        textBpm = findViewById(R.id.textBpm)
        textBeats = findViewById(R.id.textBeats)
        btnStart = findViewById(R.id.btnStart)
        checkAccent = findViewById(R.id.checkAccent)

        val seek = findViewById<SeekBar>(R.id.seekBpm)
        seek.progress = bpm - 30
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) setBpm(progress + 30)
            }

            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })

        findViewById<Button>(R.id.btnMinus).setOnClickListener {
            setBpm(bpm - 1)
            seek.progress = bpm - 30
        }
        findViewById<Button>(R.id.btnPlus).setOnClickListener {
            setBpm(bpm + 1)
            seek.progress = bpm - 30
        }

        val meterSpinner = findViewById<Spinner>(R.id.spinnerMeter)
        meterSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, meters)
        meterSpinner.setSelection(2)
        meterSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: android.view.View?, position: Int, id: Long) {
                beatsPerMeasure = meterBeats[position]
                renderBeats(-1)
            }

            override fun onNothingSelected(p: AdapterView<*>?) {}
        }

        val divisionSpinner = findViewById<Spinner>(R.id.spinnerDivision)
        divisionSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, divisions)
        divisionSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: android.view.View?, position: Int, id: Long) {
                subdivision = divisionValues[position]
            }

            override fun onNothingSelected(p: AdapterView<*>?) {}
        }

        checkAccent.setOnCheckedChangeListener { _, checked -> accent = checked }

        btnStart.setOnClickListener { if (running) stopClick() else startClick() }

        setBpm(bpm)
        renderBeats(-1)
    }

    private fun setBpm(value: Int) {
        bpm = value.coerceIn(30, 250)
        textBpm.text = bpm.toString() + " BPM"
    }

    private fun startClick() {
        SynthEngine.start()
        running = true
        btnStart.text = "■ STOP"
        thread = Thread {
            var tick = 0L
            var next = System.nanoTime()
            while (running) {
                val stepNanos = 60_000_000_000L / bpm / subdivision
                val positionInBeat = (tick % subdivision).toInt()
                val beatIndex = ((tick / subdivision) % beatsPerMeasure).toInt()
                if (positionInBeat == 0) {
                    if (accent && beatIndex == 0) {
                        SynthEngine.blip(1760.0, 0.9, Wave.SQUARE, 0.045)
                    } else {
                        SynthEngine.blip(1175.0, 0.6, Wave.SINE, 0.04)
                    }
                    handler.post { renderBeats(beatIndex) }
                } else {
                    SynthEngine.blip(880.0, 0.24, Wave.SINE, 0.02)
                }
                tick++
                next += stepNanos
                val sleep = next - System.nanoTime()
                if (sleep > 0) {
                    try {
                        Thread.sleep(sleep / 1_000_000L, (sleep % 1_000_000L).toInt())
                    } catch (_: InterruptedException) {
                        break
                    }
                } else {
                    next = System.nanoTime()
                }
            }
        }.also { it.start() }
    }

    private fun stopClick() {
        running = false
        thread?.interrupt()
        thread = null
        btnStart.text = "▶ START"
        renderBeats(-1)
    }

    private fun renderBeats(active: Int) {
        val sb = StringBuilder()
        for (i in 0 until beatsPerMeasure) {
            sb.append(if (i == active) "●" else "○")
            if (i != beatsPerMeasure - 1) sb.append(" ")
        }
        textBeats.text = sb.toString()
    }

    override fun onPause() {
        super.onPause()
        stopClick()
        SynthEngine.stop()
    }
}

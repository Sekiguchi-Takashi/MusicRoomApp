package com.appathy.musicroom.ui

import android.os.Bundle
import android.view.WindowManager
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.Spinner
import android.widget.TextView
import android.widget.ToggleButton
import androidx.appcompat.app.AppCompatActivity
import com.appathy.musicroom.R
import com.appathy.musicroom.audio.SynthEngine
import com.appathy.musicroom.audio.Wave
import com.appathy.musicroom.midi.EventSource
import com.appathy.musicroom.midi.EventType
import com.appathy.musicroom.midi.CcLearn
import com.appathy.musicroom.midi.MidiHub
import com.appathy.musicroom.midi.MusicEvent

class PlayActivity : AppCompatActivity(), MidiHub.Listener, KeyboardView.Callback {

    private lateinit var keyboard: KeyboardView
    private lateinit var textNow: TextView
    private lateinit var textOctave: TextView
    private lateinit var textMidiBadge: TextView
    private lateinit var ccLearn: CcLearn

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_play)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        keyboard = findViewById(R.id.keyboard)
        textNow = findViewById(R.id.textNow)
        textOctave = findViewById(R.id.textOctave)
        textMidiBadge = findViewById(R.id.textMidiBadge)

        ccLearn = CcLearn(this, "play", listOf("音量", "オクターブ"))
        keyboard.callback = this
        keyboard.baseNote = 48
        keyboard.octaveCount = 2

        val spinner = findViewById<Spinner>(R.id.spinnerTimbre)
        spinner.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            Wave.labels
        )
        spinner.setSelection(Wave.values().indexOf(SynthEngine.timbre))
        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: android.view.View?, position: Int, id: Long) {
                SynthEngine.timbre = Wave.values()[position]
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        findViewById<Button>(R.id.btnOctDown).setOnClickListener { shiftOctave(-12) }
        findViewById<Button>(R.id.btnOctUp).setOnClickListener { shiftOctave(12) }

        findViewById<ToggleButton>(R.id.toggleSustain).setOnCheckedChangeListener { _, checked ->
            SynthEngine.setSustain(checked)
        }

        updateOctaveLabel()
    }

    private fun shiftOctave(delta: Int) {
        SynthEngine.allNotesOff()
        keyboard.clearExternal()
        keyboard.baseNote = keyboard.baseNote + delta
        updateOctaveLabel()
    }

    private fun updateOctaveLabel() {
        textOctave.text = MusicEvent.noteName(keyboard.baseNote)
    }

    override fun onResume() {
        super.onResume()
        SynthEngine.start()
        MidiHub.addListener(this)
        MidiHub.autoConnect()
        updateBadge()
    }

    override fun onPause() {
        super.onPause()
        MidiHub.removeListener(this)
        SynthEngine.allNotesOff()
        SynthEngine.stop()
    }

    private fun updateBadge() {
        textMidiBadge.text = if (MidiHub.isConnected) {
            "MIDI ● " + MidiHub.connectedName + " — 鍵盤・パッドがそのまま鳴ります"
        } else {
            "MIDI 未接続 — 画面鍵盤で演奏できます"
        }
    }

    override fun onConnectionChanged() = updateBadge()

    // ------------------------------------------------------------- MIDI input

    override fun onMusicEvent(event: MusicEvent) {
        if (event.source != EventSource.MIDI) return
        when (event.type) {
            EventType.NOTE_ON -> {
                SynthEngine.noteOn(event.note, event.velocity)
                keyboard.setExternalNote(event.note, true)
                textNow.text = event.pitchName
            }
            EventType.NOTE_OFF -> {
                SynthEngine.noteOff(event.note)
                keyboard.setExternalNote(event.note, false)
            }
            EventType.CONTROL_CHANGE -> {
                if (event.controller == 64) {
                    SynthEngine.setSustain(event.value >= 64)
                } else {
                    handleControlChange(event.controller, event.value)
                }
            }
            else -> {}
        }
    }

    private fun handleControlChange(cc: Int, value: Int) {
        val learned = ccLearn.learn(cc)
        if (learned != null) {
            textMidiBadge.text = "このつまみを「" + learned + "」に割り当てました"
            return
        }
        when (ccLearn.roleOf(cc)) {
            "音量" -> SynthEngine.masterGain = 0.04 + 0.36 * value.coerceIn(0, 127) / 127.0
            "オクターブ" -> {
                val target = 36 + (value.coerceIn(0, 127) / 32) * 12
                if (target != keyboard.baseNote) {
                    SynthEngine.allNotesOff()
                    keyboard.clearExternal()
                    keyboard.baseNote = target
                    updateOctaveLabel()
                }
            }
            else -> {}
        }
    }

    // ------------------------------------------------------------ touch input

    override fun onKeyDown(note: Int, velocity: Int) {
        SynthEngine.noteOn(note, velocity)
        textNow.text = MusicEvent.noteName(note)
        MidiHub.inject(
            MusicEvent(EventType.NOTE_ON, note, velocity, source = EventSource.TOUCH)
        )
    }

    override fun onKeyUp(note: Int) {
        SynthEngine.noteOff(note)
        MidiHub.inject(
            MusicEvent(EventType.NOTE_OFF, note, 0, source = EventSource.TOUCH)
        )
    }
}

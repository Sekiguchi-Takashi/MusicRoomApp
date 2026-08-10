package com.appathy.musicroom.ui

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.Spinner
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.appathy.musicroom.R
import com.appathy.musicroom.audio.MusicTheory
import com.appathy.musicroom.audio.SynthEngine
import com.appathy.musicroom.midi.EventSource
import com.appathy.musicroom.midi.EventType
import com.appathy.musicroom.midi.MidiHub
import com.appathy.musicroom.midi.MusicEvent
import kotlin.random.Random

class ChordGameActivity : AppCompatActivity(), MidiHub.Listener, KeyboardView.Callback {

    private val levels = arrayOf("三和音のみ", "四和音を含む", "すべて")

    private lateinit var keyboard: KeyboardView
    private lateinit var textChord: TextView
    private lateinit var textChordName: TextView
    private lateinit var textHeld: TextView
    private lateinit var textFeedback: TextView
    private lateinit var textStreak: TextView
    private lateinit var spinnerLevel: Spinner

    private val handler = Handler(Looper.getMainLooper())
    private val held = LinkedHashSet<Int>()

    private var targetRoot = 0
    private var targetType: MusicTheory.ChordType? = null
    private var targetSet: Set<Int> = emptySet()
    private var waiting = false
    private var asked = 0
    private var correct = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_chord_game)

        keyboard = findViewById(R.id.keyboard)
        textChord = findViewById(R.id.textChord)
        textChordName = findViewById(R.id.textChordName)
        textHeld = findViewById(R.id.textHeld)
        textFeedback = findViewById(R.id.textFeedback)
        textStreak = findViewById(R.id.textStreak)
        spinnerLevel = findViewById(R.id.spinnerLevel)

        keyboard.callback = this
        keyboard.baseNote = 60
        keyboard.octaveCount = 1

        spinnerLevel.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, levels)

        findViewById<Button>(R.id.btnNext).setOnClickListener { nextQuestion() }
        findViewById<Button>(R.id.btnHint).setOnClickListener { showHint() }
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

    private fun availableTypes(): List<MusicTheory.ChordType> = when (spinnerLevel.selectedItemPosition) {
        0 -> MusicTheory.chordTypes.filter { it.intervals.size == 3 }
        1 -> MusicTheory.chordTypes.filter { it.intervals.size <= 4 && it.symbol != "dim" }
        else -> MusicTheory.chordTypes
    }

    private fun nextQuestion() {
        val types = availableTypes()
        val type = types[Random.nextInt(types.size)]
        val roots = MusicTheory.majorScale
        targetRoot = roots[Random.nextInt(roots.size)]
        targetType = type
        targetSet = MusicTheory.chordPitchClasses(targetRoot, type)
        waiting = true
        asked++
        textChord.text = MusicTheory.pitchClassName(targetRoot) + type.symbol
        textChordName.text = MusicTheory.pitchClassName(targetRoot) + " " + type.label
        textFeedback.text = "この和音を同時に押してください。オクターブは問いません。"
        updateStreak()
    }

    private fun showHint() {
        val type = targetType ?: return
        val names = type.intervals.joinToString(" + ") {
            MusicTheory.pitchClassName(targetRoot + it)
        }
        textFeedback.text = "ヒント: " + names
    }

    private fun press(note: Int, velocity: Int) {
        held.add(MusicTheory.pitchClass(note))
        SynthEngine.noteOn(note, velocity)
        updateHeld()
        check()
    }

    private fun release(note: Int) {
        held.remove(MusicTheory.pitchClass(note))
        SynthEngine.noteOff(note)
        updateHeld()
    }

    private fun updateHeld() {
        textHeld.text = "押している音: " + if (held.isEmpty()) {
            "—"
        } else {
            held.sorted().joinToString(" ") { MusicTheory.pitchClassName(it) }
        }
    }

    private fun check() {
        if (!waiting) return
        if (held != targetSet) return
        waiting = false
        correct++
        updateStreak()
        val type = targetType
        textFeedback.text = "正解！ " +
            (type?.intervals?.joinToString(" + ") { MusicTheory.pitchClassName(targetRoot + it) } ?: "") +
            "\n[出題] で次の問題へ。"
        handler.postDelayed({ if (!waiting) textChord.text = "✓" }, 400)
    }

    private fun updateStreak() {
        textStreak.text = correct.toString() + " / " + asked
    }

    override fun onMusicEvent(event: MusicEvent) {
        if (event.source != EventSource.MIDI) return
        when (event.type) {
            EventType.NOTE_ON -> {
                keyboard.setExternalNote(event.note, true)
                press(event.note, event.velocity)
            }
            EventType.NOTE_OFF -> {
                keyboard.setExternalNote(event.note, false)
                release(event.note)
            }
            else -> {}
        }
    }

    override fun onKeyDown(note: Int, velocity: Int) = press(note, velocity)

    override fun onKeyUp(note: Int) = release(note)
}

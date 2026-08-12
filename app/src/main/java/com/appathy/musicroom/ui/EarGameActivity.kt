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
import com.appathy.musicroom.audio.Wave
import com.appathy.musicroom.data.Kind
import com.appathy.musicroom.data.PracticeDb
import com.appathy.musicroom.midi.EventSource
import com.appathy.musicroom.midi.EventType
import com.appathy.musicroom.midi.MidiHub
import com.appathy.musicroom.midi.MusicEvent
import kotlin.random.Random

class EarGameActivity : AppCompatActivity(), MidiHub.Listener, KeyboardView.Callback {

    private val levels = arrayOf("単音", "2音", "3音", "音程 (2音)")

    private lateinit var keyboard: KeyboardView
    private lateinit var textQuestion: TextView
    private lateinit var textFeedback: TextView
    private lateinit var textStreak: TextView
    private lateinit var spinnerLevel: Spinner

    private val handler = Handler(Looper.getMainLooper())
    private var question: List<Int> = emptyList()
    private var answered = ArrayList<Int>()
    private var asked = 0
    private var correct = 0
    private var waiting = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_ear_game)

        keyboard = findViewById(R.id.keyboard)
        textQuestion = findViewById(R.id.textQuestion)
        textFeedback = findViewById(R.id.textFeedback)
        textStreak = findViewById(R.id.textStreak)
        spinnerLevel = findViewById(R.id.spinnerLevel)

        keyboard.callback = this
        keyboard.baseNote = 60
        keyboard.octaveCount = 1

        spinnerLevel.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, levels)

        findViewById<Button>(R.id.btnNext).setOnClickListener { nextQuestion() }
        findViewById<Button>(R.id.btnReplay).setOnClickListener { playQuestion() }
    }

    override fun onResume() {
        super.onResume()
        SynthEngine.start()
        MidiHub.addListener(this)
        MidiHub.autoConnect()
    }

    override fun onPause() {
        super.onPause()
        saveSession()
        MidiHub.removeListener(this)
        SynthEngine.stop()
    }

    private fun saveSession() {
        if (asked < 3) return
        PracticeDb.get(this).insertSession(
            kind = Kind.EAR,
            label = levels[spinnerLevel.selectedItemPosition],
            accuracy = correct.toDouble() / asked,
            itemCount = asked
        )
        asked = 0
        correct = 0
    }

    private fun noteCount(): Int = when (spinnerLevel.selectedItemPosition) {
        0 -> 1
        1 -> 2
        2 -> 3
        else -> 2
    }

    private fun nextQuestion() {
        val count = noteCount()
        val scale = MusicTheory.majorScale
        question = (0 until count).map { 60 + scale[Random.nextInt(scale.size)] }
        answered.clear()
        waiting = true
        asked++
        textQuestion.text = "?"
        textFeedback.text = if (count == 1) {
            "聴こえた音を鍵盤で押してください。"
        } else {
            "聴こえた順に " + count + " 音を押してください。"
        }
        updateStreak()
        playQuestion()
    }

    private fun playQuestion() {
        if (question.isEmpty()) return
        keyboard.clearExternal()
        question.forEachIndexed { index, note ->
            handler.postDelayed({
                SynthEngine.noteOn(note, 100, Wave.PIANO)
                handler.postDelayed({ SynthEngine.noteOff(note) }, 520)
            }, index * 620L)
        }
    }

    private fun submit(note: Int) {
        if (!waiting) return
        answered.add(MusicTheory.pitchClass(note))
        val index = answered.size - 1
        val expected = MusicTheory.pitchClass(question[index])
        if (answered[index] != expected) {
            waiting = false
            textQuestion.text = question.joinToString(" ") { MusicTheory.pitchClassName(it) }
            textFeedback.text = "不正解。正解は " +
                question.joinToString(" → ") { MusicTheory.pitchClassName(it) } +
                (if (question.size == 2 && spinnerLevel.selectedItemPosition == 3) {
                    "\n音程は " + MusicTheory.intervalName(question[1] - question[0])
                } else "") +
                "\n[出題] で次の問題へ。"
            updateStreak()
            return
        }
        if (answered.size == question.size) {
            waiting = false
            correct++
            textQuestion.text = question.joinToString(" ") { MusicTheory.pitchClassName(it) }
            textFeedback.text = "正解！" +
                (if (question.size == 2) "\n音程は " + MusicTheory.intervalName(question[1] - question[0]) else "") +
                "\n[出題] で次の問題へ。"
            updateStreak()
        }
    }

    private fun updateStreak() {
        textStreak.text = correct.toString() + " / " + asked
    }

    override fun onMusicEvent(event: MusicEvent) {
        if (event.source != EventSource.MIDI) return
        if (event.type == EventType.NOTE_OFF) {
            SynthEngine.noteOff(event.note)
            keyboard.setExternalNote(event.note, false)
            return
        }
        if (event.type != EventType.NOTE_ON) return
        SynthEngine.noteOn(event.note, event.velocity, Wave.PIANO)
        keyboard.setExternalNote(event.note, true)
        submit(event.note)
    }

    override fun onKeyDown(note: Int, velocity: Int) {
        SynthEngine.noteOn(note, velocity, Wave.PIANO)
        submit(note)
    }

    override fun onKeyUp(note: Int) {
        SynthEngine.noteOff(note)
    }
}

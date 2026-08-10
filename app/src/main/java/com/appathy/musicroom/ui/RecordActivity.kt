package com.appathy.musicroom.ui

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.WindowManager
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.appathy.musicroom.R
import com.appathy.musicroom.audio.SynthEngine
import com.appathy.musicroom.midi.EventSource
import com.appathy.musicroom.midi.EventType
import com.appathy.musicroom.midi.MidiHub
import com.appathy.musicroom.midi.MusicEvent
import java.io.File

class RecordActivity : AppCompatActivity(), MidiHub.Listener, KeyboardView.Callback {

    private class Take(val timeMs: Long, val on: Boolean, val note: Int, val velocity: Int)

    private lateinit var keyboard: KeyboardView
    private lateinit var textStatus: TextView
    private lateinit var textInfo: TextView
    private lateinit var btnRecord: Button
    private lateinit var btnPlay: Button

    private val handler = Handler(Looper.getMainLooper())
    private val take = ArrayList<Take>()
    private var recording = false
    private var playing = false
    private var recordStart = 0L

    private val fileName = "take.csv"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_record)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        keyboard = findViewById(R.id.keyboard)
        textStatus = findViewById(R.id.textStatus)
        textInfo = findViewById(R.id.textInfo)
        btnRecord = findViewById(R.id.btnRecord)
        btnPlay = findViewById(R.id.btnPlay)

        keyboard.callback = this
        keyboard.baseNote = 60
        keyboard.octaveCount = 2

        btnRecord.setOnClickListener { if (recording) stopRecording() else startRecording() }
        btnPlay.setOnClickListener { if (playing) stopPlayback() else startPlayback() }
        findViewById<Button>(R.id.btnClear).setOnClickListener {
            stopPlayback()
            stopRecording()
            take.clear()
            updateInfo()
            textStatus.text = "待機中"
        }
        findViewById<Button>(R.id.btnSave).setOnClickListener { save() }
        findViewById<Button>(R.id.btnLoad).setOnClickListener { load() }

        updateInfo()
    }

    override fun onResume() {
        super.onResume()
        SynthEngine.start()
        MidiHub.addListener(this)
        MidiHub.autoConnect()
    }

    override fun onPause() {
        super.onPause()
        stopPlayback()
        stopRecording()
        MidiHub.removeListener(this)
        SynthEngine.stop()
    }

    // ------------------------------------------------------------- recording

    private fun startRecording() {
        stopPlayback()
        take.clear()
        recording = true
        recordStart = System.currentTimeMillis()
        btnRecord.text = "■ 停止"
        textStatus.text = "● 録音中"
        updateInfo()
    }

    private fun stopRecording() {
        if (!recording) return
        recording = false
        btnRecord.text = "⏺ 録音"
        textStatus.text = "録音完了"
        updateInfo()
    }

    private fun record(on: Boolean, note: Int, velocity: Int) {
        if (!recording) return
        take.add(Take(System.currentTimeMillis() - recordStart, on, note, velocity))
        updateInfo()
    }

    // -------------------------------------------------------------- playback

    private fun startPlayback() {
        if (take.isEmpty()) {
            textStatus.text = "録音がありません"
            return
        }
        stopRecording()
        playing = true
        btnPlay.text = "■ 停止"
        textStatus.text = "▶ 再生中"
        keyboard.clearExternal()
        take.forEach { item ->
            handler.postDelayed({
                if (!playing) return@postDelayed
                if (item.on) {
                    SynthEngine.noteOn(item.note, item.velocity)
                    keyboard.setExternalNote(item.note, true)
                } else {
                    SynthEngine.noteOff(item.note)
                    keyboard.setExternalNote(item.note, false)
                }
            }, item.timeMs)
        }
        handler.postDelayed({ stopPlayback() }, (take.last().timeMs + 600L))
    }

    private fun stopPlayback() {
        if (!playing) return
        playing = false
        handler.removeCallbacksAndMessages(null)
        SynthEngine.allNotesOff()
        keyboard.clearExternal()
        btnPlay.text = "▶ 再生"
        textStatus.text = "待機中"
    }

    // ------------------------------------------------------------------ file

    private fun save() {
        if (take.isEmpty()) {
            textStatus.text = "保存するものがありません"
            return
        }
        try {
            val sb = StringBuilder()
            take.forEach {
                sb.append(it.timeMs).append(',')
                    .append(if (it.on) 1 else 0).append(',')
                    .append(it.note).append(',')
                    .append(it.velocity).append('\n')
            }
            File(filesDir, fileName).writeText(sb.toString())
            textStatus.text = "保存しました"
        } catch (e: Exception) {
            textStatus.text = "保存に失敗しました"
        }
    }

    private fun load() {
        try {
            val file = File(filesDir, fileName)
            if (!file.exists()) {
                textStatus.text = "保存データがありません"
                return
            }
            take.clear()
            file.readLines().forEach { line ->
                val parts = line.split(',')
                if (parts.size == 4) {
                    take.add(
                        Take(
                            parts[0].toLong(),
                            parts[1] == "1",
                            parts[2].toInt(),
                            parts[3].toInt()
                        )
                    )
                }
            }
            textStatus.text = "読み込みました"
            updateInfo()
        } catch (e: Exception) {
            textStatus.text = "読み込みに失敗しました"
        }
    }

    private fun updateInfo() {
        val lengthMs = take.lastOrNull()?.timeMs ?: 0L
        val noteCount = take.count { it.on }
        textInfo.text = "イベント " + take.size + " 件 / 打鍵 " + noteCount +
            " / 長さ " + String.format("%.1f", lengthMs / 1000.0) + " 秒"
    }

    // ----------------------------------------------------------------- input

    override fun onMusicEvent(event: MusicEvent) {
        if (event.source != EventSource.MIDI) return
        when (event.type) {
            EventType.NOTE_ON -> {
                SynthEngine.noteOn(event.note, event.velocity)
                keyboard.setExternalNote(event.note, true)
                record(true, event.note, event.velocity)
            }
            EventType.NOTE_OFF -> {
                SynthEngine.noteOff(event.note)
                keyboard.setExternalNote(event.note, false)
                record(false, event.note, 0)
            }
            else -> {}
        }
    }

    override fun onKeyDown(note: Int, velocity: Int) {
        SynthEngine.noteOn(note, velocity)
        record(true, note, velocity)
    }

    override fun onKeyUp(note: Int) {
        SynthEngine.noteOff(note)
        record(false, note, 0)
    }
}

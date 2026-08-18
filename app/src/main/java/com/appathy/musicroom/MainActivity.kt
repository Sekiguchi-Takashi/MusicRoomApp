package com.appathy.musicroom

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.appathy.musicroom.midi.MidiHub
import com.appathy.musicroom.ui.ChordGameActivity
import com.appathy.musicroom.ui.EarGameActivity
import com.appathy.musicroom.ui.ComposeActivity
import com.appathy.musicroom.ui.HistoryActivity
import com.appathy.musicroom.ui.MetronomeActivity
import com.appathy.musicroom.ui.MusicSearchActivity
import com.appathy.musicroom.ui.MidiActivity
import com.appathy.musicroom.ui.PlayActivity
import com.appathy.musicroom.ui.RecordActivity
import com.appathy.musicroom.ui.RepetitionActivity
import com.appathy.musicroom.ui.RhythmGameActivity
import com.appathy.musicroom.ui.SongPracticeActivity
import com.appathy.musicroom.ui.SingActivity
import com.appathy.musicroom.ui.SoundLabActivity
import com.appathy.musicroom.ui.TunerActivity

class MainActivity : AppCompatActivity(), MidiHub.Listener {

    private lateinit var textMidiState: TextView
    private lateinit var textMidiDetail: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        textMidiState = findViewById(R.id.textMidiState)
        textMidiDetail = findViewById(R.id.textMidiDetail)

        findViewById<View>(R.id.cardMidi).setOnClickListener { open(MidiActivity::class.java) }
        findViewById<View>(R.id.btnMidi).setOnClickListener { open(MidiActivity::class.java) }
        findViewById<View>(R.id.btnSong).setOnClickListener { open(SongPracticeActivity::class.java) }
        findViewById<View>(R.id.btnSing).setOnClickListener { open(SingActivity::class.java) }
        findViewById<View>(R.id.btnTuner).setOnClickListener { open(TunerActivity::class.java) }
        findViewById<View>(R.id.btnPlay).setOnClickListener { open(PlayActivity::class.java) }
        findViewById<View>(R.id.btnRecord).setOnClickListener { open(RecordActivity::class.java) }
        findViewById<View>(R.id.btnMetronome).setOnClickListener { open(MetronomeActivity::class.java) }
        findViewById<View>(R.id.btnRepetition).setOnClickListener { open(RepetitionActivity::class.java) }
        findViewById<View>(R.id.btnHistory).setOnClickListener { open(HistoryActivity::class.java) }
        findViewById<View>(R.id.btnCompose).setOnClickListener { open(ComposeActivity::class.java) }
        findViewById<View>(R.id.btnSearch).setOnClickListener { open(MusicSearchActivity::class.java) }
        findViewById<View>(R.id.btnSoundLab).setOnClickListener { open(SoundLabActivity::class.java) }
        findViewById<View>(R.id.btnRhythm).setOnClickListener { open(RhythmGameActivity::class.java) }
        findViewById<View>(R.id.btnEar).setOnClickListener { open(EarGameActivity::class.java) }
        findViewById<View>(R.id.btnChord).setOnClickListener { open(ChordGameActivity::class.java) }
    }

    private fun open(cls: Class<*>) = startActivity(Intent(this, cls))

    override fun onResume() {
        super.onResume()
        MidiHub.addListener(this)
        MidiHub.autoConnect()
        updateMidiCard()
    }

    override fun onPause() {
        super.onPause()
        MidiHub.removeListener(this)
    }

    override fun onConnectionChanged() = updateMidiCard()

    override fun onDeviceListChanged() = updateMidiCard()

    private fun updateMidiCard() {
        if (MidiHub.isConnected) {
            textMidiState.text = "🎹 MIDI ● 接続中"
            textMidiDetail.text = MidiHub.connectedName
        } else {
            val found = MidiHub.devices().size
            textMidiState.text = "🎹 MIDI ○ 未接続"
            textMidiDetail.text = if (found > 0) {
                "検出 " + found + "台 — タップして接続"
            } else {
                "USB-C で MIDIキーボードを接続してください"
            }
        }
    }
}

package com.appathy.musicroom.ui

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.appathy.musicroom.R
import com.appathy.musicroom.audio.SynthEngine
import com.appathy.musicroom.audio.Wave
import com.appathy.musicroom.data.UserSong
import com.appathy.musicroom.data.UserSongStore
import com.appathy.musicroom.midi.MusicEvent
import com.appathy.musicroom.song.Mora
import com.appathy.musicroom.song.SongNote

class LyricsActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_SONG_ID = "song_id"
    }

    private lateinit var editLyrics: EditText
    private lateinit var textCount: TextView
    private lateinit var textAlign: TextView
    private lateinit var textTitle: TextView

    private val handler = Handler(Looper.getMainLooper())
    private var song: UserSong? = null
    private var notes: List<SongNote> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_lyrics)

        editLyrics = findViewById(R.id.editLyrics)
        textCount = findViewById(R.id.textCount)
        textAlign = findViewById(R.id.textAlign)
        textTitle = findViewById(R.id.textTitle)

        val id = intent.getStringExtra(EXTRA_SONG_ID)
        if (id != null) {
            song = UserSongStore.load(this, id)
        }
        val loaded = song
        if (loaded == null) {
            Toast.makeText(this, "曲を読み込めませんでした。", Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        notes = loaded.notes.sortedBy { it.beat }
        textTitle.text = "✍ " + loaded.title
        editLyrics.setText(loaded.lyrics)

        editLyrics.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) = render()
        })

        findViewById<Button>(R.id.btnSave).setOnClickListener { save() }
        findViewById<Button>(R.id.btnSing).setOnClickListener { preview() }

        render()
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacksAndMessages(null)
        SynthEngine.allNotesOff()
        SynthEngine.stop()
    }

    private fun render() {
        val text = editLyrics.text.toString()
        val alignment = Mora.align(text, notes)

        textCount.text = "モーラ " + alignment.moraCount + " / 音 " + alignment.noteCount +
            (if (alignment.fits) "   ✓ ちょうど合っています" else "") +
            (if (alignment.warning != null) "\n" + alignment.warning else "")
        textCount.setTextColor(
            if (alignment.fits && alignment.warning == null) getColor(R.color.accent)
            else getColor(R.color.text_primary)
        )

        if (text.isBlank()) {
            textAlign.text = "歌詞を入力すると、1音ずつの割り付けを表示します。\n\n" +
                "この曲は " + notes.size + " 音です。まず " + notes.size + " モーラの言葉を考えてみてください。"
            return
        }

        val hints = Mora.sustainHints(alignment).toSet()
        val sb = StringBuilder()
        alignment.pairs.forEachIndexed { index, pair ->
            val note = pair.second
            sb.append(String.format("%3d  ", index + 1))
            sb.append(pair.first.padEnd(3, '　'))
            if (note == null) {
                sb.append("  (音なし)")
            } else {
                sb.append("  ").append(MusicEvent.noteName(note.pitch).padEnd(4))
                sb.append(formatLength(note.lengthBeats))
                if (index in hints) sb.append("  ← 伸ばす")
            }
            sb.append('\n')
        }
        textAlign.text = sb.toString().trimEnd()
    }

    private fun formatLength(beats: Double): String = when {
        beats >= 3.9 -> "全音符"
        beats >= 1.9 -> "2分音符"
        beats >= 0.9 -> "4分音符"
        beats >= 0.45 -> "8分音符"
        else -> "16分音符"
    }

    private fun preview() {
        val loaded = song ?: return
        handler.removeCallbacksAndMessages(null)
        SynthEngine.allNotesOff()
        SynthEngine.start()
        val msPerBeat = 60_000.0 / loaded.bpm
        val moras = Mora.split(editLyrics.text.toString())
        notes.forEachIndexed { index, note ->
            handler.postDelayed({
                SynthEngine.noteOn(note.pitch, 100, Wave.PIANO)
                handler.postDelayed(
                    { SynthEngine.noteOff(note.pitch) },
                    (note.lengthBeats * msPerBeat).toLong().coerceAtLeast(120L)
                )
                val mora = moras.getOrNull(index)
                if (mora != null) textTitle.text = "✍ " + mora
            }, (note.beat * msPerBeat).toLong())
        }
        handler.postDelayed(
            { textTitle.text = "✍ " + loaded.title },
            ((notes.lastOrNull()?.let { it.beat + it.lengthBeats } ?: 0.0) * msPerBeat).toLong() + 500L
        )
    }

    private fun save() {
        val loaded = song ?: return
        val updated = loaded.copy(
            lyrics = editLyrics.text.toString(),
            updatedAt = System.currentTimeMillis()
        )
        val ok = UserSongStore.save(this, updated)
        song = updated
        Toast.makeText(
            this,
            if (ok) "歌詞を保存しました。" else "保存に失敗しました。",
            Toast.LENGTH_SHORT
        ).show()
    }
}

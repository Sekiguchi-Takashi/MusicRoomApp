package com.appathy.musicroom.ui

import android.app.AlertDialog
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.WindowManager
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.appathy.musicroom.R
import com.appathy.musicroom.audio.SynthEngine
import com.appathy.musicroom.audio.Wave
import com.appathy.musicroom.data.UserSong
import com.appathy.musicroom.data.UserSongStore
import com.appathy.musicroom.midi.EventSource
import com.appathy.musicroom.midi.EventType
import com.appathy.musicroom.midi.MidiHub
import com.appathy.musicroom.midi.CcLearn
import com.appathy.musicroom.midi.MusicEvent
import com.appathy.musicroom.song.Harmonizer
import com.appathy.musicroom.song.Mora
import com.appathy.musicroom.song.Quantizer
import com.appathy.musicroom.song.RawNote

class ComposeActivity : AppCompatActivity(), MidiHub.Listener, KeyboardView.Callback,
    ComposeGridView.Callback {

    companion object {
        const val EXTRA_SONG_ID = "song_id"
        /** 音楽検索から曲名だけを引き継いで新しい曲を始めるときに使う。 */
        const val EXTRA_NEW_TITLE = "new_title"
    }

    private val gridLabels = arrayOf("4分音符", "8分音符", "16分音符", "3連符")
    private val gridValues = intArrayOf(
        Quantizer.GRID_QUARTER, Quantizer.GRID_EIGHTH,
        Quantizer.GRID_SIXTEENTH, Quantizer.GRID_TRIPLET
    )

    private lateinit var gridView: ComposeGridView
    private lateinit var keyboard: KeyboardView
    private lateinit var textTitle: TextView
    private lateinit var textStatus: TextView
    private lateinit var btnRecord: Button
    private lateinit var btnPlay: Button

    private val handler = Handler(Looper.getMainLooper())

    private var songId = UserSongStore.newId()
    private var title = "無題"
    private var bpm = 100
    private var lyrics = ""
    private val beatsPerBar = 4

    private var recording = false
    private var recordStart = 0L
    private val rawTake = ArrayList<RawNote>()
    private val pressed = HashMap<Int, Pair<Long, Int>>()

    private var playing = false
    private var playStart = 0L

    private lateinit var btnTrack: Button
    private lateinit var ccLearn: CcLearn

    private val importPicker = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? -> if (uri != null) importFrom(uri) }

    private val playTicker = object : Runnable {
        override fun run() {
            if (!playing) return
            val elapsed = (System.nanoTime() - playStart) / 1_000_000.0
            val beat = elapsed / (60_000.0 / bpm)
            gridView.playheadBeat = beat
            if (beat > gridView.totalBeats()) {
                stopPlayback()
                return
            }
            handler.postDelayed(this, 40)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_compose)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        gridView = findViewById(R.id.gridView)
        keyboard = findViewById(R.id.keyboard)
        textTitle = findViewById(R.id.textTitle)
        textStatus = findViewById(R.id.textStatus)
        btnRecord = findViewById(R.id.btnRecord)
        btnPlay = findViewById(R.id.btnPlay)
        btnTrack = findViewById(R.id.btnTrack)
        ccLearn = CcLearn(this, "compose", listOf("テンポ", "スクロール"))

        gridView.callback = this
        keyboard.callback = this
        keyboard.baseNote = 60
        keyboard.octaveCount = 1

        findViewById<Button>(R.id.btnRecord).setOnClickListener {
            if (recording) stopRecording(true) else startRecording()
        }
        findViewById<Button>(R.id.btnPlay).setOnClickListener {
            if (playing) stopPlayback() else startPlayback()
        }
        btnTrack.setOnClickListener { toggleTrack() }
        findViewById<Button>(R.id.btnHarmony).setOnClickListener { showHarmony() }
        findViewById<Button>(R.id.btnShare).setOnClickListener { showShare() }
        findViewById<Button>(R.id.btnSettings).setOnClickListener { showSettings() }
        findViewById<Button>(R.id.btnSave).setOnClickListener { save(true) }
        findViewById<Button>(R.id.btnLibrary).setOnClickListener { showLibrary() }
        findViewById<Button>(R.id.btnLyrics).setOnClickListener { openLyrics() }

        intent.getStringExtra(EXTRA_SONG_ID)?.let { load(it) }
        intent.getStringExtra(EXTRA_NEW_TITLE)?.let { incoming ->
            songId = UserSongStore.newId()
            title = incoming
            lyrics = ""
            gridView.notes = ArrayList()
            gridView.backing = ArrayList()
            gridView.editingBacking = false
        }
        refresh()
    }

    override fun onResume() {
        super.onResume()
        SynthEngine.start()
        MidiHub.addListener(this)
        MidiHub.autoConnect()
        UserSongStore.load(this, songId)?.let { if (it.lyrics != lyrics) lyrics = it.lyrics }
        refresh()
    }

    override fun onPause() {
        super.onPause()
        stopPlayback()
        stopRecording(false)
        MidiHub.removeListener(this)
        SynthEngine.allNotesOff()
        SynthEngine.stop()
        save(false)
    }

    // ------------------------------------------------------------------ edit

    override fun onNotesChanged() = refresh()

    override fun onNotePreview(pitch: Int) {
        val token = SynthEngine.noteOn(pitch, 96, Wave.PIANO)
        handler.postDelayed({ SynthEngine.releaseToken(token) }, 220)
    }

    private fun refresh() {
        textTitle.text = "🎼 " + title
        val moraCount = Mora.count(lyrics)
        btnTrack.text = if (gridView.editingBacking) "🎵 伴奏" else "♪ メロディ"
        textStatus.text = "メロディ " + gridView.notes.size +
            (if (gridView.backing.isNotEmpty()) " / 伴奏 " + gridView.backing.size else "") +
            " / BPM " + bpm + " / " + gridView.barCount + "小節" +
            (if (lyrics.isNotBlank()) " / 歌詞 " + moraCount + "モーラ" else "")
    }

    private fun toggleTrack() {
        gridView.editingBacking = !gridView.editingBacking
        refresh()
        Toast.makeText(
            this,
            if (gridView.editingBacking) "伴奏トラックを編集します (和音を置けます)"
            else "メロディトラックを編集します",
            Toast.LENGTH_SHORT
        ).show()
    }

    // --------------------------------------------------------------- recording

    private fun startRecording() {
        stopPlayback()
        rawTake.clear()
        pressed.clear()
        recording = true
        recordStart = System.currentTimeMillis()
        btnRecord.text = "■ 停止"
        Toast.makeText(this, "自由に弾いてください。停止すると拍にそろえます。", Toast.LENGTH_SHORT).show()
    }

    private fun stopRecording(fromUser: Boolean) {
        if (!recording) return
        recording = false
        btnRecord.text = "⏺ 弾いて入力"

        val now = System.currentTimeMillis() - recordStart
        pressed.forEach { (pitch, value) ->
            rawTake.add(RawNote(value.first, now, pitch, value.second))
        }
        pressed.clear()

        if (!fromUser) return
        if (rawTake.size < 2) {
            Toast.makeText(this, "音が少なすぎます。2音以上弾いてください。", Toast.LENGTH_SHORT).show()
            return
        }
        confirmQuantize()
    }

    private fun confirmQuantize() {
        val estimated = Quantizer.estimateBpm(rawTake, gridView.grid)
        val message = "録音した " + rawTake.size + " 音を拍にそろえます。\n" +
            "推定テンポ: " + estimated + " BPM\n\n" +
            "いまの譜面に追加するのではなく、置き換えます。"
        AlertDialog.Builder(this)
            .setTitle("弾いた演奏を譜面にする")
            .setMessage(message)
            .setNegativeButton("やめる", null)
            .setNeutralButton("今のBPM (" + bpm + ") で") { _, _ -> applyQuantize(bpm) }
            .setPositiveButton("推定BPMで") { _, _ -> applyQuantize(estimated) }
            .show()
    }

    private fun applyQuantize(useBpm: Int) {
        val toBacking = gridView.editingBacking
        val result = Quantizer.quantize(
            rawTake, useBpm, gridView.grid, beatsPerBar, polyphonic = toBacking
        )
        if (result.notes.isEmpty()) {
            Toast.makeText(this, "譜面に変換できませんでした。", Toast.LENGTH_SHORT).show()
            return
        }
        bpm = useBpm
        if (toBacking) {
            gridView.backing = result.notes.toMutableList()
        } else {
            gridView.notes = result.notes.toMutableList()
        }
        val endBeat = result.notes.maxOf { it.beat + it.lengthBeats }
        gridView.barCount = (kotlin.math.ceil(endBeat / beatsPerBar).toInt()).coerceIn(1, 16)
        fitRange()
        gridView.scrollBeats = 0.0
        refresh()

        val summary = "音 " + result.notes.size + " 個に変換しました。\n" +
            "平均の補正 " + result.meanShiftMs.toInt() + " ms" +
            (if (result.droppedCount > 0) " / 重なった " + result.droppedCount + " 音を整理しました" else "")
        Toast.makeText(this, summary, Toast.LENGTH_LONG).show()
    }

    private fun fitRange() {
        val notes = gridView.notes + gridView.backing
        if (notes.isEmpty()) return
        val low = notes.minOf { it.pitch }
        val high = notes.maxOf { it.pitch }
        gridView.lowPitch = (low - 2).coerceAtLeast(24)
        gridView.highPitch = (high + 2).coerceAtMost(96)
        if (gridView.highPitch - gridView.lowPitch < 11) {
            gridView.highPitch = gridView.lowPitch + 11
        }
        gridView.postInvalidateOnAnimation()
    }

    // --------------------------------------------------------------- playback

    private fun startPlayback() {
        if (gridView.notes.isEmpty()) {
            Toast.makeText(this, "音がまだありません。", Toast.LENGTH_SHORT).show()
            return
        }
        stopRecording(false)
        playing = true
        btnPlay.text = "■ 停止"
        playStart = System.nanoTime()
        val msPerBeat = 60_000.0 / bpm
        gridView.notes.sortedBy { it.beat }.forEach { note ->
            handler.postDelayed({
                if (!playing) return@postDelayed
                val token = SynthEngine.noteOn(note.pitch, 100, Wave.PIANO)
                handler.postDelayed(
                    { SynthEngine.releaseToken(token) },
                    (note.lengthBeats * msPerBeat).toLong().coerceAtLeast(120L)
                )
            }, (note.beat * msPerBeat).toLong())
        }
        gridView.backing.sortedBy { it.beat }.forEach { note ->
            handler.postDelayed({
                if (!playing) return@postDelayed
                val token = SynthEngine.noteOn(note.pitch, 62, Wave.PIANO)
                handler.postDelayed(
                    { SynthEngine.releaseToken(token) },
                    (note.lengthBeats * msPerBeat).toLong().coerceAtLeast(120L)
                )
            }, (note.beat * msPerBeat).toLong())
        }
        handler.post(playTicker)
    }

    private fun stopPlayback() {
        if (!playing) return
        playing = false
        handler.removeCallbacks(playTicker)
        handler.removeCallbacksAndMessages(null)
        SynthEngine.allNotesOff()
        gridView.playheadBeat = -1.0
        btnPlay.text = "▶ 再生"
    }

    // --------------------------------------------------------------- settings

    private fun showSettings() {
        val view = layoutInflater.inflate(R.layout.dialog_compose_settings, null)
        val editTitle = view.findViewById<EditText>(R.id.editTitle)
        val seekBpm = view.findViewById<SeekBar>(R.id.seekBpm)
        val labelBpm = view.findViewById<TextView>(R.id.labelBpm)
        val spinnerGrid = view.findViewById<Spinner>(R.id.spinnerGrid)
        val seekBars = view.findViewById<SeekBar>(R.id.seekBars)
        val labelBars = view.findViewById<TextView>(R.id.labelBars)
        val seekLow = view.findViewById<SeekBar>(R.id.seekLow)
        val labelRange = view.findViewById<TextView>(R.id.labelRange)

        editTitle.setText(title)
        seekBpm.progress = bpm - 40
        labelBpm.text = "テンポ  " + bpm + " BPM"
        seekBpm.setOnSeekBarChangeListener(simpleSeek { labelBpm.text = "テンポ  " + (it + 40) + " BPM" })

        spinnerGrid.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, gridLabels)
        spinnerGrid.setSelection(gridValues.indexOf(gridView.grid).coerceAtLeast(0))

        seekBars.progress = gridView.barCount - 1
        labelBars.text = "小節数  " + gridView.barCount
        seekBars.setOnSeekBarChangeListener(simpleSeek { labelBars.text = "小節数  " + (it + 1) })

        seekLow.progress = gridView.lowPitch - 36
        labelRange.text = "音域の下端  " + MusicEvent.noteName(gridView.lowPitch)
        seekLow.setOnSeekBarChangeListener(simpleSeek {
            labelRange.text = "音域の下端  " + MusicEvent.noteName(it + 36)
        })

        AlertDialog.Builder(this)
            .setTitle("曲の設定")
            .setView(view)
            .setNeutralButton("つまみの割当を消す") { _, _ ->
                ccLearn.reset()
                Toast.makeText(this, "割当を消しました。次に動かしたつまみから順に決まります。", Toast.LENGTH_LONG).show()
            }
            .setNegativeButton("やめる", null)
            .setPositiveButton("適用") { _, _ ->
                title = editTitle.text.toString().ifBlank { "無題" }
                bpm = seekBpm.progress + 40
                gridView.grid = gridValues[spinnerGrid.selectedItemPosition]
                gridView.barCount = seekBars.progress + 1
                gridView.lowPitch = seekLow.progress + 36
                gridView.highPitch = gridView.lowPitch + 11
                sanitizeAfterSettings()
                gridView.scrollBeats = gridView.scrollBeats
                gridView.postInvalidateOnAnimation()
                refresh()
            }
            .show()
    }

    /** 設定で狭めても既存ノートが範囲外に隠れないよう、包含するまで広げ直す。 */
    private fun sanitizeAfterSettings() {
        val notes = gridView.notes + gridView.backing
        if (notes.isEmpty()) return
        val endBeat = notes.maxOf { it.beat + it.lengthBeats }
        val neededBars = kotlin.math.ceil(endBeat / beatsPerBar).toInt()
        if (gridView.barCount < neededBars) {
            gridView.barCount = neededBars.coerceAtMost(16)
            Toast.makeText(this, "音があるため小節数を " + gridView.barCount + " に広げました。", Toast.LENGTH_SHORT).show()
        }
        val low = notes.minOf { it.pitch }
        val high = notes.maxOf { it.pitch }
        if (low < gridView.lowPitch) gridView.lowPitch = low.coerceAtLeast(24)
        if (high > gridView.highPitch) gridView.highPitch = high.coerceAtMost(96)
        if (gridView.highPitch - gridView.lowPitch < 11) {
            gridView.highPitch = (gridView.lowPitch + 11).coerceAtMost(96)
        }
    }

    private fun simpleSeek(onChange: (Int) -> Unit) = object : SeekBar.OnSeekBarChangeListener {
        override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) = onChange(progress)
        override fun onStartTrackingTouch(sb: SeekBar?) {}
        override fun onStopTrackingTouch(sb: SeekBar?) {}
    }

    // ------------------------------------------------------------------ store

    private fun current(): UserSong = UserSong(
        id = songId,
        title = title,
        bpm = bpm,
        beatsPerBar = beatsPerBar,
        lyrics = lyrics,
        updatedAt = System.currentTimeMillis(),
        notes = gridView.notes.sortedBy { it.beat },
        accompaniment = gridView.backing.sortedBy { it.beat }
    )

    private fun save(explicit: Boolean) {
        if (gridView.notes.isEmpty()) {
            if (explicit) Toast.makeText(this, "音がまだありません。", Toast.LENGTH_SHORT).show()
            return
        }
        val ok = UserSongStore.save(this, current())
        if (explicit) {
            Toast.makeText(
                this,
                if (ok) "「" + title + "」を保存しました。楽曲練習・うた練習から選べます。" else "保存に失敗しました。",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun load(id: String) {
        val song = UserSongStore.load(this, id) ?: return
        songId = song.id
        title = song.title
        bpm = song.bpm
        lyrics = song.lyrics
        gridView.notes = song.notes.toMutableList()
        gridView.backing = song.accompaniment.toMutableList()
        gridView.editingBacking = false
        val endBeat = (song.notes + song.accompaniment)
            .maxOfOrNull { it.beat + it.lengthBeats } ?: 4.0
        gridView.barCount = kotlin.math.ceil(endBeat / beatsPerBar).toInt().coerceIn(1, 16)
        fitRange()
        refresh()
    }

    private fun showLibrary() {
        val songs = UserSongStore.all(this)
        if (songs.isEmpty()) {
            Toast.makeText(this, "保存された曲はまだありません。", Toast.LENGTH_SHORT).show()
            return
        }
        val labels = songs.map {
            it.title + "  (" + it.notes.size + "音 / " + it.bpm + " BPM)"
        }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("曲を開く")
            .setItems(labels) { _, which ->
                save(false)
                load(songs[which].id)
            }
            .setNeutralButton("新しい曲") { _, _ ->
                save(false)
                songId = UserSongStore.newId()
                title = "無題"
                lyrics = ""
                gridView.notes = ArrayList()
                gridView.backing = ArrayList()
                gridView.editingBacking = false
                gridView.barCount = 4
                gridView.lowPitch = 60
                gridView.highPitch = 72
                refresh()
            }
            .setNegativeButton("やめる", null)
            .show()
    }

    // ---------------------------------------------------------------- harmony

    private fun showHarmony() {
        if (gridView.notes.isEmpty()) {
            Toast.makeText(this, "先にメロディを作ってください。", Toast.LENGTH_SHORT).show()
            return
        }
        val styles = Harmonizer.Style.values()
        val chords = Harmonizer.analyze(gridView.notes, beatsPerBar, gridView.barCount)
        val preview = chords.take(8).joinToString(" | ") { it.label }
        val labels = styles.map { it.label }.toTypedArray()

        AlertDialog.Builder(this)
            .setTitle(
                "伴奏をつける\n推定コード: " + preview + (if (chords.size > 8) " ..." else "")
            )
            .setNegativeButton("やめる", null)
            .setItems(labels) { _, which ->
                applyHarmony(chords, styles[which])
            }
            .show()
    }

    private fun applyHarmony(
        chords: List<com.appathy.musicroom.song.BarChord>,
        style: Harmonizer.Style
    ) {
        val melodyLow = gridView.notes.minOfOrNull { it.pitch } ?: 60
        val generated = Harmonizer.accompaniment(chords, beatsPerBar, style, melodyLow)
        gridView.backing = generated.toMutableList()
        fitRange()
        refresh()
        Toast.makeText(
            this,
            "伴奏を " + generated.size + " 音つくりました。手直しは [🎵 伴奏] に切り替えてください。",
            Toast.LENGTH_LONG
        ).show()
    }

    // ------------------------------------------------------------ share / io

    private fun showShare() {
        AlertDialog.Builder(this)
            .setTitle("書き出し / 取り込み")
            .setItems(
                arrayOf("この曲を共有して送る", "この曲をコピー", "クリップボードから取り込む", "ファイルから取り込む")
            ) { _, which ->
                when (which) {
                    0 -> shareSong()
                    1 -> copySong()
                    2 -> importFromClipboard()
                    3 -> importPicker.launch(arrayOf("application/json", "text/plain", "*/*"))
                }
            }
            .setNegativeButton("やめる", null)
            .show()
    }

    private fun shareSong() {
        if (gridView.notes.isEmpty()) {
            Toast.makeText(this, "音がまだありません。", Toast.LENGTH_SHORT).show()
            return
        }
        val json = UserSongStore.exportJson(current())
        val intent = Intent(Intent.ACTION_SEND)
        intent.type = "text/plain"
        intent.putExtra(Intent.EXTRA_SUBJECT, title + " (音楽室の曲)")
        intent.putExtra(Intent.EXTRA_TEXT, json)
        startActivity(Intent.createChooser(intent, "曲を送る"))
    }

    private fun copySong() {
        if (gridView.notes.isEmpty()) {
            Toast.makeText(this, "音がまだありません。", Toast.LENGTH_SHORT).show()
            return
        }
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(
            android.content.ClipData.newPlainText("musicroom_song", UserSongStore.exportJson(current()))
        )
        Toast.makeText(this, "曲をコピーしました。", Toast.LENGTH_SHORT).show()
    }

    private fun importFromClipboard() {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val text = clipboard.primaryClip?.getItemAt(0)?.coerceToText(this)?.toString()
        if (text.isNullOrBlank()) {
            Toast.makeText(this, "クリップボードが空です。", Toast.LENGTH_SHORT).show()
            return
        }
        acceptImported(UserSongStore.importJson(text))
    }

    private fun importFrom(uri: Uri) {
        val text = try {
            contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
        } catch (e: Exception) {
            null
        }
        if (text.isNullOrBlank()) {
            Toast.makeText(this, "ファイルを読めませんでした。", Toast.LENGTH_SHORT).show()
            return
        }
        acceptImported(UserSongStore.importJson(text))
    }

    private fun acceptImported(imported: com.appathy.musicroom.data.UserSong?) {
        if (imported == null) {
            Toast.makeText(this, "曲のデータとして読めませんでした。", Toast.LENGTH_LONG).show()
            return
        }
        save(false)
        UserSongStore.save(this, imported)
        load(imported.id)
        Toast.makeText(
            this,
            "「" + imported.title + "」を取り込みました (" + imported.notes.size + "音)。",
            Toast.LENGTH_LONG
        ).show()
    }

    private fun openLyrics() {
        if (gridView.notes.isEmpty()) {
            Toast.makeText(this, "先にメロディを作ってください。", Toast.LENGTH_SHORT).show()
            return
        }
        save(false)
        val intent = android.content.Intent(this, LyricsActivity::class.java)
        intent.putExtra(LyricsActivity.EXTRA_SONG_ID, songId)
        startActivity(intent)
    }

    // ------------------------------------------------------------------ input

    override fun onMusicEvent(event: MusicEvent) {
        if (event.source != EventSource.MIDI) return
        when (event.type) {
            EventType.NOTE_ON -> {
                SynthEngine.noteOn(event.note, event.velocity, Wave.PIANO)
                keyboard.setExternalNote(event.note, true)
                capture(true, event.note, event.velocity)
            }
            EventType.NOTE_OFF -> {
                SynthEngine.noteOff(event.note)
                keyboard.setExternalNote(event.note, false)
                capture(false, event.note, 0)
            }
            EventType.CONTROL_CHANGE -> handleControlChange(event.controller, event.value)
            else -> {}
        }
    }

    /** MiniLab 3 のノブ/フェーダー。最初に動かしたものから順に役割が決まる。 */
    private fun handleControlChange(cc: Int, value: Int) {
        if (cc == 64) return
        val learned = ccLearn.learn(cc)
        if (learned != null) {
            Toast.makeText(this, "このつまみを「" + learned + "」に割り当てました。", Toast.LENGTH_SHORT).show()
            return
        }
        when (ccLearn.roleOf(cc)) {
            "テンポ" -> {
                if (recording || playing) return
                bpm = ccLearn.scale(value, 40, 200)
                refresh()
            }
            "スクロール" -> {
                val total = gridView.totalBeats() - gridView.visibleBeats
                gridView.scrollBeats = total * value.coerceIn(0, 127) / 127.0
            }
            else -> {}
        }
    }

    override fun onKeyDown(note: Int, velocity: Int) {
        SynthEngine.noteOn(note, velocity, Wave.PIANO)
        capture(true, note, velocity)
    }

    override fun onKeyUp(note: Int) {
        SynthEngine.noteOff(note)
        capture(false, note, 0)
    }

    private fun capture(on: Boolean, pitch: Int, velocity: Int) {
        if (!recording) return
        val now = System.currentTimeMillis() - recordStart
        if (on) {
            pressed[pitch] = Pair(now, velocity)
        } else {
            val start = pressed.remove(pitch) ?: return
            rawTake.add(RawNote(start.first, now, pitch, start.second))
        }
    }
}

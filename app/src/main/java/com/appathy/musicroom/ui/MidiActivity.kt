package com.appathy.musicroom.ui

import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.appathy.musicroom.R
import com.appathy.musicroom.audio.SynthEngine
import com.appathy.musicroom.midi.EventType
import com.appathy.musicroom.midi.MidiHub
import com.appathy.musicroom.midi.MusicEvent

class MidiActivity : AppCompatActivity(), MidiHub.Listener {

    private lateinit var textState: TextView
    private lateinit var deviceList: LinearLayout
    private lateinit var textNoteName: TextView
    private lateinit var textDetail: TextView
    private lateinit var textVerdict: TextView
    private lateinit var textLog: TextView

    private val log = ArrayDeque<String>()
    private var receivedAny = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_midi)

        textState = findViewById(R.id.textState)
        deviceList = findViewById(R.id.deviceList)
        textNoteName = findViewById(R.id.textNoteName)
        textDetail = findViewById(R.id.textDetail)
        textVerdict = findViewById(R.id.textVerdict)
        textLog = findViewById(R.id.textLog)

        findViewById<Button>(R.id.btnScan).setOnClickListener {
            refreshDevices()
            MidiHub.autoConnect()
        }
        findViewById<Button>(R.id.btnDisconnect).setOnClickListener { MidiHub.disconnect() }
    }

    override fun onResume() {
        super.onResume()
        SynthEngine.start()
        MidiHub.addListener(this)
        refreshDevices()
        updateState()
    }

    override fun onPause() {
        super.onPause()
        MidiHub.removeListener(this)
        SynthEngine.stop()
    }

    override fun onDeviceListChanged() {
        refreshDevices()
        updateState()
    }

    override fun onConnectionChanged() {
        refreshDevices()
        updateState()
    }

    private fun updateState() {
        textState.text = if (MidiHub.isConnected) {
            "● 接続中 — " + MidiHub.connectedName
        } else {
            "○ 未接続"
        }
    }

    private fun refreshDevices() {
        deviceList.removeAllViews()
        val devices = MidiHub.devices()
        if (devices.isEmpty()) {
            deviceList.addView(hint("デバイスが見つかりません。USB-C ケーブルで接続し、[デバイスを検索] を押してください。"))
            return
        }
        devices.forEach { info ->
            val name = MidiHub.nameOf(info)
            val row = TextView(this).apply {
                text = "🎹 " + name + "\n" + MidiHub.typeOf(info) +
                    " / 出力ポート " + info.outputPortCount +
                    (if (name == MidiHub.connectedName) "  ● 接続中" else "")
                setTextColor(getColor(R.color.text_primary))
                textSize = 15f
                setPadding(pad(16), pad(14), pad(16), pad(14))
                setBackgroundResource(R.drawable.bg_menu)
                setLineSpacing(pad(4).toFloat(), 1f)
                setOnClickListener { MidiHub.connect(info) }
            }
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            lp.bottomMargin = pad(8)
            deviceList.addView(row, lp)
        }
    }

    private fun hint(message: String): View = TextView(this).apply {
        text = message
        setTextColor(getColor(R.color.text_secondary))
        textSize = 13f
        gravity = Gravity.START
        setPadding(pad(4), pad(4), pad(4), pad(4))
    }

    private fun pad(dp: Int): Int = (dp * resources.displayMetrics.density).toInt()

    override fun onMusicEvent(event: MusicEvent) {
        when (event.type) {
            EventType.NOTE_ON -> {
                textNoteName.text = event.pitchName
                textDetail.text = "Note     : " + event.note +
                    "\nVelocity : " + event.velocity +
                    "\nStatus   : ON" +
                    "\nChannel  : " + (event.channel + 1)
                SynthEngine.noteOn(event.note, event.velocity)
                addLog("NoteOn  " + event.pitchName + " (" + event.note + ") vel=" + event.velocity + " ch=" + (event.channel + 1))
            }
            EventType.NOTE_OFF -> {
                textDetail.text = "Note     : " + event.note +
                    "\nVelocity : " + event.velocity +
                    "\nStatus   : OFF" +
                    "\nChannel  : " + (event.channel + 1)
                SynthEngine.noteOff(event.note)
                addLog("NoteOff " + event.pitchName + " (" + event.note + ") ch=" + (event.channel + 1))
            }
            EventType.CONTROL_CHANGE ->
                addLog("CC      #" + event.controller + " = " + event.value + " ch=" + (event.channel + 1))
            EventType.PITCH_BEND ->
                addLog("Bend    " + event.value + " ch=" + (event.channel + 1))
            EventType.PROGRAM_CHANGE ->
                addLog("Program " + event.value + " ch=" + (event.channel + 1))
        }
        if (!receivedAny) {
            receivedAny = true
            textVerdict.text = "✓ MIDI入力正常"
            textVerdict.setTextColor(getColor(R.color.accent))
        }
    }

    private fun addLog(line: String) {
        log.addFirst(line)
        while (log.size > 20) log.removeLast()
        textLog.text = log.joinToString("\n")
    }
}

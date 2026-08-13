package com.appathy.musicroom.ui

import android.bluetooth.BluetoothDevice
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import com.appathy.musicroom.R
import com.appathy.musicroom.audio.SynthEngine
import com.appathy.musicroom.midi.EventType
import com.appathy.musicroom.midi.BleMidiScanner
import com.appathy.musicroom.midi.MidiHub
import com.appathy.musicroom.midi.MusicEvent

class MidiActivity : AppCompatActivity(), MidiHub.Listener {

    private lateinit var textState: TextView
    private lateinit var deviceList: LinearLayout
    private lateinit var textNoteName: TextView
    private lateinit var textDetail: TextView
    private lateinit var textVerdict: TextView
    private lateinit var textLog: TextView
    private lateinit var btnBle: Button
    private lateinit var textBleState: TextView
    private lateinit var bleList: LinearLayout
    private val bleFound = LinkedHashMap<String, Pair<BluetoothDevice, String>>()

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

        btnBle = findViewById(R.id.btnBle)
        textBleState = findViewById(R.id.textBleState)
        bleList = findViewById(R.id.bleList)
        btnBle.setOnClickListener { toggleBleScan() }
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
        BleMidiScanner.stop(this, null)
        MidiHub.removeListener(this)
        SynthEngine.stop()
    }

    // ------------------------------------------------------------- BLE MIDI

    private fun toggleBleScan() {
        if (BleMidiScanner.isScanning) {
            BleMidiScanner.stop(this, "スキャンを止めました。")
            return
        }
        if (!BleMidiScanner.hasPermissions(this)) {
            ActivityCompat.requestPermissions(this, BleMidiScanner.requiredPermissions(), 10)
            return
        }
        bleFound.clear()
        bleList.removeAllViews()
        val error = BleMidiScanner.start(this, object : BleMidiScanner.Callback {
            override fun onFound(device: BluetoothDevice, name: String) {
                val key = try {
                    device.address
                } catch (e: SecurityException) {
                    name
                }
                if (bleFound.containsKey(key)) return
                bleFound[key] = Pair(device, name)
                renderBleList()
            }

            override fun onScanEnded(reason: String?) {
                btnBle.text = "📡 Bluetooth MIDI を探す"
                textBleState.text = reason ?: (
                    if (bleFound.isEmpty()) "見つかりませんでした。機器の電源とペアリングモードを確認してください。"
                    else bleFound.size.toString() + "台 見つかりました。タップして接続します。"
                    )
            }
        })
        if (error != null) {
            textBleState.text = error
            return
        }
        btnBle.text = "■ スキャンを止める"
        textBleState.text = "探しています... (最大12秒)"
    }

    private fun renderBleList() {
        bleList.removeAllViews()
        bleFound.values.forEach { (device, name) ->
            val row = TextView(this).apply {
                text = "📡 " + name + "\nBluetooth MIDI"
                setTextColor(getColor(R.color.text_primary))
                textSize = 15f
                setPadding(pad(16), pad(14), pad(16), pad(14))
                setBackgroundResource(R.drawable.bg_menu)
                setLineSpacing(pad(4).toFloat(), 1f)
                setOnClickListener {
                    BleMidiScanner.stop(this@MidiActivity, "接続しています...")
                    MidiHub.connectBluetooth(device, name)
                }
            }
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            lp.bottomMargin = pad(8)
            bleList.addView(row, lp)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != 10) return
        if (grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
            toggleBleScan()
        } else {
            textBleState.text = "Bluetooth の権限がないため、ワイヤレス機器を探せません。"
        }
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

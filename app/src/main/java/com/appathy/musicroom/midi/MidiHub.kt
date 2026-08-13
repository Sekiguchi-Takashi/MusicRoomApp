package com.appathy.musicroom.midi

import android.annotation.SuppressLint
import android.content.Context
import android.media.midi.MidiDevice
import android.media.midi.MidiDeviceInfo
import android.media.midi.MidiManager
import android.media.midi.MidiOutputPort
import android.media.midi.MidiReceiver
import android.os.Build
import android.os.Handler
import android.os.Looper
import java.util.concurrent.CopyOnWriteArrayList

/**
 * MIDI入力の唯一の入口。
 * USB MIDI (Arturia MiniLab 3 など) を MidiManager 経由で開き、
 * 生バイト列を MusicEvent へ正規化してリスナへ配信する。
 * タッチ鍵盤からの入力も inject() で同じ経路に流す。
 */
object MidiHub {

    interface Listener {
        fun onMusicEvent(event: MusicEvent) {}
        fun onDeviceListChanged() {}
        fun onConnectionChanged() {}
    }

    private const val PREFS = "midi_prefs"
    private const val KEY_LAST_DEVICE = "last_device"

    private lateinit var appContext: Context
    private var manager: MidiManager? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private val listeners = CopyOnWriteArrayList<Listener>()

    private var openedDevice: MidiDevice? = null
    private var openedPort: MidiOutputPort? = null

    var connectedName: String = ""
        private set

    var lastEventCount: Long = 0L
        private set

    val isConnected: Boolean get() = openedPort != null

    fun init(context: Context) {
        if (this::appContext.isInitialized) return
        appContext = context.applicationContext
        manager = appContext.getSystemService(Context.MIDI_SERVICE) as? MidiManager
        registerDeviceCallback()
        autoConnect()
    }

    fun addListener(l: Listener) {
        if (!listeners.contains(l)) listeners.add(l)
    }

    fun removeListener(l: Listener) {
        listeners.remove(l)
    }

    // ---------------------------------------------------------------- devices

    @SuppressLint("NewApi")
    fun devices(): List<MidiDeviceInfo> {
        val m = manager ?: return emptyList()
        val all: Collection<MidiDeviceInfo> =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                m.getDevicesForTransport(MidiManager.TRANSPORT_MIDI_BYTE_STREAM)
            } else {
                @Suppress("DEPRECATION")
                m.devices.toList()
            }
        return all.filter { it.outputPortCount > 0 }
    }

    fun nameOf(info: MidiDeviceInfo): String {
        val p = info.properties
        val name = p.getString(MidiDeviceInfo.PROPERTY_NAME)
        if (!name.isNullOrBlank()) return name
        val maker = p.getString(MidiDeviceInfo.PROPERTY_MANUFACTURER) ?: ""
        val product = p.getString(MidiDeviceInfo.PROPERTY_PRODUCT) ?: ""
        val joined = (maker + " " + product).trim()
        return if (joined.isNotBlank()) joined else "MIDI Device"
    }

    fun typeOf(info: MidiDeviceInfo): String = when (info.type) {
        MidiDeviceInfo.TYPE_USB -> "USB MIDI"
        MidiDeviceInfo.TYPE_BLUETOOTH -> "Bluetooth MIDI"
        MidiDeviceInfo.TYPE_VIRTUAL -> "Virtual MIDI"
        else -> "MIDI"
    }

    // ------------------------------------------------------------- connection

    fun connect(info: MidiDeviceInfo) {
        val m = manager ?: return
        disconnect()
        val label = nameOf(info)
        m.openDevice(info, { device ->
            attach(device, label, firstOutputPortIndex(info))
        }, mainHandler)
    }

    /** BLE MIDI 機器を接続する。USB と同じ経路へ合流させる。 */
    fun connectBluetooth(device: android.bluetooth.BluetoothDevice, label: String) {
        val m = manager ?: return
        disconnect()
        m.openBluetoothDevice(device, { opened ->
            attach(opened, label, -1)
        }, mainHandler)
    }

    /** 開いた MidiDevice を受け取り、出力ポートを購読する。 */
    private fun attach(device: MidiDevice?, label: String, preferredPort: Int) {
        if (device == null) {
            mainHandler.post { notifyConnection() }
            return
        }
        val portIndex = if (preferredPort >= 0) preferredPort else firstOutputPortIndex(device.info)
        val port = device.openOutputPort(portIndex)
        if (port == null) {
            try {
                device.close()
            } catch (_: Exception) {
            }
            mainHandler.post { notifyConnection() }
            return
        }
        port.connect(receiver)
        openedDevice = device
        openedPort = port
        connectedName = label
        saveLastDevice(label)
        mainHandler.post { notifyConnection() }
    }

    fun disconnect() {
        try {
            openedPort?.disconnect(receiver)
            openedPort?.close()
        } catch (_: Exception) {
        }
        try {
            openedDevice?.close()
        } catch (_: Exception) {
        }
        openedPort = null
        openedDevice = null
        connectedName = ""
        notifyConnection()
    }

    fun autoConnect() {
        if (isConnected) return
        val list = devices()
        if (list.isEmpty()) return
        val last = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_LAST_DEVICE, null)
        val target = list.firstOrNull { nameOf(it) == last } ?: list.first()
        connect(target)
    }

    private fun firstOutputPortIndex(info: MidiDeviceInfo): Int {
        info.ports.forEach { p ->
            if (p.type == MidiDeviceInfo.PortInfo.TYPE_OUTPUT) return p.portNumber
        }
        return 0
    }

    private fun saveLastDevice(name: String) {
        appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_LAST_DEVICE, name).apply()
    }

    @SuppressLint("NewApi")
    private fun registerDeviceCallback() {
        val m = manager ?: return
        val cb = object : MidiManager.DeviceCallback() {
            override fun onDeviceAdded(device: MidiDeviceInfo) {
                mainHandler.post {
                    notifyDeviceList()
                    autoConnect()
                }
            }

            override fun onDeviceRemoved(device: MidiDeviceInfo) {
                mainHandler.post {
                    if (nameOf(device) == connectedName) disconnect()
                    notifyDeviceList()
                }
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val executor = java.util.concurrent.Executor { r -> mainHandler.post(r) }
            m.registerDeviceCallback(
                MidiManager.TRANSPORT_MIDI_BYTE_STREAM,
                executor,
                cb
            )
        } else {
            @Suppress("DEPRECATION")
            m.registerDeviceCallback(cb, mainHandler)
        }
    }

    // ---------------------------------------------------------------- parsing

    private var runningStatus = 0
    private var pendingData1 = -1
    private var inSysex = false

    private val receiver = object : MidiReceiver() {
        override fun onSend(msg: ByteArray, offset: Int, count: Int, timestamp: Long) {
            parse(msg, offset, count, timestamp)
        }
    }

    private fun parse(data: ByteArray, offset: Int, count: Int, timestamp: Long) {
        var i = offset
        val end = offset + count
        while (i < end) {
            val b = data[i].toInt() and 0xFF
            i++
            if (b >= 0xF8) continue
            if (b >= 0x80) {
                when {
                    b == 0xF0 -> {
                        inSysex = true
                        runningStatus = 0
                    }
                    b == 0xF7 -> inSysex = false
                    b > 0xF0 -> runningStatus = 0
                    else -> {
                        runningStatus = b
                        pendingData1 = -1
                        inSysex = false
                    }
                }
                continue
            }
            if (inSysex || runningStatus == 0) continue
            val command = runningStatus and 0xF0
            if (command == 0xC0 || command == 0xD0) {
                emit(command, runningStatus and 0x0F, b, 0, timestamp)
                continue
            }
            if (pendingData1 < 0) {
                pendingData1 = b
                continue
            }
            emit(command, runningStatus and 0x0F, pendingData1, b, timestamp)
            pendingData1 = -1
        }
    }

    private fun emit(command: Int, channel: Int, d1: Int, d2: Int, timestamp: Long) {
        val event = when (command) {
            0x90 -> if (d2 == 0) {
                MusicEvent(EventType.NOTE_OFF, d1, 0, channel, timestampNanos = timestamp)
            } else {
                MusicEvent(EventType.NOTE_ON, d1, d2, channel, timestampNanos = timestamp)
            }
            0x80 -> MusicEvent(EventType.NOTE_OFF, d1, d2, channel, timestampNanos = timestamp)
            0xB0 -> MusicEvent(
                EventType.CONTROL_CHANGE, -1, 0, channel,
                controller = d1, value = d2, timestampNanos = timestamp
            )
            0xE0 -> MusicEvent(
                EventType.PITCH_BEND, -1, 0, channel,
                value = (d2 shl 7) or d1, timestampNanos = timestamp
            )
            0xC0 -> MusicEvent(
                EventType.PROGRAM_CHANGE, -1, 0, channel,
                value = d1, timestampNanos = timestamp
            )
            else -> null
        } ?: return
        dispatch(event)
    }

    /** タッチ鍵盤など、アプリ内部からの入力を同じ経路へ流す。 */
    fun inject(event: MusicEvent) = dispatch(event)

    private fun dispatch(event: MusicEvent) {
        lastEventCount++
        mainHandler.post {
            listeners.forEach { it.onMusicEvent(event) }
        }
    }

    private fun notifyDeviceList() = listeners.forEach { it.onDeviceListChanged() }

    private fun notifyConnection() = listeners.forEach { it.onConnectionChanged() }
}

package com.appathy.musicroom.midi

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import androidx.core.content.ContextCompat
import java.util.UUID

/**
 * BLE MIDI (MIDI over Bluetooth LE) の機器探索。
 * MIDI サービス UUID でフィルタするので、対応機器だけが出る。
 * Arturia MiniLab 3 は USB のみなのでここには出ない。
 */
object BleMidiScanner {

    val MIDI_SERVICE_UUID: UUID = UUID.fromString("03B80E5A-EDE8-4B33-A751-6CE34EC4C700")

    interface Callback {
        fun onFound(device: BluetoothDevice, name: String)
        fun onScanEnded(reason: String?)
    }

    private val handler = Handler(Looper.getMainLooper())
    private var scanning = false
    private var callback: Callback? = null
    private var scanCallback: ScanCallback? = null

    val isScanning: Boolean get() = scanning

    fun requiredPermissions(): Array<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }

    fun hasPermissions(context: Context): Boolean =
        requiredPermissions().all {
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        }

    private fun adapter(context: Context): BluetoothAdapter? {
        val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        return manager?.adapter
    }

    fun isAvailable(context: Context): Boolean = adapter(context)?.isEnabled == true

    @SuppressLint("MissingPermission")
    fun start(context: Context, cb: Callback, timeoutMs: Long = 12_000L): String? {
        if (scanning) return null
        if (!hasPermissions(context)) return "Bluetooth の権限がありません。"
        val adapter = adapter(context) ?: return "この端末は Bluetooth に対応していません。"
        if (!adapter.isEnabled) return "Bluetooth がオフになっています。"
        val scanner = adapter.bluetoothLeScanner ?: return "BLE スキャナを取得できませんでした。"

        callback = cb
        val filters = listOf(
            ScanFilter.Builder()
                .setServiceUuid(ParcelUuid(MIDI_SERVICE_UUID))
                .build()
        )
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        val internal = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val device = result.device ?: return
                val name = try {
                    device.name
                } catch (e: SecurityException) {
                    null
                } ?: result.scanRecord?.deviceName ?: "BLE MIDI Device"
                handler.post { callback?.onFound(device, name) }
            }

            override fun onScanFailed(errorCode: Int) {
                handler.post { stop(context, "スキャンに失敗しました (code " + errorCode + ")") }
            }
        }
        scanCallback = internal

        return try {
            scanner.startScan(filters, settings, internal)
            scanning = true
            handler.postDelayed({ stop(context, null) }, timeoutMs)
            null
        } catch (e: Exception) {
            scanning = false
            "スキャンを開始できませんでした。"
        }
    }

    @SuppressLint("MissingPermission")
    fun stop(context: Context, reason: String?) {
        if (!scanning) return
        scanning = false
        try {
            adapter(context)?.bluetoothLeScanner?.stopScan(scanCallback)
        } catch (_: Exception) {
        }
        scanCallback = null
        val cb = callback
        callback = null
        cb?.onScanEnded(reason)
    }
}

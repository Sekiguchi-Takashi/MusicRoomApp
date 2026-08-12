package com.appathy.musicroom.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.WindowManager
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import com.appathy.musicroom.R
import com.appathy.musicroom.audio.MicEngine
import com.appathy.musicroom.audio.SynthEngine
import com.appathy.musicroom.audio.Wave
import com.appathy.musicroom.audio.YinDetector
import com.appathy.musicroom.midi.MusicEvent
import kotlin.math.abs
import kotlin.math.roundToInt

class TunerActivity : AppCompatActivity(), MicEngine.Listener {

    private lateinit var textNote: TextView
    private lateinit var textDetail: TextView
    private lateinit var textVerdict: TextView
    private lateinit var textHint: TextView
    private lateinit var meter: PitchMeterView
    private lateinit var barLevel: ProgressBar

    private var smoothedMidi = -1.0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_tuner)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        textNote = findViewById(R.id.textNote)
        textDetail = findViewById(R.id.textDetail)
        textVerdict = findViewById(R.id.textVerdict)
        textHint = findViewById(R.id.textHint)
        meter = findViewById(R.id.meter)
        barLevel = findViewById(R.id.barLevel)

        findViewById<Button>(R.id.btnRefTone).setOnClickListener {
            SynthEngine.start()
            SynthEngine.blip(440.0, 0.8, Wave.SINE, 0.9)
        }
    }

    override fun onResume() {
        super.onResume()
        SynthEngine.start()
        MicEngine.addListener(this)
        ensureMic()
    }

    override fun onPause() {
        super.onPause()
        MicEngine.removeListener(this)
        MicEngine.stop()
        SynthEngine.stop()
    }

    private fun ensureMic() {
        if (!MicEngine.hasPermission(this)) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), 1)
            textHint.text = "マイクの使用を許可してください。"
            return
        }
        if (!MicEngine.start(this)) {
            textHint.text = "マイクを開始できませんでした。他のアプリが使用中かもしれません。"
        } else {
            textHint.text = "声を出すと、いま出ている音の高さを表示します。"
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            ensureMic()
        } else {
            textHint.text = "マイクが許可されていないため、音程チェックは使えません。"
        }
    }

    override fun onPitch(hz: Double, midi: Double, confidence: Double, level: Double) {
        barLevel.progress = (level * 400).toInt().coerceIn(0, 100)
        if (hz <= 0.0) {
            meter.voiced = false
            textVerdict.text = "待機中"
            textVerdict.setTextColor(getColor(R.color.text_secondary))
            return
        }
        smoothedMidi = if (smoothedMidi < 0 || abs(smoothedMidi - midi) > 1.5) {
            midi
        } else {
            smoothedMidi * 0.65 + midi * 0.35
        }
        val nearest = smoothedMidi.roundToInt()
        val cents = YinDetector.centsOff(smoothedMidi)
        meter.voiced = true
        meter.cents = cents
        textNote.text = MusicEvent.noteName(nearest)
        textDetail.text = String.format("%.1f Hz   %+d cent", hz, cents.roundToInt())
        when {
            abs(cents) <= 10 -> {
                textVerdict.text = "✓ 合っています"
                textVerdict.setTextColor(getColor(R.color.accent))
            }
            cents < 0 -> {
                textVerdict.text = "すこし低い"
                textVerdict.setTextColor(getColor(R.color.accent_warm))
            }
            else -> {
                textVerdict.text = "すこし高い"
                textVerdict.setTextColor(getColor(R.color.accent_warm))
            }
        }
    }
}

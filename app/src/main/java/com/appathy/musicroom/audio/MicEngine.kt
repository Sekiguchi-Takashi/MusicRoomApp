package com.appathy.musicroom.audio

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.math.max

/**
 * マイクから常時読み出し、YIN で音程を推定して配信する。
 * 22050Hz が使えない端末では 44100Hz で読んで 1/2 に間引く。
 */
object MicEngine {

    interface Listener {
        /**
         * hz が -1 のときは無声。midi は実数 (小数がセントのズレ)。
         * timestampNanos は「その音が実際に発せられた推定時刻」で、
         * バッファ長ぶんの遅れを差し引いてある。判定に使うのはこちら。
         */
        fun onPitch(
            hz: Double,
            midi: Double,
            confidence: Double,
            level: Double,
            timestampNanos: Long
        )
    }

    const val SAMPLE_RATE = 22050
    private const val WINDOW = 1024
    private const val HOP = 512
    private const val SILENCE_RMS = 0.012

    private val listeners = CopyOnWriteArrayList<Listener>()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val detector = YinDetector(SAMPLE_RATE, WINDOW)
    private val recent = ArrayDeque<Double>()

    private var thread: Thread? = null
    @Volatile private var running = false

    var lastHz = -1.0
        private set

    val isRunning: Boolean get() = running

    fun hasPermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    fun addListener(l: Listener) {
        if (!listeners.contains(l)) listeners.add(l)
    }

    fun removeListener(l: Listener) = listeners.remove(l)

    @SuppressLint("MissingPermission")
    @Synchronized
    fun start(context: Context): Boolean {
        if (running) return true
        if (!hasPermission(context)) return false

        var rate = SAMPLE_RATE
        var decimate = false
        var minBuffer = AudioRecord.getMinBufferSize(
            rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBuffer <= 0) {
            rate = 44100
            decimate = true
            minBuffer = AudioRecord.getMinBufferSize(
                rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
            )
        }
        if (minBuffer <= 0) return false

        val record = try {
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                rate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                max(minBuffer, HOP * 8)
            )
        } catch (e: Exception) {
            return false
        }
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            return false
        }

        running = true
        record.startRecording()
        thread = Thread { loop(record, decimate) }.also {
            it.priority = Thread.NORM_PRIORITY + 2
            it.start()
        }
        return true
    }

    @Synchronized
    fun stop() {
        running = false
        try {
            thread?.join(400)
        } catch (_: InterruptedException) {
        }
        thread = null
        lastHz = -1.0
    }

    private fun loop(record: AudioRecord, decimate: Boolean) {
        val readSize = if (decimate) HOP * 2 else HOP
        val raw = ShortArray(readSize)
        val window = FloatArray(WINDOW)
        try {
            while (running) {
                val read = record.read(raw, 0, readSize)
                if (read <= 0) continue

                // 窓をずらす
                System.arraycopy(window, HOP, window, 0, WINDOW - HOP)
                if (decimate) {
                    var w = WINDOW - HOP
                    var i = 0
                    while (i + 1 < read && w < WINDOW) {
                        window[w] = (raw[i] + raw[i + 1]) / 2f / 32768f
                        w++
                        i += 2
                    }
                    while (w < WINDOW) {
                        window[w] = 0f
                        w++
                    }
                } else {
                    val copied = minOf(read, HOP)
                    for (i in 0 until copied) {
                        window[WINDOW - HOP + i] = raw[i] / 32768f
                    }
                    for (i in copied until HOP) {
                        window[WINDOW - HOP + i] = 0f
                    }
                }

                // 窓の中心が発音時刻。読み終えた時刻から窓の半分ぶん遡る。
                val captureNanos = System.nanoTime() - (WINDOW / 2L) * 1_000_000_000L / SAMPLE_RATE

                val level = YinDetector.rms(window)
                if (level < SILENCE_RMS) {
                    recent.clear()
                    lastHz = -1.0
                    emit(-1.0, -1.0, 0.0, level, captureNanos)
                    continue
                }
                val result = detector.detect(window)
                val hz = result[0]
                val confidence = result[1]
                if (hz < 70.0 || hz > 1200.0) {
                    recent.clear()
                    lastHz = -1.0
                    emit(-1.0, -1.0, confidence, level, captureNanos)
                } else {
                    val stable = smooth(hz)
                    lastHz = stable
                    emit(stable, YinDetector.midiOf(stable), confidence, level, captureNanos)
                }
            }
        } catch (_: Exception) {
        } finally {
            try {
                record.stop()
            } catch (_: Exception) {
            }
            record.release()
        }
    }

    /**
     * 直近3フレームの中央値を返す。
     * 1フレームだけ跳ねる誤検出 (子音や息の混入) を落とすためで、
     * 平均ではなく中央値なのは外れ値に引っぱられないようにするため。
     */
    private fun smooth(hz: Double): Double {
        recent.addLast(hz)
        while (recent.size > 3) recent.removeFirst()
        if (recent.size < 3) return hz
        val sorted = recent.sorted()
        return sorted[1]
    }

    private fun emit(
        hz: Double,
        midi: Double,
        confidence: Double,
        level: Double,
        timestampNanos: Long
    ) {
        if (listeners.isEmpty()) return
        mainHandler.post {
            listeners.forEach { it.onPitch(hz, midi, confidence, level, timestampNanos) }
        }
    }
}

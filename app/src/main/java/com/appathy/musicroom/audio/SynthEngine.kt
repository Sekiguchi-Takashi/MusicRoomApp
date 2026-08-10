package com.appathy.musicroom.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import com.appathy.musicroom.midi.MusicEvent
import kotlin.math.exp
import kotlin.math.max

/**
 * ポリフォニックなリアルタイム音源。
 * MIDI / タッチ双方の NoteOn / NoteOff をそのまま受ける。
 */
object SynthEngine {

    const val SAMPLE_RATE = 44100
    private const val BLOCK = 192
    private const val MAX_VOICES = 16

    private class Voice {
        @Volatile var active = false
        var note = -1
        var wave = Wave.PIANO
        var phase = 0.0
        var inc = 0.0
        var amp = 0.0
        var env = 0.0
        var stage = 0 // 1=attack 2=hold 3=release
        var attackInc = 0.0
        var decayRate = 1.0
        var releaseRate = 0.0
        var duty = 0.5
        var age = 0L
    }

    private val voices = Array(MAX_VOICES) { Voice() }
    private val lock = Object()

    private var track: AudioTrack? = null
    private var thread: Thread? = null
    @Volatile private var running = false
    @Volatile private var ageCounter = 0L

    @Volatile var timbre: Wave = Wave.PIANO
    @Volatile var masterGain: Double = 0.22
    @Volatile var sustainPedal: Boolean = false

    // ------------------------------------------------------------- lifecycle

    @Synchronized
    fun start() {
        if (running) return
        val minBuf = AudioTrack.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        val bufferSize = max(minBuf, BLOCK * 2 * 4)
        val t = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setBufferSizeInBytes(bufferSize)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
            .build()
        track = t
        t.play()
        running = true
        thread = Thread { renderLoop(t) }.also {
            it.priority = Thread.MAX_PRIORITY
            it.start()
        }
    }

    @Synchronized
    fun stop() {
        running = false
        try {
            thread?.join(400)
        } catch (_: InterruptedException) {
        }
        thread = null
        try {
            track?.pause()
            track?.flush()
            track?.release()
        } catch (_: Exception) {
        }
        track = null
        synchronized(lock) { voices.forEach { it.active = false } }
    }

    // ------------------------------------------------------------------ play

    fun noteOn(note: Int, velocity: Int, wave: Wave = timbre) {
        if (note < 0 || note > 127) return
        val freq = MusicEvent.frequency(note)
        val amp = (velocity.coerceIn(1, 127) / 127.0).let { it * it * 0.9 + 0.1 }
        allocate(note, freq, amp, wave, -1.0)
    }

    fun noteOff(note: Int) {
        if (sustainPedal) return
        synchronized(lock) {
            voices.forEach { v ->
                if (v.active && v.note == note && v.stage != 3) v.stage = 3
            }
        }
    }

    fun allNotesOff() {
        synchronized(lock) { voices.forEach { if (it.active) it.stage = 3 } }
    }

    fun setSustain(on: Boolean) {
        sustainPedal = on
        if (!on) allNotesOff()
    }

    /** メトロノームのクリックなど、短い単発音。 */
    fun blip(freq: Double, amp: Double, wave: Wave = Wave.SINE, decaySeconds: Double = 0.05) {
        allocate(-1, freq, amp, wave, decaySeconds)
    }

    private fun allocate(note: Int, freq: Double, amp: Double, wave: Wave, forcedDecay: Double) {
        val v: Voice
        synchronized(lock) {
            v = voices.firstOrNull { !it.active }
                ?: voices.minByOrNull { it.age }!!
            v.active = false
        }
        val decaying = forcedDecay > 0.0 || Waveform.isDecaying(wave)
        val decayTau = when {
            forcedDecay > 0.0 -> forcedDecay
            wave == Wave.NOISE -> 0.18
            else -> 1.6
        }
        val attackSeconds = if (wave == Wave.NOISE) 0.001 else 0.005
        synchronized(lock) {
            v.note = note
            v.wave = wave
            v.phase = 0.0
            v.inc = freq / SAMPLE_RATE
            v.amp = amp
            v.env = 0.0
            v.stage = 1
            v.attackInc = 1.0 / (attackSeconds * SAMPLE_RATE)
            v.decayRate = if (decaying) exp(-1.0 / (decayTau * SAMPLE_RATE)) else 1.0
            v.releaseRate = exp(-1.0 / (0.07 * SAMPLE_RATE))
            v.duty = if (wave == Wave.PULSE25) 0.25 else 0.5
            v.age = ++ageCounter
            v.active = true
        }
    }

    // ---------------------------------------------------------------- render

    private fun renderLoop(t: AudioTrack) {
        val mix = DoubleArray(BLOCK)
        val out = ShortArray(BLOCK)
        while (running) {
            java.util.Arrays.fill(mix, 0.0)
            synchronized(lock) {
                for (v in voices) {
                    if (!v.active) continue
                    renderVoice(v, mix)
                }
            }
            for (i in 0 until BLOCK) {
                var s = mix[i] * masterGain
                if (s > 1.0) s = 1.0
                if (s < -1.0) s = -1.0
                out[i] = (s * 32767.0).toInt().toShort()
            }
            try {
                t.write(out, 0, BLOCK)
            } catch (_: Exception) {
                break
            }
        }
    }

    private fun renderVoice(v: Voice, mix: DoubleArray) {
        for (i in mix.indices) {
            when (v.stage) {
                1 -> {
                    v.env += v.attackInc
                    if (v.env >= 1.0) {
                        v.env = 1.0
                        v.stage = 2
                    }
                }
                2 -> v.env *= v.decayRate
                else -> v.env *= v.releaseRate
            }
            if (v.env < 0.0004) {
                v.active = false
                return
            }
            mix[i] += Waveform.sample(v.wave, v.phase, v.duty) * v.env * v.amp
            v.phase += v.inc
            if (v.phase >= 1.0) v.phase -= 1.0
        }
    }
}

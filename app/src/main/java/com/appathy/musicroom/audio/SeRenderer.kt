package com.appathy.musicroom.audio

import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.sin

/**
 * ゲーム効果音のパラメータ。
 * steps > 1 でアルペジオ的な階段状ピッチ変化 (コイン音・パワーアップ音) になる。
 */
data class SeSpec(
    var name: String,
    var wave: Wave,
    var startFreq: Double,
    var endFreq: Double,
    var durationMs: Int,
    var decayTau: Double,
    var steps: Int = 1,
    var vibratoHz: Double = 0.0,
    var vibratoDepth: Double = 0.0,
    var duty: Double = 0.5,
    var noiseMix: Double = 0.0
) {
    fun copySpec(): SeSpec = copy()
}

object SePresets {

    val all: List<SeSpec> = listOf(
        SeSpec("コイン", Wave.SQUARE, 988.0, 1319.0, 420, 0.30, steps = 2),
        SeSpec("ジャンプ", Wave.PULSE25, 220.0, 880.0, 220, 0.18, duty = 0.25),
        SeSpec("決定", Wave.SQUARE, 880.0, 1568.0, 160, 0.09, steps = 2),
        SeSpec("キャンセル", Wave.SQUARE, 440.0, 196.0, 220, 0.12),
        SeSpec("パワーアップ", Wave.SQUARE, 262.0, 1047.0, 700, 0.60, steps = 6),
        SeSpec("ダメージ", Wave.SAW, 400.0, 70.0, 400, 0.20, noiseMix = 0.35),
        SeSpec("爆発", Wave.NOISE, 200.0, 40.0, 900, 0.35),
        SeSpec("レーザー", Wave.SAW, 1800.0, 200.0, 260, 0.10, vibratoHz = 40.0, vibratoDepth = 0.05)
    )

    val names: Array<String> get() = all.map { it.name }.toTypedArray()
}

object SeRenderer {

    fun render(spec: SeSpec): ShortArray {
        val sr = SynthEngine.SAMPLE_RATE
        val total = (sr.toLong() * spec.durationMs / 1000L).toInt().coerceIn(64, sr * 5)
        val out = ShortArray(total)
        val fadeIn = (sr * 0.002).toInt().coerceAtLeast(1)
        val fadeOut = (sr * 0.008).toInt().coerceAtLeast(1)
        val ratio = if (spec.startFreq > 0.0 && spec.endFreq > 0.0) {
            ln(spec.endFreq / spec.startFreq)
        } else 0.0

        var phase = 0.0
        for (i in 0 until total) {
            val t = i.toDouble() / sr
            val progress = i.toDouble() / total
            val q = if (spec.steps > 1) {
                floor(progress * spec.steps) / (spec.steps - 1).toDouble()
            } else progress
            var freq = spec.startFreq * exp(ratio * q.coerceIn(0.0, 1.0))
            if (spec.vibratoHz > 0.0) {
                freq *= 1.0 + spec.vibratoDepth * sin(2.0 * PI * spec.vibratoHz * t)
            }
            if (freq < 10.0) freq = 10.0
            phase += freq / sr
            if (phase >= 1.0) phase -= floor(phase)

            var value = Waveform.sample(spec.wave, phase, spec.duty)
            if (spec.noiseMix > 0.0) {
                value = value * (1.0 - spec.noiseMix) +
                    Waveform.sample(Wave.NOISE, phase) * spec.noiseMix
            }

            var env = exp(-t / spec.decayTau.coerceAtLeast(0.01))
            if (i < fadeIn) env *= i.toDouble() / fadeIn
            val remaining = total - i
            if (remaining < fadeOut) env *= remaining.toDouble() / fadeOut

            val s = (value * env * 0.72 * 32767.0).toInt().coerceIn(-32768, 32767)
            out[i] = s.toShort()
        }
        return out
    }

    /** 指定 MIDI ノートの高さへ移調したコピーを返す。基準は C4 (60)。 */
    fun transposed(spec: SeSpec, note: Int): SeSpec {
        val factor = Math.pow(2.0, (note - 60) / 12.0)
        return spec.copy(
            startFreq = spec.startFreq * factor,
            endFreq = spec.endFreq * factor
        )
    }
}

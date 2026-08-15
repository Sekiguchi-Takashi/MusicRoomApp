package com.appathy.musicroom.audio

import kotlin.math.sqrt

/**
 * YIN (de Cheveigné & Kawahara) による基本周波数推定。
 * 歌声の 80〜1000Hz を想定し、22050Hz / 窓1024 で動かす。
 */
class YinDetector(private val sampleRate: Int, bufferSize: Int) {

    private val half = bufferSize / 2
    private val yin = DoubleArray(half)

    var threshold = 0.15

    /**
     * 戻り値: [周波数Hz, 確からしさ0..1]。無声なら周波数 -1。
     * 確からしさは yin の谷の深さそのもの (1 - yin[tau]) を 0..1 に写したもので、
     * 閾値を通った時点で 0.85 以上になるため、呼び出し側の足切りは
     * この値ではなく RMS と周波数範囲で行うこと。
     */
    fun detect(buffer: FloatArray): DoubleArray {
        difference(buffer)
        cumulativeMeanNormalized()
        var tau = absoluteThreshold()
        if (tau < 0) return doubleArrayOf(-1.0, 0.0)
        tau = correctOctave(tau)
        val refined = parabolicInterpolation(tau)
        if (refined <= 0.0) return doubleArrayOf(-1.0, 0.0)
        val hz = sampleRate / refined
        val probability = (1.0 - yin[tau]).coerceIn(0.0, 1.0)
        return doubleArrayOf(hz, probability)
    }

    /**
     * オクターブ上に取り違える YIN の典型的な失敗を補正する。
     * 2倍・3倍の周期にも十分深い谷があるなら、そちらが本当の基本周期。
     * 歌声は倍音が強く、これがないと 1オクターブ上に張り付くことがある。
     */
    private fun correctOctave(tau: Int): Int {
        var best = tau
        for (multiple in 2..3) {
            val candidate = tau * multiple
            if (candidate >= half - 1) break
            val local = localMinimumNear(candidate) ?: continue
            // 谷が浅すぎなければ、より長い周期 (低い音) を採用する
            if (yin[local] < threshold * 1.25 && yin[local] < yin[best] * 1.4) {
                best = local
            }
        }
        return best
    }

    /** 指定位置の周辺 (±3) で最も深い点を探す。 */
    private fun localMinimumNear(center: Int): Int? {
        val from = (center - 3).coerceAtLeast(1)
        val to = (center + 3).coerceAtMost(half - 2)
        if (from > to) return null
        var best = from
        for (i in from..to) if (yin[i] < yin[best]) best = i
        return best
    }

    private fun difference(buffer: FloatArray) {
        for (tau in 0 until half) {
            var sum = 0.0
            for (i in 0 until half) {
                val delta = (buffer[i] - buffer[i + tau]).toDouble()
                sum += delta * delta
            }
            yin[tau] = sum
        }
    }

    private fun cumulativeMeanNormalized() {
        yin[0] = 1.0
        var runningSum = 0.0
        for (tau in 1 until half) {
            runningSum += yin[tau]
            yin[tau] = if (runningSum == 0.0) 1.0 else yin[tau] * tau / runningSum
        }
    }

    private fun absoluteThreshold(): Int {
        var tau = 2
        while (tau < half) {
            if (yin[tau] < threshold) {
                while (tau + 1 < half && yin[tau + 1] < yin[tau]) tau++
                return tau
            }
            tau++
        }
        return -1
    }

    private fun parabolicInterpolation(tau: Int): Double {
        if (tau <= 0 || tau >= half - 1) return tau.toDouble()
        val s0 = yin[tau - 1]
        val s1 = yin[tau]
        val s2 = yin[tau + 1]
        val denominator = 2.0 * (2.0 * s1 - s2 - s0)
        if (denominator == 0.0) return tau.toDouble()
        return tau + (s2 - s0) / denominator
    }

    companion object {
        fun rms(buffer: FloatArray): Double {
            var sum = 0.0
            buffer.forEach { sum += it * it }
            return sqrt(sum / buffer.size)
        }

        /** Hz を実数の MIDI ノート番号へ。 */
        fun midiOf(hz: Double): Double =
            if (hz <= 0.0) -1.0 else 69.0 + 12.0 * (Math.log(hz / 440.0) / Math.log(2.0))

        /** 最寄りの半音からのズレ (セント)。 */
        fun centsOff(midi: Double): Double {
            if (midi < 0) return 0.0
            return (midi - Math.round(midi)) * 100.0
        }
    }
}

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

    /** 戻り値: [周波数Hz, 確からしさ0..1]。無声なら周波数 -1。 */
    fun detect(buffer: FloatArray): DoubleArray {
        difference(buffer)
        cumulativeMeanNormalized()
        val tau = absoluteThreshold()
        if (tau < 0) return doubleArrayOf(-1.0, 0.0)
        val refined = parabolicInterpolation(tau)
        if (refined <= 0.0) return doubleArrayOf(-1.0, 0.0)
        val hz = sampleRate / refined
        val probability = (1.0 - yin[tau]).coerceIn(0.0, 1.0)
        return doubleArrayOf(hz, probability)
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

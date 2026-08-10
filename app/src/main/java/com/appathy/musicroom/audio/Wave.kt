package com.appathy.musicroom.audio

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.random.Random

enum class Wave(val label: String) {
    PIANO("ピアノ"),
    SINE("サイン"),
    SQUARE("矩形波 50%"),
    PULSE25("パルス 25%"),
    TRIANGLE("三角波"),
    SAW("ノコギリ波"),
    NOISE("ノイズ");

    companion object {
        val labels: Array<String> get() = Wave.values().map { it.label }.toTypedArray()
    }
}

object Waveform {

    /** phase は 0.0..1.0 */
    fun sample(wave: Wave, phase: Double, duty: Double = 0.5): Double = when (wave) {
        Wave.SINE -> sin(2.0 * PI * phase)
        Wave.SQUARE -> if (phase < 0.5) 1.0 else -1.0
        Wave.PULSE25 -> if (phase < duty) 1.0 else -1.0
        Wave.TRIANGLE -> 4.0 * abs(phase - 0.5) - 1.0
        Wave.SAW -> 2.0 * phase - 1.0
        Wave.NOISE -> Random.nextDouble(-1.0, 1.0)
        Wave.PIANO -> {
            val a = sin(2.0 * PI * phase)
            val b = sin(4.0 * PI * phase)
            val c = sin(6.0 * PI * phase)
            (a + 0.45 * b + 0.18 * c) * 0.62
        }
    }

    /** 減衰系 (打鍵して離すと消える) か、持続系 (押している間鳴り続ける) か。 */
    fun isDecaying(wave: Wave): Boolean = wave == Wave.PIANO || wave == Wave.NOISE
}

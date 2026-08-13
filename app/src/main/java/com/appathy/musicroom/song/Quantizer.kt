package com.appathy.musicroom.song

import kotlin.math.abs
import kotlin.math.roundToInt

/** 録音1打鍵ぶんの生データ (RecordActivity のテイクと同じ形)。 */
data class RawNote(val startMs: Long, val endMs: Long, val pitch: Int, val velocity: Int)

data class QuantizeResult(
    val notes: List<SongNote>,
    val bpm: Int,
    val meanShiftMs: Double,
    val droppedCount: Int
)

/**
 * 設計書 §11 の「弾いて作る」経路。
 * 自由に弾いた録音を拍グリッドへ吸着させ、譜面 (SongNote) に変換する。
 */
object Quantizer {

    /** グリッド分割: 1=4分, 2=8分, 4=16分, 3=3連 */
    const val GRID_QUARTER = 1
    const val GRID_EIGHTH = 2
    const val GRID_SIXTEENTH = 4
    const val GRID_TRIPLET = 3

    /**
     * 打鍵間隔から BPM を推定する。
     * 最頻の間隔を1拍とみなし、60〜200 の範囲に折り返す。
     */
    fun estimateBpm(raw: List<RawNote>): Int {
        if (raw.size < 3) return 100
        val intervals = raw.sortedBy { it.startMs }
            .zipWithNext { a, b -> (b.startMs - a.startMs).toDouble() }
            .filter { it > 90.0 }
        if (intervals.isEmpty()) return 100

        // 最小間隔の整数倍でヒストグラムを作らず、中央値を基準にする
        val sorted = intervals.sorted()
        var unit = sorted[sorted.size / 2]
        // 中央値が8分や16分なら4分へ引き上げる
        while (unit < 300.0) unit *= 2.0
        while (unit > 1200.0) unit /= 2.0
        return (60_000.0 / unit).roundToInt().coerceIn(40, 200)
    }

    /**
     * グリッドへ吸着させる。
     * 最短音符より短い音、および同一拍位置の重複は落とす (単旋律として扱う)。
     */
    fun quantize(
        raw: List<RawNote>,
        bpm: Int,
        grid: Int = GRID_EIGHTH,
        beatsPerBar: Int = 4,
        polyphonic: Boolean = false
    ): QuantizeResult {
        if (raw.isEmpty()) return QuantizeResult(emptyList(), bpm, 0.0, 0)

        val msPerBeat = 60_000.0 / bpm
        val stepBeats = 1.0 / grid
        val sorted = raw.sortedBy { it.startMs }
        val origin = sorted.first().startMs

        val shifts = ArrayList<Double>()
        val staged = ArrayList<SongNote>()
        var dropped = 0

        sorted.forEach { note ->
            val rawBeat = (note.startMs - origin) / msPerBeat
            val snapped = (rawBeat / stepBeats).roundToInt() * stepBeats
            shifts.add((snapped - rawBeat) * msPerBeat)

            val rawLength = (note.endMs - note.startMs).coerceAtLeast(1L) / msPerBeat
            var length = (rawLength / stepBeats).roundToInt() * stepBeats
            if (length < stepBeats) length = stepBeats

            staged.add(SongNote(snapped, length, note.pitch))
        }

        val trimmed: List<SongNote>
        if (polyphonic) {
            // 和音トラック: 同じ拍位置でも音高が違えば残す。完全な重複だけ落とす。
            val unique = staged.distinctBy { Pair(it.beat, it.pitch) }
            dropped += staged.size - unique.size
            // 同じ音高どうしでだけ食い込みを切り詰める
            trimmed = unique.groupBy { it.pitch }.flatMap { entry ->
                val line = entry.value.sortedBy { it.beat }
                line.mapIndexed { index, note ->
                    val next = line.getOrNull(index + 1)
                    if (next != null && note.beat + note.lengthBeats > next.beat) {
                        note.copy(lengthBeats = (next.beat - note.beat).coerceAtLeast(stepBeats))
                    } else {
                        note
                    }
                }
            }.sortedBy { it.beat }
        } else {
            // 単旋律: 同じ拍位置は最も高い音を残す
            val merged = staged.groupBy { it.beat }
                .map { entry ->
                    if (entry.value.size > 1) dropped += entry.value.size - 1
                    entry.value.maxByOrNull { it.pitch }!!
                }
                .sortedBy { it.beat }
            trimmed = merged.mapIndexed { index, note ->
                val next = merged.getOrNull(index + 1)
                if (next != null && note.beat + note.lengthBeats > next.beat) {
                    note.copy(lengthBeats = (next.beat - note.beat).coerceAtLeast(stepBeats))
                } else {
                    note
                }
            }
        }

        // 小節単位に切り上げて終端をそろえる
        val last = trimmed.lastOrNull()
        val endBeat = if (last == null) 0.0 else last.beat + last.lengthBeats
        val bars = kotlin.math.ceil(endBeat / beatsPerBar).toInt().coerceAtLeast(1)
        val limit = bars * beatsPerBar.toDouble()
        val clipped = trimmed.filter { it.beat < limit }
            .map {
                if (it.beat + it.lengthBeats > limit) it.copy(lengthBeats = limit - it.beat) else it
            }

        return QuantizeResult(
            notes = clipped,
            bpm = bpm,
            meanShiftMs = if (shifts.isEmpty()) 0.0 else shifts.map { abs(it) }.average(),
            droppedCount = dropped
        )
    }
}

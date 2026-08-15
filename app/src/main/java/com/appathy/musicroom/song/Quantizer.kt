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
     *
     * 中央値だけで決めると、8分と4分が混ざった演奏や、
     * 1音だけ長く伸ばした箇所に引きずられて倍・半分にずれる。
     * そこで中央値から得た大まかな候補の周辺を実際に走査し、
     * 「全打点をグリッドに吸着させたときの誤差が最も小さい BPM」を選ぶ。
     */
    fun estimateBpm(raw: List<RawNote>, grid: Int = GRID_EIGHTH): Int {
        if (raw.size < 3) return 100
        val starts = raw.map { it.startMs.toDouble() }.sorted()
        val intervals = starts.zipWithNext { a, b -> b - a }.filter { it > 90.0 }
        if (intervals.isEmpty()) return 100

        val sortedIntervals = intervals.sorted()
        var unit = sortedIntervals[sortedIntervals.size / 2]
        while (unit < 300.0) unit *= 2.0
        while (unit > 1200.0) unit /= 2.0
        val seed = (60_000.0 / unit).roundToInt().coerceIn(40, 200)

        // 種となる BPM とその倍・半分の周辺を走査する
        val candidates = LinkedHashSet<Int>()
        listOf(seed / 2, seed, seed * 2).forEach { center ->
            if (center < 30 || center > 260) return@forEach
            for (delta in -12..12) {
                val value = center + delta
                if (value in 40..200) candidates.add(value)
            }
        }
        if (candidates.isEmpty()) return seed

        val origin = starts.first()
        var bestBpm = seed
        var bestError = Double.MAX_VALUE
        candidates.forEach { candidate ->
            val step = 60_000.0 / candidate / grid
            var error = 0.0
            starts.forEach { start ->
                val position = (start - origin) / step
                val offset = position - Math.round(position)
                error += abs(offset) * step
            }
            val mean = error / starts.size
            // 同じ誤差なら遅い BPM を選ぶ (細かく刻みすぎる解を避ける)
            if (mean < bestError - 0.5) {
                bestError = mean
                bestBpm = candidate
            }
        }
        return bestBpm
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

package com.appathy.musicroom.song

import com.appathy.musicroom.audio.MusicTheory

data class BarChord(val bar: Int, val rootPc: Int, val type: MusicTheory.ChordType, val score: Double) {
    val label: String get() = MusicTheory.pitchClassName(rootPc) + type.symbol
}

/**
 * 単旋律に和声をつける (§11 の延長)。
 * 小節ごとに構成音を長さで重み付けして集計し、
 * ダイアトニックな三和音のうち最も合うものを選ぶ。
 */
object Harmonizer {

    /** 伴奏の形。 */
    enum class Style(val label: String) {
        BLOCK("ブロック (全音符)"),
        ARPEGGIO("アルペジオ (4分)"),
        BASS("ベースのみ")
    }

    /** Cメジャーのダイアトニック和音 (度数, 三和音の種類)。 */
    private fun candidates(): List<Pair<Int, MusicTheory.ChordType>> {
        val major = MusicTheory.chordTypes.first { it.symbol == "" }
        val minor = MusicTheory.chordTypes.first { it.symbol == "m" }
        val dim = MusicTheory.chordTypes.first { it.symbol == "dim" }
        return listOf(
            Pair(0, major),   // I
            Pair(2, minor),   // ii
            Pair(4, minor),   // iii
            Pair(5, major),   // IV
            Pair(7, major),   // V
            Pair(9, minor),   // vi
            Pair(11, dim)     // vii°
        )
    }

    /**
     * 小節ごとのコードを推定する。
     * 該当小節に音がない場合は直前のコードを引き継ぐ。
     */
    fun analyze(notes: List<SongNote>, beatsPerBar: Int, bars: Int): List<BarChord> {
        val out = ArrayList<BarChord>()
        var previous: BarChord? = null

        for (bar in 0 until bars) {
            val start = bar * beatsPerBar.toDouble()
            val end = start + beatsPerBar
            val weights = DoubleArray(12)
            notes.forEach { note ->
                val noteEnd = note.beat + note.lengthBeats
                val overlap = minOf(noteEnd, end) - maxOf(note.beat, start)
                if (overlap > 0.0) {
                    // 小節頭に近い音を重く見る (和声の支配音になりやすい)
                    val positionBonus = if (note.beat <= start + 0.01) 1.5 else 1.0
                    weights[MusicTheory.pitchClass(note.pitch)] += overlap * positionBonus
                }
            }
            val total = weights.sum()
            if (total <= 0.0) {
                val carried = previous
                if (carried != null) out.add(carried.copy(bar = bar, score = 0.0))
                else out.add(BarChord(bar, 0, candidates()[0].second, 0.0))
                continue
            }

            var bestRoot = 0
            var bestType = candidates()[0].second
            var bestScore = -1.0
            candidates().forEach { (root, type) ->
                val tones = MusicTheory.chordPitchClasses(root, type)
                var matched = 0.0
                for (pc in 0 until 12) {
                    if (weights[pc] <= 0.0) continue
                    matched += if (pc in tones) weights[pc] else -weights[pc] * 0.6
                }
                // 根音が鳴っていれば加点
                if (weights[MusicTheory.pitchClass(root)] > 0.0) matched += total * 0.12
                // I と V は機能上出やすいので僅かに優先
                if (root == 0 || root == 7) matched += total * 0.05
                if (matched > bestScore) {
                    bestScore = matched
                    bestRoot = root
                    bestType = type
                }
            }
            val chord = BarChord(bar, bestRoot, bestType, bestScore / total)
            out.add(chord)
            previous = chord
        }
        return out
    }

    /**
     * 推定したコードから伴奏トラックを作る。
     * メロディの最低音より下に置き、濁らないようにする。
     */
    fun accompaniment(
        chords: List<BarChord>,
        beatsPerBar: Int,
        style: Style,
        melodyLowPitch: Int
    ): List<SongNote> {
        val out = ArrayList<SongNote>()
        // 伴奏の基準オクターブ: メロディ最低音の1オクターブ下あたり
        var base = 48
        while (base + 12 > melodyLowPitch - 2 && base > 24) base -= 12
        while (base + 24 < melodyLowPitch - 14) base += 12

        chords.forEach { chord ->
            val start = chord.bar * beatsPerBar.toDouble()
            val root = base + chord.rootPc
            val tones = chord.type.intervals.map { root + it }
            when (style) {
                Style.BLOCK -> tones.forEach { pitch ->
                    out.add(SongNote(start, beatsPerBar.toDouble(), pitch))
                }
                Style.BASS -> {
                    out.add(SongNote(start, beatsPerBar / 2.0, root))
                    out.add(SongNote(start + beatsPerBar / 2.0, beatsPerBar / 2.0, root + 12))
                }
                Style.ARPEGGIO -> {
                    val pattern = ArrayList<Int>()
                    for (i in 0 until beatsPerBar) {
                        pattern.add(tones[i % tones.size] + if (i >= tones.size) 12 else 0)
                    }
                    pattern.forEachIndexed { index, pitch ->
                        out.add(SongNote(start + index, 1.0, pitch))
                    }
                }
            }
        }
        return out.sortedBy { it.beat }
    }
}

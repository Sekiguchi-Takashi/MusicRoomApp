package com.appathy.musicroom.game

import com.appathy.musicroom.audio.MusicTheory
import kotlin.random.Random

class NoteItem(val timeMs: Double, val lane: Int) {
    var judged = false
    var judgement: Judgement? = null
    var errorMs = 0.0
}

object ChartGenerator {

    const val LANES = 7

    /** レーン番号 → MIDIノート (C4 メジャースケール) */
    fun noteOfLane(lane: Int): Int = 60 + MusicTheory.majorScale[lane.coerceIn(0, LANES - 1)]

    /** MIDIノート → レーン番号。スケール外は -1。 */
    fun laneOfNote(note: Int): Int {
        val pc = MusicTheory.pitchClass(note)
        val index = MusicTheory.majorScale.indexOf(pc)
        return index
    }

    /**
     * 8分音符グリッド上に譜面を生成する。
     * density は 0.0..1.0 で、グリッド上に音を置く確率。
     */
    fun generate(bpm: Int, bars: Int, density: Double, seed: Long = System.currentTimeMillis()): List<NoteItem> {
        val random = Random(seed)
        val stepMs = 60_000.0 / bpm / 2.0
        val steps = bars * 8
        val notes = ArrayList<NoteItem>()
        var lane = 3
        for (i in 0 until steps) {
            val onBeat = i % 2 == 0
            val chance = if (onBeat) density else density * 0.45
            if (random.nextDouble() > chance) continue
            val move = random.nextInt(-2, 3)
            lane = (lane + move).coerceIn(0, LANES - 1)
            notes.add(NoteItem(2000.0 + i * stepMs, lane))
        }
        if (notes.isEmpty()) notes.add(NoteItem(2000.0, 3))
        return notes
    }
}

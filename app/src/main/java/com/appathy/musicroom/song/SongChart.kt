package com.appathy.musicroom.song

import com.appathy.musicroom.game.Judgement

/** 実時間に展開した1音。 */
class PlayNote(
    val timeMs: Double,
    val durationMs: Double,
    val pitch: Int,
    val measure: Int
) {
    var judged = false
    var judgement: Judgement? = null
    var errorMs = 0.0
}

class SongChart(
    val song: Song,
    val bpm: Int,
    val notes: List<PlayNote>,
    val barStartMs: List<Double>,
    val measureNumbers: List<Int>,
    val endMs: Double,
    /** 伴奏 (判定しない。再生のみ)。 */
    val backing: List<PlayNote> = emptyList()
) {
    val pitches: List<Int> = notes.map { it.pitch }.distinct().sorted()

    /** 誤って弾かれた音を小節ごとに数える。 */
    val wrongNotes = HashMap<Int, Int>()

    fun measureAt(timeMs: Double): Int {
        var index = 0
        for (i in barStartMs.indices) {
            if (timeMs >= barStartMs[i]) index = i
        }
        return measureNumbers.getOrElse(index) { measureNumbers.firstOrNull() ?: 0 }
    }
}

object ChartBuilder {

    const val LEAD_IN_BEATS = 4.0

    /**
     * 曲を実時間チャートへ展開する。
     * measures を渡すとその小節だけを repeats 回つないだ部分練習チャートになる。
     */
    fun build(song: Song, bpm: Int, measures: IntRange? = null, repeats: Int = 1): SongChart {
        val msPerBeat = 60_000.0 / bpm
        val leadIn = LEAD_IN_BEATS * msPerBeat
        val range = measures ?: 0 until song.barCount
        val barBeats = song.beatsPerBar
        val selected = song.notes.filter { (it.beat / barBeats).toInt() in range }
        val firstBeat = range.first.toDouble() * barBeats
        val spanBeats = (range.last - range.first + 1).toDouble() * barBeats

        val out = ArrayList<PlayNote>()
        val backing = ArrayList<PlayNote>()
        val barStarts = ArrayList<Double>()
        val numbers = ArrayList<Int>()
        val selectedBacking = song.accompaniment.filter { (it.beat / barBeats).toInt() in range }

        for (rep in 0 until repeats) {
            val repOffset = rep * spanBeats * msPerBeat
            for (m in range) {
                barStarts.add(leadIn + repOffset + (m * barBeats - firstBeat) * msPerBeat)
                numbers.add(m)
            }
            selected.forEach { n ->
                val measure = (n.beat / barBeats).toInt()
                out.add(
                    PlayNote(
                        timeMs = leadIn + repOffset + (n.beat - firstBeat) * msPerBeat,
                        durationMs = n.lengthBeats * msPerBeat,
                        pitch = n.pitch,
                        measure = measure
                    )
                )
            }
            selectedBacking.forEach { n ->
                backing.add(
                    PlayNote(
                        timeMs = leadIn + repOffset + (n.beat - firstBeat) * msPerBeat,
                        durationMs = n.lengthBeats * msPerBeat,
                        pitch = n.pitch,
                        measure = (n.beat / barBeats).toInt()
                    )
                )
            }
        }
        out.sortBy { it.timeMs }
        backing.sortBy { it.timeMs }
        val end = (out.maxOfOrNull { it.timeMs + it.durationMs } ?: leadIn) + msPerBeat
        return SongChart(song, bpm, out, barStarts, numbers, end, backing)
    }
}

data class MeasureStat(
    val measure: Int,
    val total: Int,
    val perfect: Int,
    val great: Int,
    val good: Int,
    val miss: Int,
    val wrong: Int,
    val meanErrorMs: Double
) {
    val accuracy: Double
        get() = if (total == 0) 1.0 else
            ((perfect * 1.0 + great * 0.7 + good * 0.4) / total - wrong * 0.1).coerceIn(0.0, 1.0)
}

object SongEvaluator {

    fun evaluate(chart: SongChart): List<MeasureStat> {
        val byMeasure = chart.notes.groupBy { it.measure }
        return byMeasure.keys.sorted().map { measure ->
            val notes = byMeasure[measure] ?: emptyList()
            val errors = notes.filter { it.judgement != null && it.judgement != Judgement.MISS }
                .map { it.errorMs }
            MeasureStat(
                measure = measure,
                total = notes.size,
                perfect = notes.count { it.judgement == Judgement.PERFECT },
                great = notes.count { it.judgement == Judgement.GREAT },
                good = notes.count { it.judgement == Judgement.GOOD },
                miss = notes.count { it.judgement == Judgement.MISS || it.judgement == null },
                wrong = chart.wrongNotes[measure] ?: 0,
                meanErrorMs = if (errors.isEmpty()) 0.0 else errors.average()
            )
        }
    }

    /** 苦手小節 (§43)。正確度が低い順に最大 count 件。 */
    fun weakMeasures(stats: List<MeasureStat>, count: Int = 2): List<MeasureStat> =
        stats.filter { it.accuracy < 0.9 }.sortedBy { it.accuracy }.take(count)

    fun comment(stat: MeasureStat): String = when {
        stat.wrong > stat.total / 2 -> "違う音を押しています。まず音そのものを確認しましょう。"
        stat.miss > 0 && stat.miss >= stat.total / 2 -> "音が抜けています。テンポを落として確実に押すところから。"
        stat.meanErrorMs > 45 -> "遅れぎみです。次の音を先に構えておくと合いやすくなります。"
        stat.meanErrorMs < -45 -> "走りぎみです。拍を数えながら弾いてみてください。"
        else -> "細かいズレが残っています。同じ小節を繰り返して安定させましょう。"
    }
}

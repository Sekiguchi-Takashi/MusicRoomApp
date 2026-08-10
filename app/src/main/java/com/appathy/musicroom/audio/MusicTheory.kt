package com.appathy.musicroom.audio

object MusicTheory {

    val pitchClassNames = arrayOf("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B")

    /** メジャースケールの度数 (半音) */
    val majorScale = intArrayOf(0, 2, 4, 5, 7, 9, 11)

    data class ChordType(val label: String, val symbol: String, val intervals: List<Int>)

    val chordTypes = listOf(
        ChordType("メジャー", "", listOf(0, 4, 7)),
        ChordType("マイナー", "m", listOf(0, 3, 7)),
        ChordType("サスフォー", "sus4", listOf(0, 5, 7)),
        ChordType("セブンス", "7", listOf(0, 4, 7, 10)),
        ChordType("マイナーセブンス", "m7", listOf(0, 3, 7, 10)),
        ChordType("メジャーセブンス", "M7", listOf(0, 4, 7, 11)),
        ChordType("ディミニッシュ", "dim", listOf(0, 3, 6))
    )

    fun pitchClass(note: Int): Int = ((note % 12) + 12) % 12

    fun pitchClassName(pc: Int): String = pitchClassNames[pitchClass(pc)]

    fun chordLabel(rootPc: Int, type: ChordType): String =
        pitchClassName(rootPc) + type.symbol + " (" + pitchClassName(rootPc) + type.label + ")"

    fun chordPitchClasses(rootPc: Int, type: ChordType): Set<Int> =
        type.intervals.map { pitchClass(rootPc + it) }.toSet()

    /** 度数差から音程名を返す。 */
    fun intervalName(semitones: Int): String = when (((semitones % 12) + 12) % 12) {
        0 -> "完全1度"
        1 -> "短2度"
        2 -> "長2度"
        3 -> "短3度"
        4 -> "長3度"
        5 -> "完全4度"
        6 -> "増4度"
        7 -> "完全5度"
        8 -> "短6度"
        9 -> "長6度"
        10 -> "短7度"
        else -> "長7度"
    }
}

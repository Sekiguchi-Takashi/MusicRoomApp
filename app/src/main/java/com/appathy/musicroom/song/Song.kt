package com.appathy.musicroom.song

/** 拍位置と長さ (拍) で表した譜面上の音。 */
data class SongNote(val beat: Double, val lengthBeats: Double, val pitch: Int)

data class Song(
    val title: String,
    val defaultBpm: Int,
    val beatsPerBar: Int,
    val notes: List<SongNote>,
    /** 伴奏トラック。判定対象にはならず、再生のみに使う。 */
    val accompaniment: List<SongNote> = emptyList()
) {
    val barCount: Int
        get() = notes.maxOfOrNull { ((it.beat + it.lengthBeats - 0.001) / beatsPerBar).toInt() + 1 } ?: 0

    fun pitches(): List<Int> = notes.map { it.pitch }.distinct().sorted()
}

object SongLibrary {

    /** 内蔵曲 + 自作曲。自作曲は「★」を頭につけて区別する。 */
    fun all(context: android.content.Context): List<Song> {
        val user = try {
            com.appathy.musicroom.data.UserSongStore.all(context)
                .filter { it.notes.isNotEmpty() }
                .map { it.toSong().copy(title = "★" + it.title) }
        } catch (e: Exception) {
            emptyList()
        }
        return songs + user
    }

    fun titlesOf(list: List<Song>): Array<String> = list.map { it.title }.toTypedArray()


    private const val C4 = 60
    private const val D4 = 62
    private const val E4 = 64
    private const val F4 = 65
    private const val G4 = 67
    private const val A4 = 69

    /** 4分音符を並べるヘルパ。null は休符。 */
    private fun line(startBeat: Double, vararg pitches: Int?): List<SongNote> {
        val out = ArrayList<SongNote>()
        pitches.forEachIndexed { i, p ->
            if (p != null) out.add(SongNote(startBeat + i, 1.0, p))
        }
        return out
    }

    private val twinkle: List<SongNote> =
        line(0.0, C4, C4, G4, G4) +
            listOf(SongNote(4.0, 1.0, A4), SongNote(5.0, 1.0, A4), SongNote(6.0, 2.0, G4)) +
            line(8.0, F4, F4, E4, E4) +
            listOf(SongNote(12.0, 1.0, D4), SongNote(13.0, 1.0, D4), SongNote(14.0, 2.0, C4)) +
            line(16.0, G4, G4, F4, F4) +
            listOf(SongNote(20.0, 1.0, E4), SongNote(21.0, 1.0, E4), SongNote(22.0, 2.0, D4)) +
            line(24.0, G4, G4, F4, F4) +
            listOf(SongNote(28.0, 1.0, E4), SongNote(29.0, 1.0, E4), SongNote(30.0, 2.0, D4))

    private val frog: List<SongNote> =
        line(0.0, C4, D4, E4, F4) +
            listOf(SongNote(4.0, 1.0, E4), SongNote(5.0, 1.0, D4), SongNote(6.0, 2.0, C4)) +
            line(8.0, E4, F4, G4, A4) +
            listOf(SongNote(12.0, 1.0, G4), SongNote(13.0, 1.0, F4), SongNote(14.0, 2.0, E4))

    private val mary: List<SongNote> =
        line(0.0, E4, D4, C4, D4) +
            listOf(SongNote(4.0, 1.0, E4), SongNote(5.0, 1.0, E4), SongNote(6.0, 2.0, E4)) +
            listOf(SongNote(8.0, 1.0, D4), SongNote(9.0, 1.0, D4), SongNote(10.0, 2.0, D4)) +
            listOf(SongNote(12.0, 1.0, E4), SongNote(13.0, 1.0, G4), SongNote(14.0, 2.0, G4)) +
            line(16.0, E4, D4, C4, D4) +
            line(20.0, E4, E4, E4, E4) +
            listOf(SongNote(24.0, 1.0, D4), SongNote(25.0, 1.0, D4), SongNote(26.0, 1.0, E4), SongNote(27.0, 1.0, D4)) +
            listOf(SongNote(28.0, 4.0, C4))

    private val joy: List<SongNote> =
        line(0.0, E4, E4, F4, G4) +
            line(4.0, G4, F4, E4, D4) +
            line(8.0, C4, C4, D4, E4) +
            listOf(SongNote(12.0, 2.0, E4), SongNote(14.0, 2.0, D4)) +
            line(16.0, E4, E4, F4, G4) +
            line(20.0, G4, F4, E4, D4) +
            line(24.0, C4, C4, D4, E4) +
            listOf(SongNote(28.0, 2.0, D4), SongNote(30.0, 2.0, C4))

    val songs: List<Song> = listOf(
        Song("かえるのうた", 92, 4, frog),
        Song("きらきら星", 96, 4, twinkle),
        Song("メリーさんのひつじ", 100, 4, mary),
        Song("歓喜の歌", 104, 4, joy)
    )

    val titles: Array<String> get() = songs.map { it.title }.toTypedArray()
}

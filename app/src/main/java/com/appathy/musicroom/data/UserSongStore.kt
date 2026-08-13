package com.appathy.musicroom.data

import android.content.Context
import com.appathy.musicroom.song.Song
import com.appathy.musicroom.song.SongNote
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class UserSong(
    val id: String,
    val title: String,
    val bpm: Int,
    val beatsPerBar: Int,
    val lyrics: String,
    val updatedAt: Long,
    val notes: List<SongNote>,
    val accompaniment: List<SongNote> = emptyList()
) {
    fun toSong(): Song = Song(title, bpm, beatsPerBar, notes, accompaniment)
}

/**
 * 自作曲は曲数が少なく構造が入れ子なので、SQLite ではなく
 * 1曲1ファイルの JSON で持つ。エクスポートもこのまま出せる。
 */
object UserSongStore {

    private const val DIR = "songs"

    private fun dir(context: Context): File =
        File(context.filesDir, DIR).also { if (!it.exists()) it.mkdirs() }

    fun newId(): String = "song_" + System.currentTimeMillis()

    fun save(context: Context, song: UserSong): Boolean = try {
        val json = JSONObject()
        json.put("id", song.id)
        json.put("title", song.title)
        json.put("bpm", song.bpm)
        json.put("beatsPerBar", song.beatsPerBar)
        json.put("lyrics", song.lyrics)
        json.put("updatedAt", song.updatedAt)
        val array = JSONArray()
        song.notes.forEach { note ->
            val item = JSONObject()
            item.put("beat", note.beat)
            item.put("length", note.lengthBeats)
            item.put("pitch", note.pitch)
            array.put(item)
        }
        json.put("notes", array)
        json.put("accompaniment", toArray(song.accompaniment))
        File(dir(context), song.id + ".json").writeText(json.toString())
        true
    } catch (e: Exception) {
        false
    }

    fun load(context: Context, id: String): UserSong? = try {
        parse(File(dir(context), id + ".json").readText())
    } catch (e: Exception) {
        null
    }

    fun all(context: Context): List<UserSong> {
        val files = dir(context).listFiles() ?: return emptyList()
        return files.mapNotNull { file ->
            try {
                parse(file.readText())
            } catch (e: Exception) {
                null
            }
        }.sortedByDescending { it.updatedAt }
    }

    fun delete(context: Context, id: String): Boolean =
        File(dir(context), id + ".json").delete()

    fun exportJson(song: UserSong): String {
        val json = JSONObject()
        json.put("title", song.title)
        json.put("bpm", song.bpm)
        json.put("beatsPerBar", song.beatsPerBar)
        json.put("lyrics", song.lyrics)
        val array = JSONArray()
        song.notes.forEach { note ->
            array.put(
                JSONObject()
                    .put("beat", note.beat)
                    .put("length", note.lengthBeats)
                    .put("pitch", note.pitch)
            )
        }
        json.put("notes", array)
        json.put("accompaniment", toArray(song.accompaniment))
        return json.toString(2)
    }

    private fun toArray(notes: List<SongNote>): JSONArray {
        val array = JSONArray()
        notes.forEach { note ->
            array.put(
                JSONObject()
                    .put("beat", note.beat)
                    .put("length", note.lengthBeats)
                    .put("pitch", note.pitch)
            )
        }
        return array
    }

    private fun fromArray(array: JSONArray?): List<SongNote> {
        val out = ArrayList<SongNote>()
        if (array == null) return out
        for (i in 0 until array.length()) {
            val item = array.getJSONObject(i)
            out.add(
                SongNote(
                    item.getDouble("beat"),
                    item.getDouble("length"),
                    item.getInt("pitch")
                )
            )
        }
        return out
    }

    /** 共有された JSON テキストから曲を復元する。失敗時は null。 */
    fun importJson(text: String): UserSong? = try {
        val json = JSONObject(text)
        val notes = fromArray(json.getJSONArray("notes"))
        if (notes.isEmpty()) null
        else UserSong(
            id = newId(),
            title = json.optString("title", "取り込んだ曲"),
            bpm = json.optInt("bpm", 100),
            beatsPerBar = json.optInt("beatsPerBar", 4),
            lyrics = json.optString("lyrics", ""),
            updatedAt = System.currentTimeMillis(),
            notes = notes,
            accompaniment = fromArray(json.optJSONArray("accompaniment"))
        )
    } catch (e: Exception) {
        null
    }

    private fun parse(text: String): UserSong {
        val json = JSONObject(text)
        val array = json.getJSONArray("notes")
        val notes = ArrayList<SongNote>()
        for (i in 0 until array.length()) {
            val item = array.getJSONObject(i)
            notes.add(
                SongNote(
                    item.getDouble("beat"),
                    item.getDouble("length"),
                    item.getInt("pitch")
                )
            )
        }
        return UserSong(
            id = json.optString("id", newId()),
            title = json.optString("title", "無題"),
            bpm = json.optInt("bpm", 100),
            beatsPerBar = json.optInt("beatsPerBar", 4),
            lyrics = json.optString("lyrics", ""),
            updatedAt = json.optLong("updatedAt", System.currentTimeMillis()),
            notes = notes,
            accompaniment = fromArray(json.optJSONArray("accompaniment"))
        )
    }
}

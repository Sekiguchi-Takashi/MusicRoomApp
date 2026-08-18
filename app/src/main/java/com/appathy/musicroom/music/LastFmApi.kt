package com.appathy.musicroom.music

import android.content.Context
import android.os.Handler
import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.Executors

/** 検索結果の1件。曲とアーティストの両方をこの形で扱う。 */
data class MusicItem(
    val name: String,
    val artist: String,
    val listeners: Long,
    val url: String,
    val isArtist: Boolean
) {
    val display: String get() = if (isArtist) name else name + " / " + artist
}

/** 詳細情報。曲でもアーティストでも同じ形にそろえる。 */
data class MusicDetail(
    val title: String,
    val subtitle: String,
    val listeners: Long,
    val playcount: Long,
    val duration: Int,
    val album: String,
    val tags: List<String>,
    val summary: String,
    val related: List<String>,
    val relatedLabel: String,
    val url: String
)

/**
 * Last.fm Web Services 2.0 (AudioScrobbler API) のクライアント。
 * 読み取り系のメソッドは API キーだけで使える。共有シークレットは不要。
 * 通信は専用スレッドで行い、結果をメインスレッドへ返す。
 */
object LastFmApi {

    private const val ENDPOINT = "https://ws.audioscrobbler.com/2.0/"
    private const val PREFS = "lastfm"
    private const val KEY_API_KEY = "api_key"
    private const val USER_AGENT = "MusicRoomApp/1.0 (Android)"

    private val executor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())

    // ------------------------------------------------------------------- key

    fun apiKey(context: Context): String =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_API_KEY, "") ?: ""

    fun hasKey(context: Context): Boolean = apiKey(context).length >= 20

    fun saveApiKey(context: Context, key: String) {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_API_KEY, key.trim()).apply()
    }

    fun clearApiKey(context: Context) {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().remove(KEY_API_KEY).apply()
    }

    // --------------------------------------------------------------- request

    private fun call(
        context: Context,
        method: String,
        params: Map<String, String>
    ): JSONObject {
        val key = apiKey(context)
        if (key.isBlank()) throw LastFmException("APIキーが設定されていません。")

        val builder = StringBuilder(ENDPOINT)
        builder.append("?method=").append(method)
        builder.append("&api_key=").append(encode(key))
        builder.append("&format=json")
        params.forEach { (name, value) ->
            builder.append('&').append(name).append('=').append(encode(value))
        }

        val connection = URL(builder.toString()).openConnection() as HttpURLConnection
        connection.requestMethod = "GET"
        connection.connectTimeout = 10_000
        connection.readTimeout = 15_000
        connection.setRequestProperty("User-Agent", USER_AGENT)
        try {
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.bufferedReader()?.use(BufferedReader::readText)
                ?: throw LastFmException("応答が空でした。")
            val json = JSONObject(text)
            if (json.has("error")) {
                throw LastFmException(errorMessage(json.optInt("error"), json.optString("message")))
            }
            if (code !in 200..299) {
                throw LastFmException("通信に失敗しました (HTTP " + code + ")")
            }
            return json
        } finally {
            connection.disconnect()
        }
    }

    /** Last.fm が返すエラー番号を日本語にする。よく出るものだけ個別に説明する。 */
    private fun errorMessage(code: Int, raw: String): String = when (code) {
        6 -> "その名前では見つかりませんでした。"
        10 -> "APIキーが正しくありません。設定を確認してください。"
        26 -> "このAPIキーは停止されています。Last.fm に問い合わせてください。"
        29 -> "短時間に検索しすぎました。少し待ってからもう一度お試しください。"
        else -> if (raw.isBlank()) "エラーが発生しました (code " + code + ")" else raw
    }

    private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")

    // ------------------------------------------------------------ async wrap

    fun <T> run(task: () -> T, onSuccess: (T) -> Unit, onError: (String) -> Unit) {
        executor.execute {
            try {
                val result = task()
                mainHandler.post { onSuccess(result) }
            } catch (e: LastFmException) {
                mainHandler.post { onError(e.message ?: "エラーが発生しました。") }
            } catch (e: Exception) {
                mainHandler.post { onError("通信できませんでした。電波の状態を確認してください。") }
            }
        }
    }

    // --------------------------------------------------------------- methods

    /** 曲名で検索する (track.search)。 */
    fun searchTracks(context: Context, query: String, limit: Int = 30): List<MusicItem> {
        val json = call(context, "track.search", mapOf("track" to query, "limit" to limit.toString()))
        val matches = json.optJSONObject("results")
            ?.optJSONObject("trackmatches")
            ?.optJSONArray("track") ?: return emptyList()
        return items(matches) { item ->
            MusicItem(
                name = item.optString("name"),
                artist = item.optString("artist"),
                listeners = item.optString("listeners").toLongOrNull() ?: 0L,
                url = item.optString("url"),
                isArtist = false
            )
        }
    }

    /** アーティスト名で検索する (artist.search)。 */
    fun searchArtists(context: Context, query: String, limit: Int = 30): List<MusicItem> {
        val json = call(context, "artist.search", mapOf("artist" to query, "limit" to limit.toString()))
        val matches = json.optJSONObject("results")
            ?.optJSONObject("artistmatches")
            ?.optJSONArray("artist") ?: return emptyList()
        return items(matches) { item ->
            MusicItem(
                name = item.optString("name"),
                artist = item.optString("name"),
                listeners = item.optString("listeners").toLongOrNull() ?: 0L,
                url = item.optString("url"),
                isArtist = true
            )
        }
    }

    /** タグ (ジャンル) で人気曲を引く (tag.getTopTracks)。 */
    fun tracksByTag(context: Context, tag: String, limit: Int = 30): List<MusicItem> {
        val json = call(context, "tag.getTopTracks", mapOf("tag" to tag, "limit" to limit.toString()))
        val array = json.optJSONObject("tracks")?.optJSONArray("track") ?: return emptyList()
        return items(array) { item ->
            MusicItem(
                name = item.optString("name"),
                artist = item.optJSONObject("artist")?.optString("name") ?: "",
                listeners = 0L,
                url = item.optString("url"),
                isArtist = false
            )
        }
    }

    /** 世界の人気曲 (chart.getTopTracks)。 */
    fun chartTopTracks(context: Context, limit: Int = 30): List<MusicItem> {
        val json = call(context, "chart.getTopTracks", mapOf("limit" to limit.toString()))
        val array = json.optJSONObject("tracks")?.optJSONArray("track") ?: return emptyList()
        return items(array) { item ->
            MusicItem(
                name = item.optString("name"),
                artist = item.optJSONObject("artist")?.optString("name") ?: "",
                listeners = item.optString("listeners").toLongOrNull() ?: 0L,
                url = item.optString("url"),
                isArtist = false
            )
        }
    }

    /** 曲の詳細 (track.getInfo)。 */
    fun trackDetail(context: Context, track: String, artist: String): MusicDetail {
        val json = call(
            context, "track.getInfo",
            mapOf("track" to track, "artist" to artist, "autocorrect" to "1")
        )
        val item = json.optJSONObject("track") ?: throw LastFmException("曲の情報を取得できませんでした。")
        val artistName = item.optJSONObject("artist")?.optString("name") ?: artist
        return MusicDetail(
            title = item.optString("name", track),
            subtitle = artistName,
            listeners = item.optString("listeners").toLongOrNull() ?: 0L,
            playcount = item.optString("playcount").toLongOrNull() ?: 0L,
            duration = (item.optString("duration").toLongOrNull() ?: 0L).toInt() / 1000,
            album = item.optJSONObject("album")?.optString("title") ?: "",
            tags = tagNames(item.optJSONObject("toptags")?.optJSONArray("tag")),
            summary = plainText(item.optJSONObject("wiki")?.optString("summary") ?: ""),
            related = emptyList(),
            relatedLabel = "",
            url = item.optString("url")
        )
    }

    /** アーティストの詳細 (artist.getInfo)。似たアーティストも一緒に返る。 */
    fun artistDetail(context: Context, artist: String): MusicDetail {
        val json = call(context, "artist.getInfo", mapOf("artist" to artist, "autocorrect" to "1"))
        val item = json.optJSONObject("artist") ?: throw LastFmException("アーティスト情報を取得できませんでした。")
        val stats = item.optJSONObject("stats")
        val similar = ArrayList<String>()
        val similarArray = item.optJSONObject("similar")?.optJSONArray("artist")
        if (similarArray != null) {
            for (i in 0 until similarArray.length()) {
                similar.add(similarArray.optJSONObject(i)?.optString("name") ?: "")
            }
        }
        return MusicDetail(
            title = item.optString("name", artist),
            subtitle = "アーティスト",
            listeners = stats?.optString("listeners")?.toLongOrNull() ?: 0L,
            playcount = stats?.optString("playcount")?.toLongOrNull() ?: 0L,
            duration = 0,
            album = "",
            tags = tagNames(item.optJSONObject("tags")?.optJSONArray("tag")),
            summary = plainText(item.optJSONObject("bio")?.optString("summary") ?: ""),
            related = similar.filter { it.isNotBlank() },
            relatedLabel = "似ているアーティスト",
            url = item.optString("url")
        )
    }

    /** アーティストの代表曲 (artist.getTopTracks)。 */
    fun artistTopTracks(context: Context, artist: String, limit: Int = 15): List<MusicItem> {
        val json = call(
            context, "artist.getTopTracks",
            mapOf("artist" to artist, "limit" to limit.toString(), "autocorrect" to "1")
        )
        val array = json.optJSONObject("toptracks")?.optJSONArray("track") ?: return emptyList()
        return items(array) { item ->
            MusicItem(
                name = item.optString("name"),
                artist = item.optJSONObject("artist")?.optString("name") ?: artist,
                listeners = item.optString("listeners").toLongOrNull() ?: 0L,
                url = item.optString("url"),
                isArtist = false
            )
        }
    }

    // ----------------------------------------------------------------- utils

    /**
     * Last.fm は結果が1件のとき配列ではなくオブジェクトを返すことがある。
     * optJSONArray だけだと1件のときに空になるので、両方を扱う。
     */
    private fun <T> items(array: JSONArray, map: (JSONObject) -> T): List<T> {
        val out = ArrayList<T>()
        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: continue
            out.add(map(item))
        }
        return out
    }

    private fun tagNames(array: JSONArray?): List<String> {
        if (array == null) return emptyList()
        val out = ArrayList<String>()
        for (i in 0 until array.length()) {
            val name = array.optJSONObject(i)?.optString("name")
            if (!name.isNullOrBlank()) out.add(name)
        }
        return out
    }

    /** 解説文には HTML と末尾のリンクが混ざるので、表示用に落とす。 */
    private fun plainText(html: String): String {
        if (html.isBlank()) return ""
        var text = html.replace(Regex("<[^>]*>"), "")
        text = text.replace("&amp;", "&").replace("&quot;", "\"")
            .replace("&#39;", "'").replace("&lt;", "<").replace("&gt;", ">")
        val marker = text.indexOf("Read more on Last.fm")
        if (marker > 0) text = text.substring(0, marker)
        return text.trim()
    }
}

class LastFmException(message: String) : Exception(message)

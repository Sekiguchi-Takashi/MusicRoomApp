package com.appathy.musicroom.ui

import android.app.AlertDialog
import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.appathy.musicroom.R
import com.appathy.musicroom.music.LastFmApi
import com.appathy.musicroom.music.MusicDetail
import com.appathy.musicroom.music.MusicItem

class MusicSearchActivity : AppCompatActivity() {

    private val modes = arrayOf("曲を探す", "アーティストを探す", "ジャンル (タグ) で探す")

    private lateinit var spinnerMode: Spinner
    private lateinit var editQuery: EditText
    private lateinit var textStatus: TextView
    private lateinit var resultArea: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_music_search)

        spinnerMode = findViewById(R.id.spinnerMode)
        editQuery = findViewById(R.id.editQuery)
        textStatus = findViewById(R.id.textStatus)
        resultArea = findViewById(R.id.resultArea)

        spinnerMode.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, modes)

        findViewById<Button>(R.id.btnSearch).setOnClickListener { search() }
        findViewById<Button>(R.id.btnChart).setOnClickListener { chart() }
        findViewById<Button>(R.id.btnKey).setOnClickListener { showKeyDialog() }

        editQuery.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                search()
                true
            } else {
                false
            }
        }

        if (!LastFmApi.hasKey(this)) {
            textStatus.text = "はじめに [🔑 APIキー設定] からキーを登録してください。"
        }
    }

    // ---------------------------------------------------------------- search

    private fun requireKey(): Boolean {
        if (LastFmApi.hasKey(this)) return true
        textStatus.text = "APIキーが未設定です。[🔑 APIキー設定] を開いてください。"
        showKeyDialog()
        return false
    }

    private fun search() {
        if (!requireKey()) return
        val query = editQuery.text.toString().trim()
        if (query.isBlank()) {
            textStatus.text = "検索する言葉を入れてください。"
            return
        }
        val mode = spinnerMode.selectedItemPosition
        busy("検索しています...")
        LastFmApi.run(
            {
                when (mode) {
                    0 -> LastFmApi.searchTracks(this, query)
                    1 -> LastFmApi.searchArtists(this, query)
                    else -> LastFmApi.tracksByTag(this, query)
                }
            },
            { list -> showList(list, "「" + query + "」の検索結果") },
            { message -> fail(message) }
        )
    }

    private fun chart() {
        if (!requireKey()) return
        busy("人気の曲を取得しています...")
        LastFmApi.run(
            { LastFmApi.chartTopTracks(this) },
            { list -> showList(list, "いま世界でよく聴かれている曲") },
            { message -> fail(message) }
        )
    }

    private fun busy(message: String) {
        textStatus.text = message
        resultArea.removeAllViews()
    }

    private fun fail(message: String) {
        textStatus.text = message
        resultArea.removeAllViews()
    }

    private fun showList(list: List<MusicItem>, heading: String) {
        resultArea.removeAllViews()
        if (list.isEmpty()) {
            textStatus.text = "見つかりませんでした。つづりを変えて試してください。"
            return
        }
        textStatus.text = heading + "  " + list.size + "件"
        list.forEach { item ->
            val body = StringBuilder(item.display)
            if (item.listeners > 0) {
                body.append("\n聴いている人 ").append(formatCount(item.listeners)).append("人")
            }
            resultArea.addView(row(body.toString()) { openDetail(item) })
        }
    }

    // ---------------------------------------------------------------- detail

    private fun openDetail(item: MusicItem) {
        busy("読み込んでいます...")
        if (item.isArtist) {
            LastFmApi.run(
                {
                    val detail = LastFmApi.artistDetail(this, item.name)
                    val top = LastFmApi.artistTopTracks(this, item.name)
                    Pair(detail, top)
                },
                { pair -> showDetail(pair.first, pair.second) },
                { message -> fail(message) }
            )
        } else {
            LastFmApi.run(
                { LastFmApi.trackDetail(this, item.name, item.artist) },
                { detail -> showDetail(detail, emptyList()) },
                { message -> fail(message) }
            )
        }
    }

    private fun showDetail(detail: MusicDetail, topTracks: List<MusicItem>) {
        resultArea.removeAllViews()
        textStatus.text = detail.title

        val header = StringBuilder()
        header.append(detail.title).append("\n").append(detail.subtitle)
        if (detail.album.isNotBlank()) header.append("\nアルバム: ").append(detail.album)
        if (detail.duration > 0) {
            header.append("\n長さ: ").append(detail.duration / 60).append("分")
                .append(detail.duration % 60).append("秒")
        }
        if (detail.listeners > 0) {
            header.append("\n聴いている人: ").append(formatCount(detail.listeners)).append("人")
        }
        if (detail.playcount > 0) {
            header.append("\n再生回数: ").append(formatCount(detail.playcount)).append("回")
        }
        resultArea.addView(card(header.toString(), true))

        if (detail.tags.isNotEmpty()) {
            resultArea.addView(card("ジャンル: " + detail.tags.joinToString("、"), false))
        }
        if (detail.summary.isNotBlank()) {
            resultArea.addView(card(detail.summary, false))
        }
        if (detail.related.isNotEmpty()) {
            resultArea.addView(
                card(detail.relatedLabel + ": " + detail.related.joinToString("、"), false)
            )
        }
        topTracks.forEachIndexed { index, track ->
            if (index == 0) resultArea.addView(card("代表曲", false))
            resultArea.addView(row((index + 1).toString() + ". " + track.name) { openDetail(track) })
        }

        resultArea.addView(
            row("🎼 この曲名で作曲を始める") { startCompose(detail.title) }
        )
        if (detail.url.isNotBlank()) {
            resultArea.addView(row("🌐 Last.fm のページを開く") { openUrl(detail.url) })
        }
        resultArea.addView(row("← 検索に戻る") { search() })
    }

    private fun startCompose(title: String) {
        val intent = Intent(this, ComposeActivity::class.java)
        intent.putExtra(ComposeActivity.EXTRA_NEW_TITLE, title)
        startActivity(intent)
    }

    private fun openUrl(url: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (e: Exception) {
            Toast.makeText(this, "ブラウザを開けませんでした。", Toast.LENGTH_SHORT).show()
        }
    }

    // ------------------------------------------------------------------- key

    private fun showKeyDialog() {
        val view = layoutInflater.inflate(R.layout.dialog_lastfm_key, null)
        val editKey = view.findViewById<EditText>(R.id.editKey)
        val textKeyState = view.findViewById<TextView>(R.id.textKeyState)
        val current = LastFmApi.apiKey(this)
        if (current.isNotBlank()) {
            editKey.setText(current)
            textKeyState.text = "登録済みです。変更する場合は入れ替えてください。"
        }

        AlertDialog.Builder(this)
            .setTitle("Last.fm APIキー")
            .setView(view)
            .setNeutralButton("キーを消す") { _, _ ->
                LastFmApi.clearApiKey(this)
                textStatus.text = "APIキーを消しました。"
            }
            .setNegativeButton("やめる", null)
            .setPositiveButton("保存") { _, _ ->
                val key = editKey.text.toString().trim()
                if (key.length < 20) {
                    textStatus.text = "キーが短すぎます。貼り付け直してください。"
                    return@setPositiveButton
                }
                LastFmApi.saveApiKey(this, key)
                textStatus.text = "APIキーを保存しました。検索できます。"
            }
            .show()
    }

    // ----------------------------------------------------------------- views

    private fun formatCount(value: Long): String =
        String.format("%,d", value)

    private fun row(text: String, onClick: () -> Unit): View {
        val view = TextView(this).apply {
            this.text = text
            setTextColor(getColor(R.color.text_primary))
            textSize = 14f
            setBackgroundResource(R.drawable.bg_menu)
            setPadding(dp(14), dp(12), dp(14), dp(12))
            setLineSpacing(dp(3).toFloat(), 1f)
            setOnClickListener { onClick() }
        }
        val lp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )
        lp.bottomMargin = dp(6)
        view.layoutParams = lp
        return view
    }

    private fun card(text: String, bold: Boolean): View {
        val view = TextView(this).apply {
            this.text = text
            setTextColor(if (bold) getColor(R.color.accent_warm) else getColor(R.color.text_secondary))
            textSize = if (bold) 15f else 13f
            if (bold) setTypeface(typeface, Typeface.BOLD)
            setBackgroundResource(R.drawable.bg_card)
            setPadding(dp(14), dp(12), dp(14), dp(12))
            setLineSpacing(dp(3).toFloat(), 1f)
        }
        val lp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )
        lp.bottomMargin = dp(6)
        view.layoutParams = lp
        return view
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}

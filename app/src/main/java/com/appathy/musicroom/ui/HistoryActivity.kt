package com.appathy.musicroom.ui

import android.app.AlertDialog
import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.appathy.musicroom.R
import com.appathy.musicroom.data.Coach
import com.appathy.musicroom.data.Kind
import com.appathy.musicroom.data.PracticeDb
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class HistoryActivity : AppCompatActivity() {

    private lateinit var db: PracticeDb
    private lateinit var textSummary: TextView
    private lateinit var trendView: TrendView
    private lateinit var adviceArea: LinearLayout
    private lateinit var weakArea: LinearLayout
    private lateinit var sessionArea: LinearLayout

    private val stamp = SimpleDateFormat("MM/dd HH:mm", Locale.JAPAN)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_history)

        db = PracticeDb.get(this)
        textSummary = findViewById(R.id.textSummary)
        trendView = findViewById(R.id.trendView)
        adviceArea = findViewById(R.id.adviceArea)
        weakArea = findViewById(R.id.weakArea)
        sessionArea = findViewById(R.id.sessionArea)

        findViewById<Button>(R.id.btnClear).setOnClickListener { confirmClear() }
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    private fun render() {
        val sessions = db.sessions(limit = 30)
        val count = db.sessionCount()
        val items = db.totalItems()

        textSummary.text = if (count == 0) {
            "まだ記録がありません。楽曲練習やゲームを1回終えると、ここに残ります。"
        } else {
            "セッション " + count + " 回 / 判定した音 " + items + " 個\n" +
                "最後の練習 " + stamp.format(Date(sessions.first().timestamp))
        }

        val timed = sessions.filter { it.kind == Kind.SONG || it.kind == Kind.RHYTHM }
            .sortedBy { it.timestamp }
            .map { it.accuracy }
        trendView.values = timed

        adviceArea.removeAllViews()
        Coach.advise(db).forEach { advice ->
            adviceArea.addView(card(advice.title, advice.body))
        }

        weakArea.removeAllViews()
        val songs = db.practicedSongs()
        if (songs.isEmpty()) {
            weakArea.addView(hint("楽曲練習の記録がまだありません。"))
        } else {
            songs.take(3).forEach { title ->
                val weak = db.weakMeasures(title, 3).filter { it.accuracy < 0.95 }
                if (weak.isEmpty()) {
                    weakArea.addView(hint(title + " — 目立つ苦手小節はありません。"))
                } else {
                    weak.forEach { row ->
                        weakArea.addView(
                            card(
                                title + "  " + (row.measure + 1) + "小節目",
                                "正確度 " + (row.accuracy * 100).toInt() + "% / 平均ズレ " +
                                    row.meanErrorMs.toInt() + "ms / ミス " + row.miss + " 誤音 " + row.wrong,
                                Color.parseColor("#EF6461")
                            )
                        )
                    }
                }
            }
        }

        sessionArea.removeAllViews()
        if (sessions.isEmpty()) {
            sessionArea.addView(hint("—"))
        } else {
            sessions.take(12).forEach { session ->
                val detail = StringBuilder()
                detail.append(stamp.format(Date(session.timestamp)))
                detail.append("  ").append(Kind.label(session.kind))
                if (session.label.isNotBlank() && session.kind == Kind.SONG) {
                    detail.append(" / ").append(session.label)
                }
                if (session.bpm > 0) detail.append(" / BPM ").append(session.bpm)
                detail.append("\n正確度 ").append((session.accuracy * 100).toInt()).append("%")
                if (session.kind == Kind.SONG || session.kind == Kind.RHYTHM) {
                    detail.append(" / 平均ズレ ").append(session.meanErrorMs.toInt()).append("ms")
                }
                if (session.score > 0) detail.append(" / ").append(session.score).append("点")
                sessionArea.addView(hint(detail.toString()))
            }
        }
    }

    private fun card(title: String, body: String, titleColor: Int = getColor(R.color.accent_warm)): View {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.bg_card)
            setPadding(dp(16), dp(14), dp(16), dp(14))
        }
        container.addView(TextView(this).apply {
            text = title
            setTextColor(titleColor)
            textSize = 15f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        })
        container.addView(TextView(this).apply {
            text = body
            setTextColor(getColor(R.color.text_secondary))
            textSize = 13f
            setPadding(0, dp(4), 0, 0)
        })
        val lp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )
        lp.bottomMargin = dp(8)
        container.layoutParams = lp
        return container
    }

    private fun hint(text: String): View {
        val view = TextView(this).apply {
            this.text = text
            setTextColor(getColor(R.color.text_secondary))
            textSize = 12f
            setBackgroundResource(R.drawable.bg_card)
            setPadding(dp(14), dp(10), dp(14), dp(10))
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

    private fun confirmClear() {
        AlertDialog.Builder(this)
            .setTitle("記録の削除")
            .setMessage("これまでの練習記録をすべて削除します。元に戻せません。")
            .setNegativeButton("やめる", null)
            .setPositiveButton("削除する") { _, _ ->
                db.clearAll()
                render()
            }
            .show()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}

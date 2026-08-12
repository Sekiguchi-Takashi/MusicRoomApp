package com.appathy.musicroom.data

import kotlin.math.abs

data class Advice(val title: String, val body: String)

/**
 * 設計書 §36/§37/§44 の入口。
 * 蓄積したセッションから弱点を推定し、次に何を練習すべきかを返す。
 * 統計が足りないうちは断定せず「まだ判断材料が足りない」と言う。
 */
object Coach {

    fun advise(db: PracticeDb): List<Advice> {
        val out = ArrayList<Advice>()
        val sessions = db.sessions(limit = 60)
        if (sessions.size < 3) {
            out.add(
                Advice(
                    "まずは記録を貯めましょう",
                    "練習の傾向を出すには、あと " + (3 - sessions.size) + " 回ほど記録が必要です。" +
                        "楽曲練習かピアノリズムを1回通してみてください。"
                )
            )
            return out
        }

        // タイミングの癖
        val timed = sessions.filter { it.kind == Kind.SONG || it.kind == Kind.RHYTHM }
        if (timed.size >= 3) {
            val mean = timed.map { it.meanErrorMs }.average()
            when {
                mean > 30 -> out.add(
                    Advice(
                        "遅れる癖があります",
                        "直近の平均ズレは " + fmt(mean) + " ms。音を見てから押す反応になっています。" +
                            "メトロノームを鳴らしながら、次の音を先に構える練習が効きます。"
                    )
                )
                mean < -30 -> out.add(
                    Advice(
                        "走る癖があります",
                        "直近の平均ズレは " + fmt(mean) + " ms。拍より早く入っています。" +
                            "テンポを1段階落として、拍を数えながら弾いてみてください。"
                    )
                )
                else -> out.add(
                    Advice(
                        "タイミングは安定しています",
                        "平均ズレ " + fmt(mean) + " ms。偏りはほとんどありません。テンポを上げても崩れにくい状態です。"
                    )
                )
            }
        }

        // 曲ごとのテンポ調整
        db.practicedSongs().take(2).forEach { title ->
            val songSessions = sessions.filter { it.kind == Kind.SONG && it.label == title }
            if (songSessions.isEmpty()) return@forEach
            val recent = songSessions.take(3)
            val accuracy = recent.map { it.accuracy }.average()
            val bpm = recent.first().bpm
            when {
                accuracy >= 0.9 -> out.add(
                    Advice(
                        title + " — テンポを上げどきです",
                        "直近の正確度は " + pct(accuracy) + "。BPM " + bpm + " では余裕があります。" +
                            "1段階速いテンポで通してみてください。"
                    )
                )
                accuracy < 0.65 -> out.add(
                    Advice(
                        title + " — テンポを落としましょう",
                        "直近の正確度は " + pct(accuracy) + "。BPM " + bpm + " はまだ速すぎます。" +
                            "遅いテンポで音を確実にしてから戻すほうが早く仕上がります。"
                    )
                )
                else -> {
                    val weak = db.weakMeasures(title, 2).filter { it.accuracy < 0.85 }
                    if (weak.isNotEmpty()) {
                        out.add(
                            Advice(
                                title + " — " + weak.joinToString("、") { (it.measure + 1).toString() + "小節目" } + "が弱点です",
                                "この小節だけ正確度が " + pct(weak.first().accuracy) + " まで落ちています。" +
                                    "楽曲練習の結果画面でこの行をタップすると、その小節だけを4回くり返せます。"
                            )
                        )
                    }
                }
            }
        }

        // 耳とコード
        val ear = db.averages(Kind.EAR)
        if (ear != null && ear.first < 0.7) {
            out.add(
                Advice(
                    "音当ての正答率が低めです",
                    "正答率 " + pct(ear.first) + "。単音から始めて、慣れたら2音・音程に広げてください。" +
                        "耳が育つと譜面を追う負担が減ります。"
                )
            )
        }
        val chord = db.averages(Kind.CHORD)
        if (chord != null && chord.first < 0.7) {
            out.add(
                Advice(
                    "コードの反応が遅れています",
                    "正答率 " + pct(chord.first) + "。三和音だけに絞って、ルートから積む順番を体で覚えるのが近道です。"
                )
            )
        }

        // うた
        val sing = db.averages(Kind.SING)
        if (sing != null) {
            val cents = sing.second
            when {
                sing.first < 0.5 -> out.add(
                    Advice(
                        "うたの音程がまだ取れていません",
                        "正確度 " + pct(sing.first) + "。キーを1オクターブ下げ、ガイド音を鳴らしながら合わせてください。"
                    )
                )
                cents < -30 -> out.add(
                    Advice(
                        "うたがぶら下がりぎみです",
                        "平均 " + fmt(cents) + " セント低めです。息の支えが弱いときに出やすい癖です。"
                    )
                )
                cents > 30 -> out.add(
                    Advice(
                        "うたが上ずりぎみです",
                        "平均 " + fmt(cents) + " セント高めです。力みを抜くと収まります。"
                    )
                )
                sing.first >= 0.85 -> out.add(
                    Advice("うたは安定しています", "正確度 " + pct(sing.first) + "。キーを上げるか原速に戻してみましょう。")
                )
            }
        }

        // 連打
        val repeat = db.averages(Kind.REPEAT)
        if (repeat != null && repeat.first < 0.7) {
            out.add(
                Advice(
                    "打鍵の均一性が課題です",
                    "連打の安定度は平均 " + pct(repeat.first) + "。速さより粒をそろえることを優先しましょう。"
                )
            )
        }

        if (out.isEmpty()) {
            out.add(Advice("順調です", "目立つ弱点は見つかりませんでした。新しい曲か、速いテンポに進んでみてください。"))
        }
        return out
    }

    private fun pct(value: Double): String = (value * 100).toInt().toString() + "%"

    private fun fmt(value: Double): String =
        (if (value >= 0) "+" else "-") + abs(value).toInt().toString()
}

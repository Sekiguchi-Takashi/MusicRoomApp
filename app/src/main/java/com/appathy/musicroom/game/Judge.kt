package com.appathy.musicroom.game

/**
 * 設計書 §41 のタイミング推論をゲーム判定に落としたもの。
 * 閾値はテンポや難易度で調整できるよう可変にしてある。
 */
enum class Judgement(val label: String, val score: Int) {
    PERFECT("PERFECT", 100),
    GREAT("GREAT", 70),
    GOOD("GOOD", 40),
    MISS("MISS", 0)
}

object Judge {

    var perfectMs = 40.0
    var greatMs = 80.0
    var goodMs = 130.0
    var windowMs = 180.0

    fun of(errorMs: Double): Judgement {
        val e = kotlin.math.abs(errorMs)
        return when {
            e <= perfectMs -> Judgement.PERFECT
            e <= greatMs -> Judgement.GREAT
            e <= goodMs -> Judgement.GOOD
            else -> Judgement.MISS
        }
    }

    /** タイミングのズレ傾向 (§42)。正なら遅れ、負なら走り。 */
    fun tendency(errors: List<Double>): String {
        if (errors.size < 4) return "サンプルが少なく傾向は判定できません。"
        val mean = errors.average()
        val half = errors.size / 2
        val front = errors.take(half).average()
        val back = errors.drop(half).average()
        return when {
            mean > 25 && back > front + 15 -> "全体に遅れぎみで、後半ほど遅れが大きくなっています。テンポが落ちる癖があります。"
            mean < -25 && back < front - 15 -> "全体に走りぎみで、後半ほど早くなっています。テンポが上がる癖があります。"
            mean > 25 -> "全体に遅れぎみです。ノートを見てから押すのではなく、拍を先に感じてみてください。"
            mean < -25 -> "全体に走りぎみです。メトロノームを鳴らしながら弾くと安定します。"
            else -> "タイミングの偏りはほとんどありません。"
        }
    }

    fun rank(accuracy: Double): String = when {
        accuracy >= 0.95 -> "S"
        accuracy >= 0.90 -> "A"
        accuracy >= 0.80 -> "B"
        accuracy >= 0.65 -> "C"
        else -> "D"
    }
}

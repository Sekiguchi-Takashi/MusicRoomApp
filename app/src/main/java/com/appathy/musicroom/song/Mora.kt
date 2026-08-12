package com.appathy.musicroom.song

/**
 * 設計書 §12〜§14, §55〜§60 の歌詞制約。
 * 日本語のメロディでは「1音 = 1モーラ」が基本になるため、
 * 音数とモーラ数の一致を機械的に検査できる。
 */
object Mora {

    /** 直前のモーラに吸収される小書き仮名 (拗音)。 */
    private val small = setOf(
        'ゃ', 'ゅ', 'ょ', 'ャ', 'ュ', 'ョ',
        'ぁ', 'ぃ', 'ぅ', 'ぇ', 'ぉ', 'ァ', 'ィ', 'ゥ', 'ェ', 'ォ',
        'ゎ', 'ヮ'
    )

    /** それ自体で1モーラを占める特殊拍 (撥音・促音・長音)。 */
    private val special = setOf('ん', 'ン', 'っ', 'ッ', 'ー')

    private fun isKana(c: Char): Boolean =
        (c in '\u3041'..'\u3096') || (c in '\u30A1'..'\u30FA') || c == 'ー' || c == 'ｰ'

    private val punctuation = "、。,.!?！？「」『』（）()・… ".toSet()

    private fun isIgnorable(c: Char): Boolean = c.isWhitespace() || c in punctuation

    /** 文字列のモーラ数。仮名以外 (漢字・英数) は1文字1モーラとして数える。 */
    fun count(text: String): Int {
        var total = 0
        text.forEach { c ->
            if (isIgnorable(c)) return@forEach
            if (c in small) return@forEach
            total++
        }
        return total
    }

    /** 1モーラずつに分割する。拗音は直前とまとめる。 */
    fun split(text: String): List<String> {
        val out = ArrayList<String>()
        text.forEach { c ->
            if (isIgnorable(c)) return@forEach
            if (c in small && out.isNotEmpty()) {
                out[out.size - 1] = out[out.size - 1] + c
            } else {
                out.add(c.toString())
            }
        }
        return out
    }

    /** 仮名以外を含むか (漢字が残っていると正確に数えられない)。 */
    fun hasNonKana(text: String): Boolean =
        text.any { !isIgnorable(it) && !isKana(it) }

    data class Alignment(
        val pairs: List<Pair<String, SongNote?>>,
        val moraCount: Int,
        val noteCount: Int,
        val warning: String?
    ) {
        val fits: Boolean get() = moraCount == noteCount
    }

    /**
     * 歌詞をメロディへ割り付ける。
     * 音数と合わない場合も並べて返し、どこで足りない/余るかを見せる。
     */
    fun align(text: String, notes: List<SongNote>): Alignment {
        val moras = split(text)
        val sorted = notes.sortedBy { it.beat }
        val pairs = ArrayList<Pair<String, SongNote?>>()
        moras.forEachIndexed { index, mora ->
            pairs.add(Pair(mora, sorted.getOrNull(index)))
        }
        // 音のほうが多い場合は空欄として見せる
        if (sorted.size > moras.size) {
            for (i in moras.size until sorted.size) {
                pairs.add(Pair("－", sorted[i]))
            }
        }
        val warning = when {
            hasNonKana(text) ->
                "漢字や英数字が含まれています。読みをひらがなで書くと正確に数えられます。"
            moras.size > sorted.size ->
                "歌詞が " + (moras.size - sorted.size) + " モーラ多いです。音を足すか、言葉を詰めてください。"
            moras.size < sorted.size ->
                "音が " + (sorted.size - moras.size) + " 個余っています。言葉を伸ばすか、音を減らしてください。"
            else -> null
        }
        return Alignment(pairs, moras.size, sorted.size, warning)
    }

    /**
     * 長音で伸ばすべき箇所の提案 (§58)。
     * 音が長い (2拍以上) のに短いモーラが当たっている位置を返す。
     */
    fun sustainHints(alignment: Alignment): List<Int> {
        val out = ArrayList<Int>()
        alignment.pairs.forEachIndexed { index, pair ->
            val note = pair.second ?: return@forEachIndexed
            if (note.lengthBeats >= 2.0 && pair.first != "ー" && pair.first != "－") {
                out.add(index)
            }
        }
        return out
    }
}

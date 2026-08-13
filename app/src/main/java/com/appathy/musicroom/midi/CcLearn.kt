package com.appathy.musicroom.midi

import android.content.Context

/**
 * MiniLab 3 のノブ/フェーダーは機種・プリセットで CC 番号が変わるため、
 * 番号を決め打ちせず「最初に動かしたノブから順に役割を割り当てる」方式にする。
 * 割り当ては役割ごとに保存され、次回以降も同じノブが効く。
 */
class CcLearn(context: Context, private val scope: String, private val roles: List<String>) {

    private val prefs = context.applicationContext
        .getSharedPreferences("cc_map_" + scope, Context.MODE_PRIVATE)

    /** 役割 → CC番号。未割り当ては -1。 */
    fun ccOf(role: String): Int = prefs.getInt(role, -1)

    fun roleOf(cc: Int): String? = roles.firstOrNull { ccOf(it) == cc }

    fun isAssigned(cc: Int): Boolean = roleOf(cc) != null

    /** まだ役割のない CC が来たら、空いている役割へ順に割り当てる。割り当てた役割名を返す。 */
    fun learn(cc: Int): String? {
        if (isAssigned(cc)) return null
        val free = roles.firstOrNull { ccOf(it) < 0 } ?: return null
        prefs.edit().putInt(free, cc).apply()
        return free
    }

    fun reset() = prefs.edit().clear().apply()

    fun summary(): String = roles.joinToString(" / ") { role ->
        val cc = ccOf(role)
        role + ": " + (if (cc < 0) "未割当" else "CC" + cc)
    }

    /**
     * 絶対値モードの CC (0..127) を範囲へ写像する。
     * MiniLab 3 の既定は絶対値モードのため、相対エンコーダ処理は行わない。
     */
    fun scale(value: Int, min: Int, max: Int): Int =
        min + ((max - min) * value.coerceIn(0, 127) / 127.0).toInt()
}

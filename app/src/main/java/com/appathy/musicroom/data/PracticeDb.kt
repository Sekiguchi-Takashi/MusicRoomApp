package com.appathy.musicroom.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

data class SessionRow(
    val id: Long,
    val timestamp: Long,
    val kind: String,
    val label: String,
    val bpm: Int,
    val accuracy: Double,
    val meanErrorMs: Double,
    val score: Int,
    val itemCount: Int
)

data class MeasureRow(
    val measure: Int,
    val accuracy: Double,
    val meanErrorMs: Double,
    val miss: Int,
    val wrong: Int
)

object Kind {
    const val SONG = "song"
    const val RHYTHM = "rhythm"
    const val EAR = "ear"
    const val CHORD = "chord"
    const val REPEAT = "repeat"
    const val SING = "sing"

    fun label(kind: String): String = when (kind) {
        SONG -> "楽曲練習"
        RHYTHM -> "ピアノリズム"
        EAR -> "音当て"
        CHORD -> "コード"
        REPEAT -> "連打"
        SING -> "うた練習"
        else -> kind
    }
}

class PracticeDb private constructor(context: Context) :
    SQLiteOpenHelper(context.applicationContext, "musicroom.db", null, 1) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE sessions (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "ts INTEGER NOT NULL," +
                "kind TEXT NOT NULL," +
                "label TEXT NOT NULL," +
                "bpm INTEGER NOT NULL DEFAULT 0," +
                "accuracy REAL NOT NULL DEFAULT 0," +
                "mean_error REAL NOT NULL DEFAULT 0," +
                "score INTEGER NOT NULL DEFAULT 0," +
                "item_count INTEGER NOT NULL DEFAULT 0)"
        )
        db.execSQL(
            "CREATE TABLE measures (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "session_id INTEGER NOT NULL," +
                "measure INTEGER NOT NULL," +
                "accuracy REAL NOT NULL," +
                "mean_error REAL NOT NULL," +
                "miss INTEGER NOT NULL," +
                "wrong INTEGER NOT NULL)"
        )
        db.execSQL("CREATE INDEX idx_sessions_kind ON sessions(kind, ts)")
        db.execSQL("CREATE INDEX idx_measures_session ON measures(session_id)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // v1 のみ。将来のスキーマ変更はここに追記する。
    }

    // ----------------------------------------------------------------- write

    fun insertSession(
        kind: String,
        label: String,
        bpm: Int = 0,
        accuracy: Double = 0.0,
        meanErrorMs: Double = 0.0,
        score: Int = 0,
        itemCount: Int = 0
    ): Long {
        val values = ContentValues().apply {
            put("ts", System.currentTimeMillis())
            put("kind", kind)
            put("label", label)
            put("bpm", bpm)
            put("accuracy", accuracy)
            put("mean_error", meanErrorMs)
            put("score", score)
            put("item_count", itemCount)
        }
        return writableDatabase.insert("sessions", null, values)
    }

    fun insertMeasures(sessionId: Long, rows: List<MeasureRow>) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            rows.forEach { row ->
                val values = ContentValues().apply {
                    put("session_id", sessionId)
                    put("measure", row.measure)
                    put("accuracy", row.accuracy)
                    put("mean_error", row.meanErrorMs)
                    put("miss", row.miss)
                    put("wrong", row.wrong)
                }
                db.insert("measures", null, values)
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun clearAll() {
        writableDatabase.execSQL("DELETE FROM measures")
        writableDatabase.execSQL("DELETE FROM sessions")
    }

    // ------------------------------------------------------------------ read

    fun sessions(kind: String? = null, limit: Int = 20): List<SessionRow> {
        val sql = StringBuilder("SELECT id, ts, kind, label, bpm, accuracy, mean_error, score, item_count FROM sessions")
        val args = ArrayList<String>()
        if (kind != null) {
            sql.append(" WHERE kind = ?")
            args.add(kind)
        }
        sql.append(" ORDER BY ts DESC LIMIT ").append(limit)
        val out = ArrayList<SessionRow>()
        readableDatabase.rawQuery(sql.toString(), args.toTypedArray()).use { c ->
            while (c.moveToNext()) {
                out.add(
                    SessionRow(
                        c.getLong(0), c.getLong(1), c.getString(2), c.getString(3),
                        c.getInt(4), c.getDouble(5), c.getDouble(6), c.getInt(7), c.getInt(8)
                    )
                )
            }
        }
        return out
    }

    fun sessionCount(): Int {
        readableDatabase.rawQuery("SELECT COUNT(*) FROM sessions", null).use { c ->
            return if (c.moveToFirst()) c.getInt(0) else 0
        }
    }

    fun totalItems(): Int {
        readableDatabase.rawQuery("SELECT SUM(item_count) FROM sessions", null).use { c ->
            return if (c.moveToFirst()) c.getInt(0) else 0
        }
    }

    /** 種目ごとの平均正確度と平均ズレ。 */
    fun averages(kind: String): Pair<Double, Double>? {
        readableDatabase.rawQuery(
            "SELECT AVG(accuracy), AVG(mean_error), COUNT(*) FROM sessions WHERE kind = ?",
            arrayOf(kind)
        ).use { c ->
            if (c.moveToFirst() && c.getInt(2) > 0) {
                return Pair(c.getDouble(0), c.getDouble(1))
            }
        }
        return null
    }

    /** 曲を横断した苦手小節。直近セッションの平均正確度が低い順。 */
    fun weakMeasures(label: String, limit: Int = 5): List<MeasureRow> {
        val out = ArrayList<MeasureRow>()
        readableDatabase.rawQuery(
            "SELECT m.measure, AVG(m.accuracy), AVG(m.mean_error), SUM(m.miss), SUM(m.wrong) " +
                "FROM measures m JOIN sessions s ON s.id = m.session_id " +
                "WHERE s.label = ? GROUP BY m.measure ORDER BY AVG(m.accuracy) ASC LIMIT " + limit,
            arrayOf(label)
        ).use { c ->
            while (c.moveToNext()) {
                out.add(MeasureRow(c.getInt(0), c.getDouble(1), c.getDouble(2), c.getInt(3), c.getInt(4)))
            }
        }
        return out
    }

    /** 履歴に出てくる曲名 (楽曲練習のみ)。 */
    fun practicedSongs(): List<String> {
        val out = ArrayList<String>()
        readableDatabase.rawQuery(
            "SELECT label, MAX(ts) FROM sessions WHERE kind = ? GROUP BY label ORDER BY MAX(ts) DESC",
            arrayOf(Kind.SONG)
        ).use { c ->
            while (c.moveToNext()) out.add(c.getString(0))
        }
        return out
    }

    companion object {
        @Volatile
        private var instance: PracticeDb? = null

        fun get(context: Context): PracticeDb =
            instance ?: synchronized(this) {
                instance ?: PracticeDb(context).also { instance = it }
            }
    }
}

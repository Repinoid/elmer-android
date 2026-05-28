package ru.elmer.client

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/**
 * Локальное хранилище сессий диагностики.
 *
 * Схема:
 *   sessions   — метаданные сессии (скрипт, статус загрузки)
 *   responses  — сырые ответы ELM327 с декодированием
 */
class SessionDb(context: Context) : SQLiteOpenHelper(context, DB_NAME, null, DB_VERSION) {

    companion object {
        const val DB_NAME = "elmer_sessions.db"
        const val DB_VERSION = 2
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE sessions (
                id          INTEGER PRIMARY KEY AUTOINCREMENT,
                script_json TEXT    NOT NULL,
                title       TEXT,
                created_at  INTEGER NOT NULL DEFAULT (strftime('%s','now')),
                uploaded    INTEGER NOT NULL DEFAULT 0,
                diagnosis   TEXT,
                server_url  TEXT
            )
        """)
        db.execSQL("""
            CREATE TABLE responses (
                id          INTEGER PRIMARY KEY AUTOINCREMENT,
                session_id  INTEGER NOT NULL REFERENCES sessions(id),
                step_id     TEXT,
                cmd         TEXT,
                raw         TEXT,
                decoded     TEXT,
                timestamp   INTEGER NOT NULL DEFAULT (strftime('%s','now'))
            )
        """)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        db.execSQL("DROP TABLE IF EXISTS responses")
        db.execSQL("DROP TABLE IF EXISTS sessions")
        onCreate(db)
    }

    // ── Sessions ────────────────────────────────────────

    fun createSession(scriptJson: String, title: String?, serverUrl: String?): Long {
        val db = writableDatabase
        val cv = android.content.ContentValues().apply {
            put("script_json", scriptJson)
            put("title", title)
            serverUrl?.let { put("server_url", it) }
        }
        return db.insert("sessions", null, cv)
    }

    fun markUploaded(sessionId: Long) {
        writableDatabase.execSQL(
            "UPDATE sessions SET uploaded = 1 WHERE id = ?",
            arrayOf(sessionId)
        )
    }

    fun getPendingSessions(): List<Long> {
        val db = readableDatabase
        val cursor = db.rawQuery("SELECT id FROM sessions WHERE uploaded = 0", null)
        val ids = mutableListOf<Long>()
        while (cursor.moveToNext()) ids.add(cursor.getLong(0))
        cursor.close()
        return ids
    }

    // ── Responses ──────────────────────────────────────

    fun addResponse(sessionId: Long, stepId: String?, cmd: String?, raw: String, decoded: String?) {
        val db = writableDatabase
        val cv = android.content.ContentValues().apply {
            put("session_id", sessionId)
            put("step_id", stepId)
            put("cmd", cmd)
            put("raw", raw)
            put("decoded", decoded)
        }
        db.insert("responses", null, cv)
    }

    fun getResponses(sessionId: Long): List<Map<String, String?>> {
        val db = readableDatabase
        val cursor = db.rawQuery(
            "SELECT step_id, cmd, raw, decoded, timestamp FROM responses WHERE session_id = ? ORDER BY id",
            arrayOf(sessionId.toString())
        )
        val rows = mutableListOf<Map<String, String?>>()
        while (cursor.moveToNext()) {
            rows.add(mapOf(
                "step_id" to cursor.getString(0),
                "cmd" to cursor.getString(1),
                "raw" to cursor.getString(2),
                "decoded" to cursor.getString(3),
                "timestamp" to cursor.getString(4),
            ))
        }
        cursor.close()
        return rows
    }

    // ── Diagnosis ──────────────────────────────────────

    fun saveDiagnosis(sessionId: Long, diagnosis: String) {
        writableDatabase.execSQL(
            "UPDATE sessions SET diagnosis = ? WHERE id = ?",
            arrayOf(diagnosis, sessionId)
        )
    }

    fun getSessions(): List<Map<String, String?>> {
        val db = readableDatabase
        val cursor = db.rawQuery(
            "SELECT id, title, created_at, uploaded, diagnosis FROM sessions ORDER BY id DESC",
            null
        )
        val rows = mutableListOf<Map<String, String?>>()
        while (cursor.moveToNext()) {
            rows.add(mapOf(
                "id" to cursor.getLong(0).toString(),
                "title" to cursor.getString(1),
                "created_at" to cursor.getString(2),
                "uploaded" to cursor.getString(3),
                "diagnosis" to cursor.getString(4),
            ))
        }
        cursor.close()
        return rows
    }
}

package com.spark.companion.memory

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

data class ChatMessage(val id: Long, val fromUser: Boolean, val text: String, val time: Long)

/**
 * Spark's long-term memory: facts it has learned about the user, and the
 * conversation transcript. Everything stays in a private on-device database.
 */
class MemoryStore(context: Context) :
    SQLiteOpenHelper(context.applicationContext, "spark_memory.db", null, 1) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE facts (id INTEGER PRIMARY KEY AUTOINCREMENT, text TEXT NOT NULL, created INTEGER NOT NULL)")
        db.execSQL("CREATE TABLE messages (id INTEGER PRIMARY KEY AUTOINCREMENT, from_user INTEGER NOT NULL, text TEXT NOT NULL, time INTEGER NOT NULL)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    // ── Facts ────────────────────────────────────────────────────────────

    @Synchronized
    fun remember(fact: String): Boolean {
        val clean = fact.trim()
        if (clean.isEmpty()) return false
        readableDatabase.rawQuery(
            "SELECT 1 FROM facts WHERE lower(text) = lower(?) LIMIT 1", arrayOf(clean)
        ).use { if (it.moveToFirst()) return true }
        val values = ContentValues().apply {
            put("text", clean)
            put("created", System.currentTimeMillis())
        }
        return writableDatabase.insert("facts", null, values) != -1L
    }

    /** Most recent facts first, capped so the prompt stays small. */
    @Synchronized
    fun facts(limit: Int = 80): List<String> {
        val out = ArrayList<String>()
        readableDatabase.rawQuery(
            "SELECT text FROM facts ORDER BY id DESC LIMIT ?", arrayOf(limit.toString())
        ).use { c -> while (c.moveToNext()) out.add(c.getString(0)) }
        return out.reversed()
    }

    /** Deletes facts containing every word in [about]. Returns what was removed. */
    @Synchronized
    fun forget(about: String): List<String> {
        val words = about.lowercase().split(Regex("\\s+")).filter { it.length > 1 }
        if (words.isEmpty()) return emptyList()
        val removed = ArrayList<String>()
        val ids = ArrayList<Long>()
        readableDatabase.rawQuery("SELECT id, text FROM facts", null).use { c ->
            while (c.moveToNext()) {
                val text = c.getString(1)
                val lower = text.lowercase()
                if (words.all { lower.contains(it) }) {
                    ids.add(c.getLong(0))
                    removed.add(text)
                }
            }
        }
        ids.forEach { writableDatabase.delete("facts", "id = ?", arrayOf(it.toString())) }
        return removed
    }

    @Synchronized
    fun factCount(): Int =
        readableDatabase.rawQuery("SELECT COUNT(*) FROM facts", null).use {
            if (it.moveToFirst()) it.getInt(0) else 0
        }

    // ── Transcript ───────────────────────────────────────────────────────

    @Synchronized
    fun addMessage(fromUser: Boolean, text: String): ChatMessage {
        val now = System.currentTimeMillis()
        val values = ContentValues().apply {
            put("from_user", if (fromUser) 1 else 0)
            put("text", text)
            put("time", now)
        }
        val id = writableDatabase.insert("messages", null, values)
        return ChatMessage(id, fromUser, text, now)
    }

    @Synchronized
    fun recentMessages(limit: Int = 60): List<ChatMessage> {
        val out = ArrayList<ChatMessage>()
        readableDatabase.rawQuery(
            "SELECT id, from_user, text, time FROM messages ORDER BY id DESC LIMIT ?",
            arrayOf(limit.toString())
        ).use { c ->
            while (c.moveToNext()) {
                out.add(ChatMessage(c.getLong(0), c.getInt(1) == 1, c.getString(2), c.getLong(3)))
            }
        }
        return out.reversed()
    }

    @Synchronized
    fun clearMessages() {
        writableDatabase.delete("messages", null, null)
    }

    @Synchronized
    fun clearEverything() {
        writableDatabase.delete("messages", null, null)
        writableDatabase.delete("facts", null, null)
    }
}

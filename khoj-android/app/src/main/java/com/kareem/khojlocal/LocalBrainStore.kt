package com.kareem.khojlocal

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.security.MessageDigest

class LocalBrainStore(context: Context) : SQLiteOpenHelper(context, DB_NAME, null, DB_VERSION) {
    companion object {
        private const val DB_NAME = "khoj_local.db"
        private const val DB_VERSION = 1
        private const val TABLE = "memories"
        private const val FTS = "memories_fts"
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE $TABLE (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                title TEXT NOT NULL DEFAULT '',
                body TEXT NOT NULL,
                source TEXT NOT NULL DEFAULT 'manual',
                tags TEXT NOT NULL DEFAULT '',
                created_at INTEGER NOT NULL,
                updated_at INTEGER NOT NULL,
                attachment_path TEXT,
                content_hash TEXT NOT NULL UNIQUE
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX idx_memories_updated_at ON $TABLE(updated_at DESC)")
        db.execSQL("CREATE VIRTUAL TABLE $FTS USING fts4(title, body, source, tags)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    @Synchronized
    fun addMemory(
        title: String,
        body: String,
        source: String = "manual",
        tags: String = "",
        attachmentPath: String? = null,
    ): Long {
        val cleanTitle = title.trim().ifBlank { body.trim().lineSequence().firstOrNull()?.take(80).orEmpty() }
        val cleanBody = body.trim()
        require(cleanBody.isNotBlank() || cleanTitle.isNotBlank()) { "Memory cannot be empty" }

        val now = System.currentTimeMillis()
        val hash = sha256("$cleanTitle\n$cleanBody\n$source")
        val db = writableDatabase
        db.beginTransaction()
        try {
            val existingId = db.rawQuery(
                "SELECT id FROM $TABLE WHERE content_hash = ? LIMIT 1",
                arrayOf(hash),
            ).use { cursor -> if (cursor.moveToFirst()) cursor.getLong(0) else null }
            if (existingId != null) {
                db.setTransactionSuccessful()
                return existingId
            }

            val values = ContentValues().apply {
                put("title", cleanTitle)
                put("body", cleanBody)
                put("source", source.ifBlank { "manual" })
                put("tags", tags.trim())
                put("created_at", now)
                put("updated_at", now)
                if (attachmentPath != null) put("attachment_path", attachmentPath)
                put("content_hash", hash)
            }
            val id = db.insertOrThrow(TABLE, null, values)
            val ftsValues = ContentValues().apply {
                put("rowid", id)
                put("title", cleanTitle)
                put("body", cleanBody)
                put("source", source)
                put("tags", tags)
            }
            db.insertOrThrow(FTS, null, ftsValues)
            db.setTransactionSuccessful()
            return id
        } finally {
            db.endTransaction()
        }
    }

    @Synchronized
    fun updateMemory(id: Long, title: String, body: String, tags: String): Boolean {
        val existing = getMemory(id) ?: return false
        val cleanTitle = title.trim()
        val cleanBody = body.trim()
        if (cleanTitle.isBlank() && cleanBody.isBlank()) return false
        val now = System.currentTimeMillis()
        val hash = sha256("$cleanTitle\n$cleanBody\n${existing.source}")
        val db = writableDatabase
        db.beginTransaction()
        return try {
            val values = ContentValues().apply {
                put("title", cleanTitle)
                put("body", cleanBody)
                put("tags", tags.trim())
                put("updated_at", now)
                put("content_hash", hash)
            }
            val changed = db.update(TABLE, values, "id = ?", arrayOf(id.toString())) > 0
            if (changed) {
                db.delete(FTS, "rowid = ?", arrayOf(id.toString()))
                val ftsValues = ContentValues().apply {
                    put("rowid", id)
                    put("title", cleanTitle)
                    put("body", cleanBody)
                    put("source", existing.source)
                    put("tags", tags.trim())
                }
                db.insertOrThrow(FTS, null, ftsValues)
            }
            db.setTransactionSuccessful()
            changed
        } finally {
            db.endTransaction()
        }
    }

    @Synchronized
    fun deleteMemory(id: Long): Boolean {
        val db = writableDatabase
        db.beginTransaction()
        return try {
            db.delete(FTS, "rowid = ?", arrayOf(id.toString()))
            val deleted = db.delete(TABLE, "id = ?", arrayOf(id.toString())) > 0
            db.setTransactionSuccessful()
            deleted
        } finally {
            db.endTransaction()
        }
    }

    fun getMemory(id: Long): Memory? = readableDatabase.rawQuery(
        "SELECT id,title,body,source,tags,created_at,updated_at,attachment_path FROM $TABLE WHERE id = ? LIMIT 1",
        arrayOf(id.toString()),
    ).use { cursor -> if (cursor.moveToFirst()) cursor.toMemory() else null }

    fun count(): Int = readableDatabase.rawQuery("SELECT COUNT(*) FROM $TABLE", null).use { cursor ->
        if (cursor.moveToFirst()) cursor.getInt(0) else 0
    }

    fun listRecent(limit: Int = 50): List<Memory> = queryMemories(
        "SELECT id,title,body,source,tags,created_at,updated_at,attachment_path FROM $TABLE ORDER BY updated_at DESC LIMIT ?",
        arrayOf(limit.coerceIn(1, 1000).toString()),
    )

    fun listAllForVector(limit: Int = 500): List<Memory> = listRecent(limit.coerceAtMost(1000))

    fun lexicalSearch(rawQuery: String, limit: Int = 80): List<Memory> {
        val tokens = tokenize(rawQuery)
        if (tokens.isEmpty()) return emptyList()
        val ftsQuery = tokens.joinToString(" OR ") { escapeFtsToken(it) }
        return try {
            queryMemories(
                """
                SELECT m.id,m.title,m.body,m.source,m.tags,m.created_at,m.updated_at,m.attachment_path
                FROM $FTS f JOIN $TABLE m ON m.id = f.rowid
                WHERE $FTS MATCH ?
                ORDER BY m.updated_at DESC
                LIMIT ?
                """.trimIndent(),
                arrayOf(ftsQuery, limit.coerceIn(1, 300).toString()),
            )
        } catch (_: Exception) {
            val like = "%${rawQuery.trim()}%"
            queryMemories(
                """
                SELECT id,title,body,source,tags,created_at,updated_at,attachment_path
                FROM $TABLE
                WHERE title LIKE ? OR body LIKE ? OR tags LIKE ? OR source LIKE ?
                ORDER BY updated_at DESC LIMIT ?
                """.trimIndent(),
                arrayOf(like, like, like, like, limit.coerceIn(1, 300).toString()),
            )
        }
    }

    fun exportPlainText(): String {
        val builder = StringBuilder("# Khoj Local export\n\n")
        listRecent(1000).reversed().forEach { memory ->
            builder.append("## ").append(memory.title.ifBlank { "Memory ${memory.id}" }).append('\n')
            builder.append("Source: ").append(memory.source).append('\n')
            if (memory.tags.isNotBlank()) builder.append("Tags: ").append(memory.tags).append('\n')
            builder.append('\n').append(memory.body).append("\n\n---\n\n")
        }
        return builder.toString()
    }

    private fun queryMemories(sql: String, args: Array<String>): List<Memory> = readableDatabase.rawQuery(sql, args).use { cursor ->
        buildList {
            while (cursor.moveToNext()) add(cursor.toMemory())
        }
    }

    private fun Cursor.toMemory(): Memory = Memory(
        id = getLong(0),
        title = getString(1).orEmpty(),
        body = getString(2).orEmpty(),
        source = getString(3).orEmpty(),
        tags = getString(4).orEmpty(),
        createdAt = getLong(5),
        updatedAt = getLong(6),
        attachmentPath = if (isNull(7)) null else getString(7),
    )

    private fun tokenize(text: String): List<String> = text.lowercase()
        .split(Regex("[^\\p{L}\\p{N}_]+"))
        .map { it.trim() }
        .filter { it.length >= 2 }
        .distinct()
        .take(12)

    private fun escapeFtsToken(token: String): String = "\"${token.replace("\"", "\"\"")}\""

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
}

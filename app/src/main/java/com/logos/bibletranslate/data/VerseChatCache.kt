package com.logos.bibletranslate.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.security.MessageDigest

/**
 * Caches verse-chat replies keyed by verse + direction + the exact
 * conversation prefix + question (addendum §6), so repeated identical
 * follow-ups (e.g. tapping "Explain this verse" again on the same verse)
 * don't re-hit the API.
 */
class VerseChatCache(context: Context) {
    private val db: SQLiteDatabase = context.openOrCreateDatabase("verse_chat_cache.db", Context.MODE_PRIVATE, null).apply {
        execSQL(
            """
            CREATE TABLE IF NOT EXISTS chat_cache (
                verse_id INTEGER NOT NULL,
                source_lang TEXT NOT NULL,
                target_lang TEXT NOT NULL,
                context_hash TEXT NOT NULL,
                answer TEXT NOT NULL,
                PRIMARY KEY (verse_id, source_lang, target_lang, context_hash)
            )
            """.trimIndent(),
        )
    }

    private fun hashKey(history: List<ChatMessage>, question: String): String {
        val raw = history.joinToString("|") { "${it.role}:${it.text}" } + "||$question"
        val digest = MessageDigest.getInstance("SHA-256").digest(raw.toByteArray())
        return digest.joinToString("") { "%02x".format(it) }
    }

    suspend fun get(
        verseId: Long,
        sourceLang: BibleLanguage,
        targetLang: BibleLanguage,
        history: List<ChatMessage>,
        question: String,
    ): String? = withContext(Dispatchers.IO) {
        db.rawQuery(
            "SELECT answer FROM chat_cache WHERE verse_id = ? AND source_lang = ? AND target_lang = ? AND context_hash = ?",
            arrayOf(verseId.toString(), sourceLang.code, targetLang.code, hashKey(history, question)),
        ).use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
    }

    suspend fun put(
        verseId: Long,
        sourceLang: BibleLanguage,
        targetLang: BibleLanguage,
        history: List<ChatMessage>,
        question: String,
        answer: String,
    ) = withContext(Dispatchers.IO) {
        db.execSQL(
            "INSERT OR REPLACE INTO chat_cache (verse_id, source_lang, target_lang, context_hash, answer) VALUES (?, ?, ?, ?, ?)",
            arrayOf(verseId, sourceLang.code, targetLang.code, hashKey(history, question), answer),
        )
    }
}

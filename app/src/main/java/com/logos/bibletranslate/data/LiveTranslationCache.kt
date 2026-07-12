package com.logos.bibletranslate.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Persisted cache for the live-call experiment paths (Exodus/Cloud
 * Translation, Leviticus/Gemini) — keyed by the exact verse + direction +
 * word range tapped, so re-tapping the same selection reads locally
 * instead of paying for another network call. Separate writable database
 * from the bundled read-only Bible/word_translations assets.
 */
class LiveTranslationCache(context: Context) {
    private val db: SQLiteDatabase = context.openOrCreateDatabase("live_translation_cache.db", Context.MODE_PRIVATE, null).apply {
        execSQL(
            """
            CREATE TABLE IF NOT EXISTS cache (
                verse_id INTEGER NOT NULL,
                source_lang TEXT NOT NULL,
                target_lang TEXT NOT NULL,
                word_start INTEGER NOT NULL,
                word_end INTEGER NOT NULL,
                translated_text TEXT NOT NULL,
                PRIMARY KEY (verse_id, source_lang, target_lang, word_start, word_end)
            )
            """.trimIndent(),
        )
    }

    suspend fun get(verseId: Long, sourceLang: BibleLanguage, targetLang: BibleLanguage, wordStart: Int, wordEnd: Int): String? =
        withContext(Dispatchers.IO) {
            db.rawQuery(
                "SELECT translated_text FROM cache WHERE verse_id = ? AND source_lang = ? AND target_lang = ? AND word_start = ? AND word_end = ?",
                arrayOf(verseId.toString(), sourceLang.code, targetLang.code, wordStart.toString(), wordEnd.toString()),
            ).use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
        }

    suspend fun put(
        verseId: Long,
        sourceLang: BibleLanguage,
        targetLang: BibleLanguage,
        wordStart: Int,
        wordEnd: Int,
        translatedText: String,
    ) = withContext(Dispatchers.IO) {
        db.execSQL(
            "INSERT OR REPLACE INTO cache (verse_id, source_lang, target_lang, word_start, word_end, translated_text) VALUES (?, ?, ?, ?, ?, ?)",
            arrayOf(verseId, sourceLang.code, targetLang.code, wordStart, wordEnd, translatedText),
        )
    }
}

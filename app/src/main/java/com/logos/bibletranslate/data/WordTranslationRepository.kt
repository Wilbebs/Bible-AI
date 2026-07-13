package com.logos.bibletranslate.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class WordTranslation(
    val wordIndex: Int,
    val originalWord: String,
    val translatedWord: String,
)

private const val WORD_TRANSLATIONS_FILE = "word_translations.db"

class WordTranslationRepository(private val context: Context) {

    /**
     * Preloads every precomputed word mapping for a chapter in one batch read
     * (§5 runtime performance), keyed by the globally-unique numeric verse id
     * (not a bare verse number, which repeats across chapters once the reader
     * scrolls continuously) then ordered by word_index. Returns an empty map
     * if word_translations.db hasn't been bundled yet (Phase 2 not run) —
     * callers fall back to verse-level translation only.
     */
    suspend fun getChapterWordTranslations(
        sourceLang: BibleLanguage,
        targetLang: BibleLanguage,
        bookId: Int,
        chapter: Int,
    ): Map<Long, List<WordTranslation>> = withContext(Dispatchers.IO) {
        val db = BibleAssetDatabase.openByFileName(context, WORD_TRANSLATIONS_FILE)
            ?: return@withContext emptyMap()

        val minId = verseNumericId(bookId, chapter, 0)
        val maxId = verseNumericId(bookId, chapter, 999)
        val result = mutableMapOf<Long, MutableList<WordTranslation>>()
        db.rawQuery(
            "SELECT verse_id, word_index, original_word, translated_word FROM word_translations " +
                "WHERE verse_id BETWEEN ? AND ? AND source_lang = ? AND target_lang = ? " +
                "ORDER BY verse_id, word_index",
            arrayOf(minId.toString(), maxId.toString(), sourceLang.code, targetLang.code),
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val verseId = cursor.getLong(0)
                result.getOrPut(verseId) { mutableListOf() } += WordTranslation(
                    wordIndex = cursor.getInt(1),
                    originalWord = cursor.getString(2),
                    translatedWord = cursor.getString(3),
                )
            }
        }
        result
    }
}

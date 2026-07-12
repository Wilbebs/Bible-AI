package com.logos.bibletranslate.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class BibleRepository(private val context: Context) {

    suspend fun getBooks(language: BibleLanguage): List<BookInfo> = withContext(Dispatchers.IO) {
        val db = BibleAssetDatabase.open(context, language)
        val books = mutableListOf<BookInfo>()
        db.rawQuery(
            "SELECT DISTINCT book_id, book_name FROM verses ORDER BY book_id",
            null,
        ).use { cursor ->
            while (cursor.moveToNext()) {
                books += BookInfo(
                    bookId = cursor.getInt(0),
                    bookName = cursor.getString(1),
                )
            }
        }
        books
    }

    suspend fun getChapterCount(language: BibleLanguage, bookId: Int): Int =
        withContext(Dispatchers.IO) {
            val db = BibleAssetDatabase.open(context, language)
            db.rawQuery(
                "SELECT MAX(chapter) FROM verses WHERE book_id = ?",
                arrayOf(bookId.toString()),
            ).use { cursor ->
                if (cursor.moveToFirst()) cursor.getInt(0) else 0
            }
        }

    /** Loads every verse of a chapter in one batch read (§5 runtime performance). */
    suspend fun getChapter(language: BibleLanguage, bookId: Int, chapter: Int): List<VerseData> =
        withContext(Dispatchers.IO) {
            val db = BibleAssetDatabase.open(context, language)
            val verses = mutableListOf<VerseData>()
            db.rawQuery(
                "SELECT book_id, book_name, chapter, verse, text FROM verses " +
                    "WHERE book_id = ? AND chapter = ? ORDER BY verse",
                arrayOf(bookId.toString(), chapter.toString()),
            ).use { cursor ->
                while (cursor.moveToNext()) {
                    verses += VerseData(
                        bookId = cursor.getInt(0),
                        bookName = cursor.getString(1),
                        chapter = cursor.getInt(2),
                        verse = cursor.getInt(3),
                        text = cursor.getString(4),
                    )
                }
            }
            verses
        }

    /** Straight DB lookup of the same (book, chapter, verse) in another language (§6). */
    suspend fun getVerse(language: BibleLanguage, bookId: Int, chapter: Int, verse: Int): VerseData? =
        withContext(Dispatchers.IO) {
            val db = BibleAssetDatabase.open(context, language)
            db.rawQuery(
                "SELECT book_id, book_name, chapter, verse, text FROM verses " +
                    "WHERE book_id = ? AND chapter = ? AND verse = ? LIMIT 1",
                arrayOf(bookId.toString(), chapter.toString(), verse.toString()),
            ).use { cursor ->
                if (cursor.moveToFirst()) {
                    VerseData(
                        bookId = cursor.getInt(0),
                        bookName = cursor.getString(1),
                        chapter = cursor.getInt(2),
                        verse = cursor.getInt(3),
                        text = cursor.getString(4),
                    )
                } else {
                    null
                }
            }
        }
}

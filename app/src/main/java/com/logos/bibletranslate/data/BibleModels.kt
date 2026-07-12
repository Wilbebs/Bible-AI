package com.logos.bibletranslate.data

data class BookInfo(
    val bookId: Int,
    val bookName: String,
)

data class VerseData(
    val bookId: Int,
    val bookName: String,
    val chapter: Int,
    val verse: Int,
    val text: String,
) {
    val verseId: String get() = "$bookId:$chapter:$verse"
    val numericVerseId: Long get() = verseNumericId(bookId, chapter, verse)
}

/**
 * Matches the verse_id formula used by scripts/precompute_word_translations.mjs
 * (bookId*1_000_000 + chapter*1_000 + verse) so word-lookup joins line up.
 */
fun verseNumericId(bookId: Int, chapter: Int, verse: Int): Long =
    bookId.toLong() * 1_000_000 + chapter * 1_000 + verse

package com.logos.bibletranslate.data

/**
 * Must match the tokenization in scripts/precompute_word_translations.mjs
 * (whitespace split, punctuation kept attached to words) so a word's
 * position here matches its word_index in the precomputed data.
 */
object VerseTokenizer {
    private val WHITESPACE = Regex("\\s+")

    fun tokenize(text: String): List<String> =
        text.split(WHITESPACE).filter { it.isNotEmpty() }
}

package com.logos.bibletranslate.data

import android.content.Context
import android.speech.tts.TextToSpeech
import java.util.Locale

/**
 * Thin wrapper around Android's built-in [TextToSpeech] engine. One instance is held by the
 * ViewModel so any two simultaneous speak() calls auto-cancel each other (QUEUE_FLUSH).
 * Initialised lazily from the composable via ViewModel.initTts() so no Context leaks into
 * the ViewModel constructor; shut down in onCleared() when the screen leaves.
 */
class VerseTextToSpeech(context: Context) {

    private var engine: TextToSpeech? = null
    private var ready = false

    init {
        engine = TextToSpeech(context.applicationContext) { status ->
            ready = (status == TextToSpeech.SUCCESS)
        }
    }

    /**
     * Speaks [text] in the locale that best matches [languageCode] ([BibleLanguage.code]).
     * Uses QUEUE_FLUSH so starting a new utterance always cancels the previous one.
     * If the device TTS engine doesn't support the requested locale it falls back to its default.
     */
    fun speak(text: String, languageCode: String) {
        if (!ready || text.isBlank()) return
        engine?.setLanguage(localeFor(languageCode))
        engine?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "tts_${languageCode}_${text.length}")
    }

    fun stop() { engine?.stop() }

    fun shutdown() {
        engine?.shutdown()
        engine = null
        ready = false
    }

    /** Maps [BibleLanguage.code] to the closest supported Android TTS locale. */
    private fun localeFor(code: String): Locale = when (code) {
        "en"  -> Locale.ENGLISH
        "es"  -> Locale("es", "ES")
        "pt"  -> Locale("pt", "BR")
        "he"  -> Locale("iw", "IL")   // Android uses legacy "iw" tag for Hebrew
        "el"  -> Locale("el", "GR")
        "la"  -> Locale("la")
        // Aramaic ("arc") has no Android TTS locale; English reads transliteration acceptably
        else  -> Locale.ENGLISH
    }
}

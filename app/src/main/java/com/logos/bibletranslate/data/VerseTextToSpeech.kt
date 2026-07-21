package com.logos.bibletranslate.data

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale

/**
 * Thin wrapper around Android's built-in [TextToSpeech] engine. One instance is held by the
 * ViewModel so any two simultaneous speak() calls auto-cancel each other (QUEUE_FLUSH).
 * Initialised lazily from the composable via ViewModel.initTts() so no Context leaks into
 * the ViewModel constructor; shut down in onCleared() when the screen leaves.
 *
 * The optional [onDone] callback in [speak] is used by the partner-reading orchestrator to
 * know when an AI verse has finished being read aloud so it can advance to the user's turn.
 * Regular speaker-icon taps pass no callback.
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
     * If [onDone] is provided it is invoked when the utterance completes (or on error); the
     * callback may arrive on a non-main thread so callers must dispatch to Main if needed.
     */
    fun speak(text: String, languageCode: String, onDone: (() -> Unit)? = null) {
        if (!ready || text.isBlank()) return
        engine?.setLanguage(localeFor(languageCode))
        val utteranceId = "tts_${languageCode}_${text.length}_${text.hashCode()}"
        if (onDone != null) {
            engine?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(uid: String?) {}
                override fun onDone(uid: String?) { onDone() }
                @Deprecated("Deprecated in Java")
                override fun onError(uid: String?) { onDone() }
            })
        } else {
            // Clear any previous listener so a leftover partner-reading callback doesn't fire
            // on a speaker-icon tap that has nothing to do with the partner turn loop.
            engine?.setOnUtteranceProgressListener(null)
        }
        engine?.speak(text, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
    }

    fun stop() {
        engine?.setOnUtteranceProgressListener(null)
        engine?.stop()
    }

    fun shutdown() {
        engine?.setOnUtteranceProgressListener(null)
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
        else  -> Locale.ENGLISH
    }
}

package com.logos.bibletranslate.data

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * Coroutine-friendly one-shot wrapper around Android's [SpeechRecognizer].
 *
 * **Must be called from the main thread** — that is the SpeechRecognizer contract; calling
 * from a background dispatcher will crash with a RuntimeException on some API levels.
 * The ViewModel dispatches all calls via Dispatchers.Main.
 *
 * Suspends until the recogniser delivers a final result or an error, then returns.
 * The SpeechRecognizer instance is created and destroyed per call to keep lifecycle clean.
 */
class PartnerSpeechRecognizer {

    /**
     * Captures one utterance and returns the best transcript.
     * @param context Application context (not Activity) — no leak risk.
     * @param languageTag BCP-47 tag matching the reading language (e.g. "en-US", "es-ES").
     */
    suspend fun listenOnce(context: Context, languageTag: String = "en-US"): Result<String> =
        suspendCancellableCoroutine { cont ->
            if (!SpeechRecognizer.isRecognitionAvailable(context)) {
                cont.resume(Result.failure(Exception("Speech recognition not available on this device")))
                return@suspendCancellableCoroutine
            }

            val recognizer = SpeechRecognizer.createSpeechRecognizer(context)

            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, languageTag)
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
                // Prefer on-device recognition where available (Android 13+, Pixel Neural Core etc.)
                putExtra("android.speech.extra.PREFER_OFFLINE", true)
            }

            recognizer.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {}
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() {}
                override fun onPartialResults(partial: Bundle?) {}
                override fun onEvent(eventType: Int, params: Bundle?) {}

                override fun onResults(results: Bundle?) {
                    val transcript = results
                        ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        ?.firstOrNull()
                        ?: ""
                    recognizer.destroy()
                    if (cont.isActive) cont.resume(Result.success(transcript))
                }

                override fun onError(error: Int) {
                    recognizer.destroy()
                    if (cont.isActive) cont.resume(
                        Result.failure(Exception("SpeechRecognizer error code $error"))
                    )
                }
            })

            recognizer.startListening(intent)

            cont.invokeOnCancellation {
                runCatching {
                    recognizer.stopListening()
                    recognizer.destroy()
                }
            }
        }
}

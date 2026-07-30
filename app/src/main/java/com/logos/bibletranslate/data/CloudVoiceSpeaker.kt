package com.logos.bibletranslate.data

import android.content.Context
import android.media.MediaPlayer
import android.util.Base64
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Reads text aloud using Google Cloud Text-to-Speech (WaveNet/Neural2 voices) — replaces the
 * earlier Gemini-generative-audio engine. Cloud TTS is a dedicated speech-synthesis service, not
 * a large generative model, so it responds in well under a second instead of Gemini's several
 * seconds of "thinking" — that gap was the actual root cause of the read-aloud delay complaints,
 * not network speed, and it applied everywhere audio was used (verse speaker icons, word/
 * definition reads, partner reading). Uses [ApiKeys.translateApiKey] — the same GCP-provisioned
 * key already used for Cloud Translation — since Cloud TTS lives in the same API family and
 * needs the same kind of key, not the Gemini/AI Studio key. Requires the Cloud Text-to-Speech
 * API to be enabled on that key's GCP project (same console, same project as Translate).
 *
 * Same public shape as the engine it replaces: permanent on-disk cache keyed by (text, voice),
 * [prefetch] to warm that cache ahead of time without playing, [speak] fire-and-forget with a
 * new call cancelling whatever the previous one was doing, [onError] surfaces failures instead
 * of them vanishing silently.
 */
class CloudVoiceSpeaker(private val context: Context, private val onError: (String) -> Unit = {}) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    private var player: MediaPlayer? = null

    // filesDir, not cacheDir: Android can purge cacheDir under storage pressure with no warning,
    // which would silently defeat a cache meant to mean "never regenerate this verse again".
    private val cacheDir: File by lazy { File(context.filesDir, "cloud_tts_cache").apply { mkdirs() } }

    private val inFlightPrefetches = java.util.Collections.synchronizedSet(mutableSetOf<File>())

    fun speak(text: String, languageCode: String, onDone: (() -> Unit)? = null) {
        stop()
        if (text.isBlank()) {
            onDone?.invoke()
            return
        }
        if (languageCode in UNSUPPORTED_LANGUAGES) {
            onError("Voice reading isn't available for this language.")
            onDone?.invoke()
            return
        }
        val cacheFile = cacheFileFor(text, languageCode)
        if (cacheFile.exists()) {
            playFile(cacheFile, onDone)
            return
        }
        val apiKey = ApiKeys.translateApiKey
        if (apiKey == null) {
            onError("No Cloud API key configured — can't generate a voice.")
            onDone?.invoke()
            return
        }
        job = scope.launch {
            val result = fetchAudio(apiKey, text, languageCode)
            result.onSuccess { mp3 -> runCatching { cacheFile.writeBytes(mp3) } }
            withContext(Dispatchers.Main) {
                result.fold(
                    onSuccess = { playFile(cacheFile, onDone) },
                    onFailure = { err ->
                        onError(err.message ?: "Voice generation failed.")
                        onDone?.invoke()
                    },
                )
            }
        }
    }

    /** Opportunistically generates and caches [text]'s audio without playing it — fire-and-forget,
     *  silent on failure. No-ops if already cached or already in flight. */
    fun prefetch(text: String, languageCode: String) {
        if (text.isBlank() || languageCode in UNSUPPORTED_LANGUAGES) return
        val cacheFile = cacheFileFor(text, languageCode)
        if (cacheFile.exists() || cacheFile in inFlightPrefetches) return
        val apiKey = ApiKeys.translateApiKey ?: return
        inFlightPrefetches += cacheFile
        scope.launch {
            val result = fetchAudio(apiKey, text, languageCode)
            result.onSuccess { mp3 -> runCatching { cacheFile.writeBytes(mp3) } }
            inFlightPrefetches -= cacheFile
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        player?.let { p -> runCatching { p.stop(); p.release() } }
        player = null
    }

    fun shutdown() = stop()

    private fun isTransient(code: Int) = code == 503 || code == 429 || code == 500

    private class TransientHttpException(val code: Int, message: String) : Exception(message)

    private suspend fun fetchAudio(apiKey: String, text: String, languageCode: String): Result<ByteArray> {
        for (attempt in 0..2) {
            if (attempt > 0) delay(500L * attempt)
            val result = fetchAudioOnce(apiKey, text, languageCode)
            if (result.isSuccess) return result
            val code = (result.exceptionOrNull() as? TransientHttpException)?.code
            if (code == null || !isTransient(code)) return result
            if (attempt == 2) return Result.failure(Exception("Cloud voice service is busy right now — try again in a moment."))
        }
        error("unreachable")
    }

    /** Tries the named voice first; if Cloud TTS rejects that specific voice (a 400, e.g. a
     *  retired/renamed voice), falls back to letting Google pick a default voice for the
     *  language instead of failing outright. */
    private suspend fun fetchAudioOnce(apiKey: String, text: String, languageCode: String): Result<ByteArray> {
        val (locale, voiceName) = voiceFor(languageCode)
        val primary = synthesize(apiKey, text, locale, voiceName)
        if (primary.isSuccess || voiceName == null) return primary
        val primaryCode = (primary.exceptionOrNull() as? TransientHttpException)?.code
        if (primaryCode != null && isTransient(primaryCode)) return primary // let the outer retry loop handle it
        return synthesize(apiKey, text, locale, name = null)
    }

    private suspend fun synthesize(apiKey: String, text: String, locale: String, name: String?): Result<ByteArray> =
        withContext(Dispatchers.IO) {
            try {
                val url = URL("https://texttospeech.googleapis.com/v1/text:synthesize?key=$apiKey")
                val connection = url.openConnection() as HttpURLConnection
                connection.requestMethod = "POST"
                connection.setRequestProperty("Content-Type", "application/json")
                connection.doOutput = true
                connection.connectTimeout = 15_000
                connection.readTimeout = 20_000

                val voice = JSONObject().put("languageCode", locale)
                if (name != null) voice.put("name", name) else voice.put("ssmlGender", "NEUTRAL")

                val body = JSONObject()
                    .put("input", JSONObject().put("text", text))
                    .put("voice", voice)
                    .put("audioConfig", JSONObject().put("audioEncoding", "MP3"))

                connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }

                if (connection.responseCode !in 200..299) {
                    val code = connection.responseCode
                    if (isTransient(code)) return@withContext Result.failure(TransientHttpException(code, "HTTP $code"))
                    val errorBody = runCatching { connection.errorStream?.bufferedReader()?.use { it.readText() } }.getOrNull()
                    return@withContext Result.failure(
                        Exception("Cloud voice request failed: HTTP $code${errorBody?.let { " — ${it.take(200)}" } ?: ""}"),
                    )
                }
                val responseText = connection.inputStream.bufferedReader().use { it.readText() }
                val base64Audio = JSONObject(responseText).getString("audioContent")
                Result.success(Base64.decode(base64Audio, Base64.DEFAULT))
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    /** Plays an already-cached MP3 file. Never deletes it — that's the whole point of the cache. */
    private fun playFile(mp3File: File, onDone: (() -> Unit)?) {
        val mp = MediaPlayer()
        player = mp
        try {
            mp.setDataSource(mp3File.absolutePath)
            mp.setOnCompletionListener {
                it.release()
                if (player === it) player = null
                onDone?.invoke()
            }
            mp.setOnErrorListener { errored, what, extra ->
                errored.release()
                if (player === errored) player = null
                runCatching { mp3File.delete() } // corrupt/truncated cache entry — regenerate next time
                onError("Voice playback failed (code $what/$extra).")
                onDone?.invoke()
                true
            }
            mp.prepare()
            mp.start()
        } catch (e: Exception) {
            mp.release()
            player = null
            onError("Couldn't play the voice audio: ${e.message}")
            onDone?.invoke()
        }
    }

    private fun cacheFileFor(text: String, languageCode: String): File {
        val (locale, voiceName) = voiceFor(languageCode)
        val key = sha256Hex("$text|$locale|${voiceName ?: "default"}")
        return File(cacheDir, "$key.mp3")
    }

    private fun sha256Hex(input: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    /** Maps [BibleLanguage.code] to a Cloud TTS locale + a specific WaveNet/Neural2 voice name.
     *  A null voice name means "no good match, let synthesize() fall back to languageCode-only".
     *  Cloud TTS has no voices at all for Aramaic or Latin — those languages don't get spoken. */
    private fun voiceFor(code: String): Pair<String, String?> = when (code) {
        "en" -> "en-US" to "en-US-Neural2-C"
        "es" -> "es-US" to "es-US-Neural2-A"
        "pt" -> "pt-BR" to "pt-BR-Neural2-A"
        "zh" -> "cmn-CN" to "cmn-CN-Wavenet-A" // Cloud TTS uses the "cmn-CN" locale tag for Mandarin, not "zh-CN"
        "he" -> "he-IL" to "he-IL-Wavenet-A"
        "el" -> "el-GR" to "el-GR-Wavenet-A" // modern Greek phonology — best available match for the ancient text
        else -> "en-US" to null
    }

    companion object {
        /** Cloud TTS has no voice at all for these — reading them in a substitute voice would
         *  mispronounce the text badly, so they're refused outright instead of faked. */
        private val UNSUPPORTED_LANGUAGES = setOf("arc", "la")
    }
}

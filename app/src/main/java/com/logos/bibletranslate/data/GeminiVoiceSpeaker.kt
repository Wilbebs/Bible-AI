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
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest

private const val TTS_MODEL = "gemini-3.1-flash-tts-preview"
/** Falls back to this if the newer model rejects the request (e.g. not yet enabled on this key's project). */
private const val TTS_MODEL_FALLBACK = "gemini-2.5-flash-preview-tts"

/**
 * Reads text aloud with a real Gemini AI voice (native audio generation) instead of Android's
 * built-in, robotic-sounding TextToSpeech engine.
 *
 * Every unique (text, voice) pairing is generated **once ever, per device** and cached
 * permanently in the app's private files directory — Bible verse text is static, so a verse read
 * a second time (a repeat speaker-icon tap, a re-read in partner mode, a second app session)
 * plays instantly from disk instead of re-paying Gemini's several-second generate latency.
 * [prefetch] lets a caller warm that cache ahead of time for text it knows it'll need soon
 * (partner reading uses this to generate the AI's next verse in the background while the user
 * is still reading theirs, so by the time it's needed it's often already sitting on disk).
 *
 * Deliberately used only for full verse reads (the verse-number speaker icon, and partner
 * reading's turn-by-turn verses/replies) — not for the AI window's word/phrase/chat-message
 * speaker icons, which use [CloudVoiceSpeaker] instead for its near-instant response time. A
 * whole verse is long enough, and part of a slower-paced reading flow anyway, that Gemini's
 * few-second generation cost is worth paying for its noticeably more natural voice; a single
 * word tapped for instant feedback is not.
 *
 * [speak] is not suspend, starts its own network + playback job, and a new call cancels
 * whatever the previous one was doing (mirrors the old Android TTS engine's QUEUE_FLUSH
 * behaviour) so overlapping taps can't talk over each other. [onError] fires whenever a request
 * or playback failure happens — previously these failed completely silently.
 */
class GeminiVoiceSpeaker(private val context: Context, private val onError: (String) -> Unit = {}) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    private var player: MediaPlayer? = null

    /** Which TTS model actually worked last time — null until the first successful call. */
    private var resolvedModel: String? = null

    // filesDir, not cacheDir: Android is free to purge cacheDir under storage pressure at any
    // time with no warning, which would silently defeat the point of a "never regenerate this
    // verse again" cache. filesDir only goes away on uninstall/clear-data.
    private val cacheDir: File by lazy { File(context.filesDir, "gemini_tts_cache").apply { mkdirs() } }

    /**
     * Speaks [text] using a voice appropriate for [languageCode]. [onDone] fires once playback
     * finishes, fails, or the request errors — callers rely on this to advance partner-reading
     * turns, so it must always fire eventually rather than leaving the caller stuck waiting.
     */
    fun speak(text: String, languageCode: String, onDone: (() -> Unit)? = null) {
        stop()
        if (text.isBlank()) {
            onDone?.invoke()
            return
        }
        val cacheFile = cacheFileFor(text, languageCode)
        if (cacheFile.exists()) {
            playFile(cacheFile, onDone)
            return
        }
        val apiKey = ApiKeys.geminiApiKey
        if (apiKey == null) {
            onError("No Gemini API key configured — can't generate a voice.")
            onDone?.invoke()
            return
        }
        job = scope.launch {
            val result = fetchWithFallback(apiKey, text, languageCode)
            result.onSuccess { pcm -> runCatching { cacheFile.writeBytes(wavHeader(pcm.size) + pcm) } }
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

    /**
     * Opportunistically generates and caches [text]'s audio without playing it — fire-and-forget,
     * silent on failure (a real [speak] call for the same text will surface any error itself when
     * it's actually needed). No-ops if already cached or already in flight, and deliberately does
     * not touch [job]/[player] so it can never interrupt whatever is currently playing.
     */
    fun prefetch(text: String, languageCode: String) {
        if (text.isBlank()) return
        val cacheFile = cacheFileFor(text, languageCode)
        if (cacheFile.exists() || cacheFile in inFlightPrefetches) return
        val apiKey = ApiKeys.geminiApiKey ?: return
        inFlightPrefetches += cacheFile
        scope.launch {
            val result = fetchWithFallback(apiKey, text, languageCode)
            result.onSuccess { pcm -> runCatching { cacheFile.writeBytes(wavHeader(pcm.size) + pcm) } }
            inFlightPrefetches -= cacheFile
        }
    }

    private val inFlightPrefetches = java.util.Collections.synchronizedSet(mutableSetOf<File>())

    fun stop() {
        job?.cancel()
        job = null
        player?.let { p -> runCatching { p.stop(); p.release() } }
        player = null
    }

    fun shutdown() = stop()

    private suspend fun fetchWithFallback(apiKey: String, text: String, languageCode: String): Result<ByteArray> {
        // Blindly retrying the fallback model on every single call doubled the network round
        // trip (and so the perceived delay before any audio started) — once we learn which model
        // this key actually works with, stick to it. resolvedModel starts at the preferred model
        // and only ever moves to the fallback, never back, so a transient fallback failure can't
        // thrash between the two.
        val voice = voiceFor(languageCode)
        val firstModel = resolvedModel ?: TTS_MODEL
        var result = fetchAudio(apiKey, text, voice, firstModel)
        if (result.isFailure && resolvedModel == null && firstModel == TTS_MODEL) {
            result = fetchAudio(apiKey, text, voice, TTS_MODEL_FALLBACK)
            if (result.isSuccess) resolvedModel = TTS_MODEL_FALLBACK
        } else if (result.isSuccess) {
            resolvedModel = firstModel
        }
        return result
    }

    /** HTTP codes worth a short retry — transient capacity/rate issues, not real request errors. */
    private fun isTransient(code: Int) = code == 503 || code == 429 || code == 500

    private suspend fun fetchAudio(apiKey: String, text: String, voiceName: String, model: String): Result<ByteArray> {
        for (attempt in 0..2) {
            if (attempt > 0) delay(500L * attempt) // 500ms, then 1000ms
            val result = fetchAudioOnce(apiKey, text, voiceName, model)
            if (result.isSuccess) return result
            val code = (result.exceptionOrNull() as? TransientHttpException)?.code
            if (code == null || !isTransient(code)) return result // don't retry real errors
            if (attempt == 2) {
                // Every attempt hit a transient error — surface a plain, non-technical message
                // rather than the raw JSON error body a user saw once here (a scary wall of text
                // for something that's just "Google's TTS servers are momentarily overloaded").
                return Result.failure(Exception("Gemini's voice service is busy right now — try again in a moment."))
            }
        }
        error("unreachable")
    }

    private class TransientHttpException(val code: Int, message: String) : Exception(message)

    private suspend fun fetchAudioOnce(apiKey: String, text: String, voiceName: String, model: String): Result<ByteArray> =
        withContext(Dispatchers.IO) {
            try {
                val url = URL("https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent?key=$apiKey")
                val connection = url.openConnection() as HttpURLConnection
                connection.requestMethod = "POST"
                connection.setRequestProperty("Content-Type", "application/json")
                connection.doOutput = true
                connection.connectTimeout = 15_000
                connection.readTimeout = 30_000

                val body = JSONObject().apply {
                    put("contents", JSONArray().put(JSONObject().put("parts", JSONArray().put(JSONObject().put("text", text)))))
                    put(
                        "generationConfig",
                        JSONObject().apply {
                            put("responseModalities", JSONArray().put("AUDIO"))
                            put(
                                "speechConfig",
                                JSONObject().put(
                                    "voiceConfig",
                                    JSONObject().put("prebuiltVoiceConfig", JSONObject().put("voiceName", voiceName)),
                                ),
                            )
                        },
                    )
                }
                connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }

                if (connection.responseCode !in 200..299) {
                    val code = connection.responseCode
                    if (isTransient(code)) {
                        return@withContext Result.failure(TransientHttpException(code, "HTTP $code"))
                    }
                    val errorBody = runCatching { connection.errorStream?.bufferedReader()?.use { it.readText() } }.getOrNull()
                    return@withContext Result.failure(
                        Exception("Gemini voice request failed: HTTP $code${errorBody?.let { " — ${it.take(200)}" } ?: ""}"),
                    )
                }
                val responseText = connection.inputStream.bufferedReader().use { it.readText() }
                val json = JSONObject(responseText)
                val base64Audio = json.getJSONArray("candidates")
                    .getJSONObject(0)
                    .getJSONObject("content")
                    .getJSONArray("parts")
                    .getJSONObject(0)
                    .getJSONObject("inlineData")
                    .getString("data")
                Result.success(Base64.decode(base64Audio, Base64.DEFAULT))
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    /** Plays an already-cached WAV file. Never deletes it — that's the whole point of the cache. */
    private fun playFile(wavFile: File, onDone: (() -> Unit)?) {
        val mp = MediaPlayer()
        player = mp
        try {
            mp.setDataSource(wavFile.absolutePath)
            mp.setOnCompletionListener {
                it.release()
                if (player === it) player = null
                onDone?.invoke()
            }
            mp.setOnErrorListener { errored, what, extra ->
                errored.release()
                if (player === errored) player = null
                // A cached file that fails to play is corrupt/truncated — remove it so the next
                // attempt regenerates instead of failing the same way forever.
                runCatching { wavFile.delete() }
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
        val key = sha256Hex("$text|${voiceFor(languageCode)}")
        return File(cacheDir, "$key.wav")
    }

    private fun sha256Hex(input: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    private fun wavHeader(dataSize: Int, sampleRate: Int = 24000, channels: Int = 1, bitsPerSample: Int = 16): ByteArray {
        val byteRate = sampleRate * channels * bitsPerSample / 8
        val blockAlign = channels * bitsPerSample / 8
        val buffer = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        buffer.put("RIFF".toByteArray(Charsets.US_ASCII))
        buffer.putInt(36 + dataSize)
        buffer.put("WAVE".toByteArray(Charsets.US_ASCII))
        buffer.put("fmt ".toByteArray(Charsets.US_ASCII))
        buffer.putInt(16)
        buffer.putShort(1) // PCM
        buffer.putShort(channels.toShort())
        buffer.putInt(sampleRate)
        buffer.putInt(byteRate)
        buffer.putShort(blockAlign.toShort())
        buffer.putShort(bitsPerSample.toShort())
        buffer.put("data".toByteArray(Charsets.US_ASCII))
        buffer.putInt(dataSize)
        return buffer.array()
    }

    /** Gemini prebuilt voice names — picked per reading language for a natural-sounding fit. */
    private fun voiceFor(code: String): String = when (code) {
        "en" -> "Kore"
        "es" -> "Puck"
        "pt" -> "Aoede"
        "zh" -> "Kore"
        "he", "el", "arc", "la" -> "Kore"
        else -> "Kore"
    }
}

package com.logos.bibletranslate.data

import android.content.Context
import android.media.MediaPlayer
import android.util.Base64
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.nio.ByteOrder

private const val TTS_MODEL = "gemini-3.1-flash-tts-preview"
/** Falls back to this if the newer model rejects the request (e.g. not yet enabled on this key's project). */
private const val TTS_MODEL_FALLBACK = "gemini-2.5-flash-preview-tts"

/**
 * Reads text aloud with a real Gemini AI voice (native audio generation) instead of Android's
 * built-in, robotic-sounding TextToSpeech engine — replaces the old VerseTextToSpeech everywhere
 * in the app (verse speaker icons, AI chat replies, partner-reading turns) since every one of
 * those call sites already requires the app to be online with a Gemini key configured.
 *
 * Same fire-and-forget shape as the engine it replaces: [speak] is not suspend, starts its own
 * network + playback job, and a new call cancels whatever the previous one was doing (mirrors
 * the old engine's QUEUE_FLUSH behaviour) so overlapping taps can't talk over each other.
 *
 * [onError] fires (in addition to [Companion]-less [speak]'s [onDone]) whenever a request or
 * playback failure happens — a request that silently did nothing used to be indistinguishable
 * from "the AI voice sounds robotic" if a caller ended up hearing nothing at all; now the
 * caller can surface the real reason instead of guessing.
 */
class GeminiVoiceSpeaker(private val context: Context, private val onError: (String) -> Unit = {}) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    private var player: MediaPlayer? = null

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
        val apiKey = ApiKeys.geminiApiKey
        if (apiKey == null) {
            onError("No Gemini API key configured — can't generate a voice.")
            onDone?.invoke()
            return
        }
        job = scope.launch {
            var result = fetchAudio(apiKey, text, voiceFor(languageCode), TTS_MODEL)
            if (result.isFailure) {
                // Retry once against the older, more broadly-enabled model before giving up —
                // some API keys/projects haven't been granted the newest preview model yet.
                result = fetchAudio(apiKey, text, voiceFor(languageCode), TTS_MODEL_FALLBACK)
            }
            withContext(Dispatchers.Main) {
                result.fold(
                    onSuccess = { pcm -> playPcm(pcm, onDone) },
                    onFailure = { err ->
                        onError(err.message ?: "Voice generation failed.")
                        onDone?.invoke()
                    },
                )
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        player?.let { p -> runCatching { p.stop(); p.release() } }
        player = null
    }

    fun shutdown() = stop()

    private suspend fun fetchAudio(apiKey: String, text: String, voiceName: String, model: String): Result<ByteArray> =
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
                    val errorBody = runCatching { connection.errorStream?.bufferedReader()?.use { it.readText() } }.getOrNull()
                    return@withContext Result.failure(
                        Exception("Gemini voice request failed: HTTP ${connection.responseCode}${errorBody?.let { " — ${it.take(200)}" } ?: ""}"),
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

    /** Gemini's native audio output is raw PCM16 mono @ 24kHz — wrap it in a minimal WAV header
     *  and hand it to MediaPlayer rather than hand-rolling AudioTrack timing/threading. */
    private fun playPcm(pcm: ByteArray, onDone: (() -> Unit)?) {
        val wavFile = File(context.cacheDir, "gemini_tts_${System.nanoTime()}.wav")
        try {
            wavFile.writeBytes(wavHeader(pcm.size) + pcm)
        } catch (e: Exception) {
            onError("Couldn't save the voice audio: ${e.message}")
            onDone?.invoke()
            return
        }
        val mp = MediaPlayer()
        player = mp
        try {
            mp.setDataSource(wavFile.absolutePath)
            mp.setOnCompletionListener {
                it.release()
                wavFile.delete()
                if (player === it) player = null
                onDone?.invoke()
            }
            mp.setOnErrorListener { errored, what, extra ->
                errored.release()
                wavFile.delete()
                if (player === errored) player = null
                onError("Voice playback failed (code $what/$extra).")
                onDone?.invoke()
                true
            }
            mp.prepare()
            mp.start()
        } catch (e: Exception) {
            mp.release()
            wavFile.delete()
            player = null
            onError("Couldn't play the voice audio: ${e.message}")
            onDone?.invoke()
        }
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

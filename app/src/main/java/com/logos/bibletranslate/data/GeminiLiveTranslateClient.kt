package com.logos.bibletranslate.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

private const val MODEL = "gemini-3.1-flash-lite"

/**
 * Live, non-preprocessed Gemini calls — the "direct API" comparison arm for
 * Leviticus (§5's live-API alternative to the precomputed word_translations
 * table). One network call per tap, scoped to just the selected word/phrase
 * rather than the whole verse, so cost per tap stays small.
 */
class GeminiLiveTranslateClient {

    suspend fun translateSelection(
        apiKey: String,
        sourceLangName: String,
        targetLangName: String,
        sourceVerseText: String,
        targetVerseText: String,
        selectedText: String,
    ): Result<String> = withContext(Dispatchers.IO) {
        try {
            // Use a full-passage translation prompt for multi-word selections (phrases or whole
            // verses) so Gemini understands it must cover the entire input. The short "word or
            // phrase" framing was confusing the model into returning just a few words even when
            // an entire verse was selected, producing a truncated translation.
            val wordCount = selectedText.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }.size
            val isMultiWord = wordCount > 4

            val prompt = if (isMultiWord) {
                """
                    Translate the following $sourceLangName text into $targetLangName.
                    Text to translate: "$selectedText"
                    Verse context ($sourceLangName): "$sourceVerseText"
                    Reference $targetLangName translation of the same verse: "$targetVerseText"
                    Provide a COMPLETE translation that covers every word of the input — do not omit or summarise any part.
                    Respond with ONLY the translated text, nothing else.
                """.trimIndent()
            } else {
                """
                    Bible verse context. Source ($sourceLangName): "$sourceVerseText"
                    Official $targetLangName translation of the same verse: "$targetVerseText"
                    What is the $targetLangName word or short phrase that "$selectedText" corresponds to in the official translation above, given this context?
                    Respond with ONLY the translated word or phrase, nothing else.
                """.trimIndent()
            }

            val url = URL("https://generativelanguage.googleapis.com/v1beta/models/$MODEL:generateContent?key=$apiKey")
            val connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "POST"
            connection.setRequestProperty("Content-Type", "application/json")
            connection.doOutput = true
            connection.connectTimeout = 15_000
            connection.readTimeout = 15_000

            val body = JSONObject().apply {
                put(
                    "contents",
                    org.json.JSONArray().put(
                        JSONObject().put(
                            "parts",
                            org.json.JSONArray().put(JSONObject().put("text", prompt)),
                        ),
                    ),
                )
                put("generationConfig", JSONObject().put("temperature", 0.1))
            }

            OutputStreamWriter(connection.outputStream).use { it.write(body.toString()) }

            if (connection.responseCode !in 200..299) {
                val error = connection.errorStream?.bufferedReader()?.readText() ?: "HTTP ${connection.responseCode}"
                return@withContext Result.failure(Exception(error.take(200)))
            }

            val responseText = connection.inputStream.bufferedReader().readText()
            val json = JSONObject(responseText)
            val text = json.getJSONArray("candidates")
                .getJSONObject(0)
                .getJSONObject("content")
                .getJSONArray("parts")
                .getJSONObject(0)
                .getString("text")
                .trim()

            // Guard against Gemini still truncating on a multi-word selection: if the response
            // looks implausibly short relative to what was asked for, fall back to the official
            // target-verse text so the user sees something correct rather than a fragment.
            if (isMultiWord && targetVerseText.isNotEmpty()) {
                val resultWords = text.split(Regex("\\s+")).filter { it.isNotEmpty() }.size
                val minExpected = (wordCount * 0.25).toInt().coerceAtLeast(3)
                if (resultWords < minExpected) {
                    return@withContext Result.success(targetVerseText)
                }
            }

            Result.success(text)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}

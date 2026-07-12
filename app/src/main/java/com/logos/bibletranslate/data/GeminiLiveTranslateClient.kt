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
            val prompt = """
                Bible verse context. Source ($sourceLangName): "$sourceVerseText"
                Official $targetLangName translation of the same verse: "$targetVerseText"
                What is the $targetLangName word or short phrase that "$selectedText" corresponds to in the official translation above, given this context?
                Respond with ONLY the translated word or phrase, nothing else.
            """.trimIndent()

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

            Result.success(text)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}

package com.logos.bibletranslate.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

/**
 * Live, non-preprocessed Google Cloud Translation API calls — the "dedicated
 * translate API" comparison arm for Exodus. Deliberately translates each
 * selected word in isolation (no verse context at all, unlike the Gemini
 * paths), since Cloud Translation has no word-alignment/context feature —
 * this is the plan's original concern (§5) made concrete: how much does
 * losing context actually hurt on ambiguous words?
 */
class GoogleTranslateLiveClient {

    suspend fun translateWords(
        apiKey: String,
        sourceLangCode: String,
        targetLangCode: String,
        words: List<String>,
    ): Result<List<String>> = withContext(Dispatchers.IO) {
        try {
            val url = URL("https://translation.googleapis.com/language/translate/v2?key=$apiKey")
            val connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "POST"
            connection.setRequestProperty("Content-Type", "application/json")
            connection.doOutput = true
            connection.connectTimeout = 15_000
            connection.readTimeout = 15_000

            val body = JSONObject().apply {
                put("q", JSONArray(words))
                put("source", sourceLangCode)
                put("target", targetLangCode)
                put("format", "text")
            }

            OutputStreamWriter(connection.outputStream).use { it.write(body.toString()) }

            if (connection.responseCode !in 200..299) {
                val error = connection.errorStream?.bufferedReader()?.readText() ?: "HTTP ${connection.responseCode}"
                return@withContext Result.failure(Exception(error.take(200)))
            }

            val responseText = connection.inputStream.bufferedReader().readText()
            val translations = JSONObject(responseText)
                .getJSONObject("data")
                .getJSONArray("translations")
            val results = (0 until translations.length()).map { i ->
                translations.getJSONObject(i).getString("translatedText")
            }
            Result.success(results)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}

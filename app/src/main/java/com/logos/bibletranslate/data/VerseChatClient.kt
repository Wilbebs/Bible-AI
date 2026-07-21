package com.logos.bibletranslate.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

private const val MODEL = "gemini-3.1-flash-lite"

private val DETECTION_RESPONSE_SCHEMA = JSONObject()
    .put("type", "OBJECT")
    .put(
        "properties",
        JSONObject()
            .put("lang", JSONObject().put("type", "STRING"))
            .put("reply", JSONObject().put("type", "STRING")),
    )
    .put("required", JSONArray().put("lang").put("reply"))

private val WORD_INFO_RESPONSE_SCHEMA = JSONObject()
    .put("type", "OBJECT")
    .put(
        "properties",
        JSONObject()
            .put("pronunciation", JSONObject().put("type", "STRING"))
            .put("definition", JSONObject().put("type", "STRING"))
            .put("translation", JSONObject().put("type", "STRING")),
    )
    .put("required", JSONArray().put("pronunciation").put("definition").put("translation"))

private val PARTNER_JUDGMENT_SCHEMA = JSONObject()
    .put("type", "OBJECT")
    .put(
        "properties",
        JSONObject()
            .put("kind", JSONObject().put("type", "STRING"))
            .put("reply", JSONObject().put("type", "STRING")),
    )
    .put("required", JSONArray().put("kind").put("reply"))

/**
 * Scoped mini-chatbot for a single selected verse/word (addendum §2). Every
 * call is a live Gemini request carrying the verse text, the running
 * in-bubble conversation history, and a system prompt that keeps the model
 * strictly on-topic for that one verse.
 */
class VerseChatClient {

    /** Fixed-language reply — used when a manual toggle override or a short/ambiguous message means we don't want auto-detection to kick in. */
    suspend fun sendMessage(
        apiKey: String,
        verseRef: String,
        sourceLangName: String,
        targetLangName: String,
        sourceText: String,
        targetText: String,
        history: List<ChatMessage>,
        userMessage: String,
    ): Result<String> {
        val systemInstruction = baseSystemInstruction(verseRef, sourceLangName, sourceText, targetLangName, targetText) +
            "\nRespond in $targetLangName."
        return callGemini(apiKey, systemInstruction, history, userMessage, responseSchema = null)
            .map { it.trim() }
    }

    /**
     * Auto-detects which of EN/ES/PT the user's message is written in and
     * replies in that same language (auto-detect-language-idea.md). Returns
     * (detectedLanguage, replyText).
     */
    suspend fun sendMessageWithDetection(
        apiKey: String,
        verseRef: String,
        sourceLangName: String,
        sourceText: String,
        referenceTargetLangName: String,
        referenceTargetText: String,
        currentLanguage: BibleLanguage,
        history: List<ChatMessage>,
        userMessage: String,
    ): Result<Pair<BibleLanguage, String>> {
        val systemInstruction = baseSystemInstruction(verseRef, sourceLangName, sourceText, referenceTargetLangName, referenceTargetText) +
            """

                The user's message may be written in English, Spanish, or Portuguese. Detect which of these
                three it is written in and reply in that same language. If the message is too short or
                ambiguous to confidently tell (e.g. a single common word, "ok", a name), instead keep the
                language as "${currentLanguage.code}" and reply in ${currentLanguage.displayName}.
                Respond as JSON: {"lang": "en"|"es"|"pt", "reply": "your reply text"}.
            """.trimIndent()

        return callGemini(apiKey, systemInstruction, history, userMessage, responseSchema = DETECTION_RESPONSE_SCHEMA)
            .mapCatching { raw ->
                val json = JSONObject(raw)
                val lang = BibleLanguage.fromCode(json.getString("lang")) ?: currentLanguage
                lang to json.getString("reply").trim()
            }
    }

    /**
     * A single word's pronunciation + dictionary-style definition + a short cross-language
     * translation, grounded in the verse it appears in for the correct sense. The translation
     * mirrors the "word · translation" pairing shown for scripture-word selections — so tapping
     * a word anywhere in the AI window shows the same kind of dual-language label.
     *
     * [translationTargetLangName] is the language the translation should be written in (typically
     * the opposite direction from [wordLanguageName]: if the word is Spanish, translate to
     * English; if the word is English from a definition, translate back to Spanish).
     */
    suspend fun fetchWordInfo(
        apiKey: String,
        word: String,
        wordLanguageName: String,
        responseLanguageName: String,
        translationTargetLangName: String,
        verseRef: String,
        verseContext: String,
    ): Result<Triple<String, String, String?>> {
        val translationLine = if (translationTargetLangName != wordLanguageName) {
            """3. A short translation of "$word" from $wordLanguageName into $translationTargetLangName (1–4 words maximum).
               CRITICAL: If "$word" is a proper noun — a person's name (e.g. Aarón, Moses, Jehová), place name, or title — do NOT translate it to a pronoun or paraphrase. Instead, write its standard form in $translationTargetLangName (or the unchanged name if it carries across languages). For example, "Aarón" → "Aaron", never "him" or "his"."""
        } else {
            "3. Leave the \"translation\" field as an empty string."
        }
        val systemInstruction = """
            The word "$word" appears in $wordLanguageName in this Bible verse ($verseRef): "$verseContext"
            Provide:
            1. A simple, easy-to-read phonetic pronunciation guide for "$word" (not IPA — spelled out for a learner, e.g. "boh-NEE-toh"). Keep this spelled-out guide itself readable regardless of language.
            2. A concise dictionary-style definition (one sentence) of "$word" as used in this context, written in $responseLanguageName.
            $translationLine
            Respond as JSON: {"pronunciation": "...", "definition": "...", "translation": "..."}.
        """.trimIndent()
        return callGemini(apiKey, systemInstruction, emptyList(), "Look up this word.", responseSchema = WORD_INFO_RESPONSE_SCHEMA)
            .mapCatching { raw ->
                val json = JSONObject(raw)
                val pronunciation = json.getString("pronunciation").trim()
                val definition = json.getString("definition").trim()
                val translation = json.optString("translation", "").trim()
                    .takeIf { it.isNotEmpty() && !it.equals(word, ignoreCase = true) }
                Triple(pronunciation, definition, translation)
            }
    }

    private fun baseSystemInstruction(
        verseRef: String,
        sourceLangName: String,
        sourceText: String,
        targetLangName: String,
        targetText: String,
    ) = """
        You are a Bible-study assistant helping the user explore $verseRef and its world.
        $sourceLangName text: "$sourceText"
        Official $targetLangName translation: "$targetText"
        Welcome any question about this verse or naturally related Bible-study territory: word meanings, grammar, translation into any language, etymology, historical context, geography, theology, cross-references, related figures (e.g. "who was king David?"), biblical customs, or comparative religion.
        Translate words or phrases on request — even into Latin, Aramaic, Hebrew, Greek, or any other language — and feel free to explore related vocabulary comparisons the user brings up.
        Only gently redirect if the question is truly unrelated to the Bible or faith (e.g. sports, cooking, current events). Even then, keep it warm — one sentence, then offer to continue Bible study.
        Keep answers short and plain — 1–3 sentences unless the user explicitly asks for more depth.
    """.trimIndent()

    /**
     * Compares [spokenText] (the user's speech transcript) against [expectedVerseText] and
     * returns a structured judgment: good read → advance; bad read → gentle retry prompt;
     * question/statement → conversational answer. The reply is already in [sourceLangName].
     *
     * This is the only Gemini call partner reading makes per user turn — kept very brief so
     * the round-trip feels fast between verses.
     */
    suspend fun judgePartnerReading(
        apiKey: String,
        expectedVerseText: String,
        sourceLangName: String,
        spokenText: String,
    ): Result<PartnerReadingJudgment> {
        val system = """
            You are a gentle Bible reading companion running a Partner Reading exercise.
            The user was asked to read this verse aloud in $sourceLangName:
            "$expectedVerseText"
            
            Their speech was transcribed as:
            "$spokenText"
            
            Classify this into exactly one of:
            - GOOD_READ  : They read the verse correctly, or close enough (minor word slips are fine)
            - BAD_READ   : Most of the verse is missing, badly garbled, or the transcript is clearly wrong
            - QUESTION_OR_STATEMENT : They said something unrelated to reading the verse (a question, a comment, etc.)
            
            Set "kind" to one of those three exact strings.
            Set "reply" to a response in $sourceLangName:
            - GOOD_READ           → 1 very short warm affirmation (max 8 words — they want to keep reading)
            - BAD_READ            → 1-2 gentle, encouraging sentences
            - QUESTION_OR_STATEMENT → a concise, helpful answer staying focused on the Bible passage
        """.trimIndent()
        return callGemini(apiKey, system, emptyList(), spokenText, PARTNER_JUDGMENT_SCHEMA)
            .mapCatching { json ->
                val obj = JSONObject(json)
                val kindStr = obj.optString("kind", "GOOD_READ")
                val reply = obj.optString("reply", "")
                val kind = when (kindStr) {
                    "BAD_READ" -> PartnerJudgmentKind.BAD_READ
                    "QUESTION_OR_STATEMENT" -> PartnerJudgmentKind.QUESTION_OR_STATEMENT
                    else -> PartnerJudgmentKind.GOOD_READ
                }
                PartnerReadingJudgment(kind, reply)
            }
    }

    private suspend fun callGemini(
        apiKey: String,
        systemInstruction: String,
        history: List<ChatMessage>,
        userMessage: String,
        responseSchema: JSONObject?,
    ): Result<String> = withContext(Dispatchers.IO) {
        try {
            val contents = JSONArray()
            for (message in history) {
                contents.put(
                    JSONObject()
                        .put("role", if (message.role == ChatRole.USER) "user" else "model")
                        .put("parts", JSONArray().put(JSONObject().put("text", message.text))),
                )
            }
            contents.put(
                JSONObject()
                    .put("role", "user")
                    .put("parts", JSONArray().put(JSONObject().put("text", userMessage))),
            )

            val url = URL("https://generativelanguage.googleapis.com/v1beta/models/$MODEL:generateContent?key=$apiKey")
            val connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "POST"
            connection.setRequestProperty("Content-Type", "application/json")
            connection.doOutput = true
            connection.connectTimeout = 15_000
            connection.readTimeout = 15_000

            val generationConfig = JSONObject().put("temperature", 0.3)
            if (responseSchema != null) {
                generationConfig.put("responseMimeType", "application/json")
                generationConfig.put("responseSchema", responseSchema)
            }

            val body = JSONObject().apply {
                put("contents", contents)
                put(
                    "systemInstruction",
                    JSONObject().put("parts", JSONArray().put(JSONObject().put("text", systemInstruction))),
                )
                put("generationConfig", generationConfig)
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

            Result.success(text)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}

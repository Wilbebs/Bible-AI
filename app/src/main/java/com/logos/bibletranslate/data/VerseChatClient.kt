package com.logos.bibletranslate.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
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
            .put("reply", JSONObject().put("type", "STRING"))
            .put("isComplete", JSONObject().put("type", "BOOLEAN")),
    )
    .put("required", JSONArray().put("kind").put("reply").put("isComplete"))

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
     * Compares [spokenText] (the user's speech transcript, possibly accumulated across more
     * than one listenOnce() call if an earlier pass was judged incomplete) against
     * [expectedVerseText] and returns a structured judgment: complete read → advance; incomplete
     * read → silently keep listening for the rest; question/statement → conversational answer.
     * The reply is already in [sourceLangName].
     *
     * [recentVersesContext] is a short block of recently-read verse text (not graded against —
     * purely so a question like "what did the verse before that mean?" has something to recall)
     * and [history] is the partner session's own running Q&A — both bounded by the caller so this
     * stays the fast, cheap call it needs to be between verses.
     */
    suspend fun judgePartnerReading(
        apiKey: String,
        expectedVerseText: String,
        sourceLangName: String,
        spokenText: String,
        recentVersesContext: String,
        history: List<ChatMessage>,
    ): Result<PartnerReadingJudgment> {
        val system = """
            You are a Bible reading companion running a Partner Reading exercise. The user was
            asked to read this verse aloud in $sourceLangName:
            "$expectedVerseText"

            Their speech was transcribed as:
            "$spokenText"

            Recently read verses, for your own recall if they ask about something earlier (not
            something to grade the transcript against):
            $recentVersesContext

            This is a practice/flow exercise, not a grading exercise — the goal is to keep the
            reading moving, not to correct pronunciation or word-perfect accuracy. Speech-to-text
            transcripts of a second language are frequently garbled, code-switched, or only
            partially recognized even when the person read it just fine out loud, so a rough or
            imperfect transcript is NOT by itself a reason to call this a question — only its
            actual content decides that.

            Classify into exactly one of:
            - READ_ATTEMPT : The transcript's words substantially overlap with or resemble the
              expected verse text above — even badly mangled by transcription (missed words,
              accent-driven errors, code-switching, only loosely resembling the verse). Use this
              whenever the transcript is recognizably an attempt at THIS verse's actual content,
              however rough the transcription is.
            - QUESTION_OR_STATEMENT : The transcript does NOT meaningfully share content with the
              expected verse — it's asking something, commenting, or continuing a conversation
              instead. This explicitly includes short follow-ups ("why?", "what about that part?",
              "can you explain more", "and?") that only make sense in light of the conversation
              history above — if the history shows you just answered a question, a short,
              verse-unrelated utterance right after is almost always another follow-up in that
              same conversation, not a garbled reading attempt. People essentially never resume
              reading with a one- or two-word reply to their own question's answer.

            When torn between the two, ask: does this transcript share real content with the
            verse text, or does it read as directed at you as a conversational partner (including
            continuing something from the history above)? Favor READ_ATTEMPT only when there's
            actual overlap with the verse itself; favor QUESTION_OR_STATEMENT whenever the
            transcript reads as talking TO you rather than reciting scripture.

            Set "kind" to one of those two exact strings.
            Set "reply":
            - READ_ATTEMPT → always the empty string "" (nothing is spoken; the app just moves on)
            - QUESTION_OR_STATEMENT → respond naturally and conversationally in $sourceLangName,
              like a knowledgeable, warm reading companion would. Answer whatever they actually
              asked or said — use your own judgment about how to be helpful here rather than
              forcing every reply back onto the current verse; if they go off-topic, engage with
              that genuinely instead of redirecting them back to the passage. Use the recently-read
              verses above if their question refers to something earlier.

            Set "isComplete" (only meaningful when kind is READ_ATTEMPT — set it to true otherwise):
            - true if the transcript's content reasonably covers the verse from beginning to end —
              again, imperfect wording/accuracy is completely fine, this is purely about whether
              they got to the end of the verse, not how well they said it.
            - false ONLY if the transcript clearly stops partway through and is missing a
              meaningful trailing portion of the verse — e.g. it covers the first half and then
              just ends, as if the recognizer cut them off mid-sentence. When genuinely unsure,
              prefer true — this must not become a new way to nitpick the read.
        """.trimIndent()
        return callGemini(apiKey, system, history, spokenText, PARTNER_JUDGMENT_SCHEMA)
            .mapCatching { json ->
                val obj = JSONObject(json)
                val kindStr = obj.optString("kind", "READ_ATTEMPT")
                val reply = obj.optString("reply", "")
                val isComplete = obj.optBoolean("isComplete", true)
                // READ_ATTEMPT is the new, deliberately-permissive default (see the prompt above)
                // — everything except an unmistakable question maps to GOOD_READ so the ViewModel
                // just keeps moving. BAD_READ is still accepted from the model as a synonym in
                // case older prompt phrasing lingers in a cached response, but nothing here asks
                // for it anymore.
                val kind = when (kindStr) {
                    "QUESTION_OR_STATEMENT" -> PartnerJudgmentKind.QUESTION_OR_STATEMENT
                    "BAD_READ" -> PartnerJudgmentKind.BAD_READ
                    else -> PartnerJudgmentKind.GOOD_READ
                }
                PartnerReadingJudgment(kind, reply, isComplete)
            }
    }

    private fun isTransient(code: Int) = code == 503 || code == 429 || code == 500

    private class TransientHttpException(val code: Int, message: String) : Exception(message)

    /** Retries transient failures (Gemini capacity/rate errors, and read timeouts — a stalled
     *  request is often faster to just retry than to keep waiting on) up to twice with a short
     *  backoff before giving up, the same pattern used for TTS's transient-error handling. This
     *  is the only Gemini call partner reading makes per turn, so a single hard failure here used
     *  to surface as a bare "timed out" with no recovery. */
    private suspend fun callGemini(
        apiKey: String,
        systemInstruction: String,
        history: List<ChatMessage>,
        userMessage: String,
        responseSchema: JSONObject?,
    ): Result<String> {
        for (attempt in 0..2) {
            if (attempt > 0) delay(400L * attempt)
            val result = callGeminiOnce(apiKey, systemInstruction, history, userMessage, responseSchema)
            if (result.isSuccess) return result
            val transient = result.exceptionOrNull() is TransientHttpException ||
                result.exceptionOrNull() is java.net.SocketTimeoutException
            if (!transient) return result
            if (attempt == 2) return Result.failure(Exception("Gemini is busy right now — try again in a moment."))
        }
        error("unreachable")
    }

    private suspend fun callGeminiOnce(
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
            connection.readTimeout = 12_000

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
                val code = connection.responseCode
                if (isTransient(code)) return@withContext Result.failure(TransientHttpException(code, "HTTP $code"))
                val error = connection.errorStream?.bufferedReader()?.readText() ?: "HTTP $code"
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

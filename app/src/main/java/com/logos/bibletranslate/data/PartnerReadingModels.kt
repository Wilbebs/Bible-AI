package com.logos.bibletranslate.data

/** Which half of the partner-reading turn cycle is currently active. */
enum class PartnerTurn {
    AI_SPEAKING,          // TTS is reading the AI's verse aloud
    AWAITING_USER,        // Waiting for user to tap the mic
    LISTENING,            // SpeechRecognizer is capturing audio
    AI_RESPONDING,        // Gemini is judging the transcript / answering a question
}

/** What Gemini decides after comparing the user's speech to the expected verse. */
enum class PartnerJudgmentKind {
    GOOD_READ,            // Transcript matches the verse closely enough — advance
    BAD_READ,             // Transcript is too far off — encourage a retry
    QUESTION_OR_STATEMENT // User said something unrelated — answer it, then return to AWAITING_USER
}

data class PartnerReadingJudgment(
    val kind: PartnerJudgmentKind,
    /** Brief affirmation, gentle retry note, or conversational answer — already in the reading language. */
    val reply: String,
    /**
     * Only meaningful when [kind] is GOOD_READ/BAD_READ (a read attempt, not a question): true if
     * the transcript reasonably covers the verse from beginning to end — imperfect wording,
     * mispronunciation, or transcription noise is fine, this is about COVERAGE, not accuracy —
     * false if it clearly stops partway through and is missing a meaningful trailing portion
     * (e.g. the recognizer's silence-endpoint cut them off mid-verse). Defaults to true so a
     * response missing this field (older cached behavior, a parsing gap) doesn't stall the
     * session by endlessly treating everything as incomplete.
     */
    val isComplete: Boolean = true,
)

/** Which of partner reading's three modes is active. */
enum class PartnerReadingMode {
    /** AI and user alternate verse by verse — the original back-and-forth experience. */
    PARTNER_READ,
    /** The AI reads every verse in sequence; the user can interject a question between verses
     *  (not true mid-speech interruption — see ReaderViewModel's speakReadAloudVerse doc). */
    READ_ALOUD,
    /** The user reads every verse themselves; the AI listens silently and only ever speaks if
     *  asked a question. */
    SOLO_READ,
}

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
)

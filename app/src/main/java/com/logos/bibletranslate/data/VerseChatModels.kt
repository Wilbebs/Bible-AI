package com.logos.bibletranslate.data

/** A single turn in a verse-scoped chat bubble (addendum §2). Not persisted across app sessions. */
data class ChatMessage(
    val role: ChatRole,
    val text: String,
    /** Set for a failed-request message (e.g. network error) so the UI can render it as plain, non-tappable text instead of AI content. */
    val isError: Boolean = false,
    /** Set when this message was injected by tapping a verse reference hyperlink — rendered as a
     *  scripture card rather than a chat turn so it stays visually distinct from AI replies. */
    val isVerseCard: Boolean = false,
)

enum class ChatRole { USER, ASSISTANT }

/** Max user+assistant exchange pairs per bubble session before requiring "Start Over" (addendum §6). */
const val MAX_CHAT_EXCHANGES = 10

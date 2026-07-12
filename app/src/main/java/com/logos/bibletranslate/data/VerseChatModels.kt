package com.logos.bibletranslate.data

/** A single turn in a verse-scoped chat bubble (addendum §2). Not persisted across app sessions. */
data class ChatMessage(
    val role: ChatRole,
    val text: String,
)

enum class ChatRole { USER, ASSISTANT }

/** Max user+assistant exchange pairs per bubble session before requiring "Start Over" (addendum §6). */
const val MAX_CHAT_EXCHANGES = 10

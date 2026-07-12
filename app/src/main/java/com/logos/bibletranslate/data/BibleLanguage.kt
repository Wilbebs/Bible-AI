package com.logos.bibletranslate.data

enum class BibleLanguage(val code: String, val displayName: String, val assetFileName: String, val translationName: String) {
    EN("en", "English", "kjv.db", "KJV"),
    ES("es", "Español", "rv1909.db", "Reina Valera"),
    PT("pt", "Português", "almeida1911.db", "Almeida"),
    ;

    /** e.g. "Español (Reina Valera 1909)" — shown in language pickers so the actual translation is clear, not just the language. */
    val displayNameWithTranslation: String get() = "$displayName ($translationName)"

    companion object {
        fun fromCode(code: String): BibleLanguage? = entries.firstOrNull { it.code.equals(code, ignoreCase = true) }
    }
}

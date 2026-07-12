package com.logos.bibletranslate.data

enum class BibleLanguage(val code: String, val displayName: String, val assetFileName: String) {
    EN("en", "English", "kjv.db"),
    ES("es", "Español", "rv1909.db"),
    PT("pt", "Português", "almeida1911.db"),
    ;

    companion object {
        fun fromCode(code: String): BibleLanguage? = entries.firstOrNull { it.code.equals(code, ignoreCase = true) }
    }
}

package com.logos.bibletranslate.data

enum class BibleLanguage(val code: String, val displayName: String, val assetFileName: String, val translationName: String) {
    // ── English ──────────────────────────────────────────────────────────────
    EN("en",  "English",    "kjv.db",               "KJV"),
    WEB("en", "English",    "web.db",                "WEB"),
    BBE("en", "English",    "bbe.db",                "BBE"),
    // ── Spanish ──────────────────────────────────────────────────────────────
    ES("es",  "Español",    "rv1909.db",             "RV 1909"),
    // ── Portuguese ───────────────────────────────────────────────────────────
    PT("pt",  "Português",  "almeida1911.db",        "Almeida"),
    PT_LIVRE("pt", "Português", "biblia_livre_pt.db","B. Livre"),
    // ── Biblical languages ────────────────────────────────────────────────────
    HE("he",  "Hebrew",     "wlc_hebrew.db",         "WLC"),
    GR("el",  "Greek",      "tischendorf_greek.db",  "Tisch."),
    AR("arc", "Aramaic",    "peshitta_aramaic.db",   "Peshitta"),
    LA("la",  "Latin",      "vulgate_latin.db",      "Vulgate"),
    ;

    /** e.g. "English (KJV)" — shown in language pickers so the actual translation is clear. */
    val displayNameWithTranslation: String get() = "$displayName ($translationName)"

    companion object {
        fun fromCode(code: String): BibleLanguage? = entries.firstOrNull { it.code.equals(code, ignoreCase = true) }
    }
}

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
    // ── Chinese ──────────────────────────────────────────────────────────────
    ZH("zh",  "Chinese",    "chiunl_chinese.db",     "Wenli Union"),
    // ── Biblical languages ────────────────────────────────────────────────────
    HE("he",  "Hebrew",     "wlc_hebrew.db",         "WLC"),
    GR("el",  "Greek",      "tischendorf_greek.db",  "Tisch."),
    AR("arc", "Aramaic",    "peshitta_aramaic.db",   "Peshitta"),
    LA("la",  "Latin",      "vulgate_latin.db",      "Vulgate"),
    ;

    /** e.g. "English (KJV)" — shown in language pickers so the actual translation is clear. */
    val displayNameWithTranslation: String get() = "$displayName ($translationName)"

    /** True for the small set bundled in the APK itself — everything else must be downloaded
     *  from the Languages settings window (TranslationDownloadManager) before it can be read. */
    val isBundledByDefault: Boolean get() = this in DEFAULT_DOWNLOADED

    companion object {
        /** Ships inside the APK, available with no download step. Kept in sync with
         *  BUNDLED_SOURCES in scripts/build_bible_dbs.mjs — everything else lives in
         *  bible_downloads/ in the repo and is fetched on demand. */
        val DEFAULT_DOWNLOADED = setOf(EN, WEB, ES, ZH, GR)

        fun fromCode(code: String): BibleLanguage? = entries.firstOrNull { it.code.equals(code, ignoreCase = true) }
    }
}

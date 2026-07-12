package com.logos.bibletranslate.data

import com.logos.bibletranslate.BuildConfig

/**
 * Dev-time only keys, baked into the app at compile time from
 * local.properties (gitignored, never committed) — not visible or
 * editable by the end user via any in-app UI. A real shipped app would
 * proxy these through a backend instead of embedding them in the APK.
 */
object ApiKeys {
    val geminiApiKey: String? = BuildConfig.GEMINI_API_KEY.takeIf { it.isNotBlank() }
    val translateApiKey: String? = BuildConfig.TRANSLATE_API_KEY.takeIf { it.isNotBlank() }
}

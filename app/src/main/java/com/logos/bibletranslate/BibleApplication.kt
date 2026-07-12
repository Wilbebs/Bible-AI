package com.logos.bibletranslate

import android.app.Application
import com.logos.bibletranslate.data.BibleRepository
import com.logos.bibletranslate.data.GeminiLiveTranslateClient
import com.logos.bibletranslate.data.GoogleTranslateLiveClient
import com.logos.bibletranslate.data.LiveTranslationCache
import com.logos.bibletranslate.data.VerseChatCache
import com.logos.bibletranslate.data.VerseChatClient
import com.logos.bibletranslate.data.WordTranslationRepository

class BibleApplication : Application() {
    val repository: BibleRepository by lazy { BibleRepository(this) }
    val wordTranslationRepository: WordTranslationRepository by lazy { WordTranslationRepository(this) }
    val liveTranslateClient: GeminiLiveTranslateClient by lazy { GeminiLiveTranslateClient() }
    val googleTranslateClient: GoogleTranslateLiveClient by lazy { GoogleTranslateLiveClient() }
    val liveTranslationCache: LiveTranslationCache by lazy { LiveTranslationCache(this) }
    val verseChatClient: VerseChatClient by lazy { VerseChatClient() }
    val verseChatCache: VerseChatCache by lazy { VerseChatCache(this) }
}

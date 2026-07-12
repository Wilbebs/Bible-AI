package com.logos.bibletranslate.ui.reader

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.logos.bibletranslate.data.BibleRepository
import com.logos.bibletranslate.data.GeminiLiveTranslateClient
import com.logos.bibletranslate.data.GoogleTranslateLiveClient
import com.logos.bibletranslate.data.LiveTranslationCache
import com.logos.bibletranslate.data.VerseChatCache
import com.logos.bibletranslate.data.VerseChatClient
import com.logos.bibletranslate.data.WordTranslationRepository

class ReaderViewModelFactory(
    private val repository: BibleRepository,
    private val wordTranslationRepository: WordTranslationRepository,
    private val liveTranslateClient: GeminiLiveTranslateClient,
    private val googleTranslateClient: GoogleTranslateLiveClient,
    private val liveTranslationCache: LiveTranslationCache,
    private val verseChatClient: VerseChatClient,
    private val verseChatCache: VerseChatCache,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        return ReaderViewModel(
            repository, wordTranslationRepository, liveTranslateClient, googleTranslateClient,
            liveTranslationCache, verseChatClient, verseChatCache,
        ) as T
    }
}

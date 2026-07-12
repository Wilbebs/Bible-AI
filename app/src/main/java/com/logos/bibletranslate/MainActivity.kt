package com.logos.bibletranslate

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.logos.bibletranslate.ui.reader.ReaderScreen
import com.logos.bibletranslate.ui.theme.BibleTranslateTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = application as BibleApplication
        setContent {
            BibleTranslateTheme {
                ReaderScreen(
                    repository = app.repository,
                    wordTranslationRepository = app.wordTranslationRepository,
                    liveTranslateClient = app.liveTranslateClient,
                    googleTranslateClient = app.googleTranslateClient,
                    liveTranslationCache = app.liveTranslationCache,
                    verseChatClient = app.verseChatClient,
                    verseChatCache = app.verseChatCache,
                )
            }
        }
    }
}

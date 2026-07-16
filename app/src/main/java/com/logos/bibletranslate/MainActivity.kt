package com.logos.bibletranslate

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.logos.bibletranslate.ui.reader.ReaderScreen
import com.logos.bibletranslate.ui.theme.BibleTranslateTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Request the highest available refresh rate (up to 120 Hz) so the OS
        // can use adaptive refresh — 60/90/120 Hz as motion demands — rather
        // than locking to 60 Hz. On devices that don't support higher rates the
        // request is silently ignored. Must be set before setContent so the
        // display mode is negotiated before the first frame is drawn.
        window.attributes = window.attributes.also { it.preferredRefreshRate = 120f }

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

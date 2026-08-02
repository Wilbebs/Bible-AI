package com.logos.bibletranslate

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.lifecycle.viewmodel.compose.viewModel
import com.logos.bibletranslate.ui.auth.AuthViewModel
import com.logos.bibletranslate.ui.auth.AuthViewModelFactory
import com.logos.bibletranslate.ui.auth.LoginScreen
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
                AppRoot(app)
            }
        }
    }
}

/**
 * Login gate: shows [LoginScreen] until FirebaseAuth reports a signed-in user, then shows the
 * reader. FirebaseAuth persists the session itself — this only re-shows the login screen on a
 * genuinely fresh device or after sign-out/reinstall, never on every app launch.
 */
@Composable
private fun AppRoot(app: BibleApplication) {
    val authViewModel: AuthViewModel = viewModel(factory = AuthViewModelFactory(app.authRepository))
    // authState() is a cold Flow — collect it directly rather than through the ViewModel's own
    // uiState, since sign-in state needs to be known before AuthViewModel's login-form state is
    // relevant at all (and ReaderScreen has no ViewModel of its own that owns this).
    val currentUser by remember { app.authRepository.authState() }
        .collectAsState(initial = app.authRepository.currentUser)

    // Firebase not set up yet (no google-services.json) → skip the login gate entirely so the
    // reader keeps working exactly as it did before this feature existed, rather than stranding
    // everyone on a login screen that can't actually sign anyone in yet.
    val needsLogin = app.authRepository.isConfigured && currentUser == null

    if (needsLogin) {
        LoginScreen(viewModel = authViewModel)
    } else {
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
